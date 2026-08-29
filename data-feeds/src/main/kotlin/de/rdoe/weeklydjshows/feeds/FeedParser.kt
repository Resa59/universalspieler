package de.rdoe.weeklydjshows.feeds

import android.os.Build
import android.text.Html
import android.util.Xml
import de.rdoe.weeklydjshows.database.EpisodeEntity
import de.rdoe.weeklydjshows.database.ShowEntity
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedFeed(
    val title: String?,
    val description: String,
    val artworkUrl: String?,
    val episodes: List<EpisodeEntity>,
    /** IDs of every entry in the current feed, including entries beyond the local storage cap. */
    val allEpisodeIds: Set<String>,
)

/**
 * RSS feeds are not required to list their newest items first. Keep enough history for long-running
 * weekly shows while still putting a finite bound on what is persisted per subscription.
 */
const val RSS_STORED_EPISODE_LIMIT = 1_000

private data class ParsedEpisodeCandidate(
    val episode: EpisodeEntity,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val sourceOrder: Int,
)

class FeedParser {
    fun parse(show: ShowEntity, input: InputStream, maxEpisodes: Int = RSS_STORED_EPISODE_LIMIT): ParsedFeed {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        var inItem = false
        var inFeedImage = false
        var currentTag = ""
        var feedTitle: String? = null
        var feedDescription = ""
        var feedImage: String? = null

        var title = ""
        var description = ""
        var published = ""
        var enclosureUrl: String? = null
        var pageUrl: String? = null
        var artwork: String? = null
        var duration = ""
        var guid = ""
        var youtubeVideoId = ""
        var seasonNumber: Int? = null
        var episodeNumber: Int? = null
        var sourceOrder = 0
        val candidates = mutableListOf<ParsedEpisodeCandidate>()

        fun resetItem() {
            title = ""
            description = ""
            published = ""
            enclosureUrl = null
            pageUrl = null
            artwork = null
            duration = ""
            guid = ""
            youtubeVideoId = ""
            seasonNumber = null
            episodeNumber = null
        }

        // Deliberately scan the whole document before applying maxEpisodes. Some valid podcast
        // feeds append a new iTunes season after the old season instead of reverse-chronologically
        // prepending it. Stopping after the first N <item>s therefore drops the current season.
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.lowercase(Locale.ROOT)
                    if (currentTag == "item" || currentTag == "entry") {
                        inItem = true
                        resetItem()
                    }
                    if (!inItem && currentTag == "image") inFeedImage = true

                    if (inItem && currentTag in setOf("enclosure", "media:content")) {
                        val candidate = parser.getAttributeValue(null, "url").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val medium = parser.getAttributeValue(null, "medium").orEmpty()
                        if (duration.isBlank()) {
                            duration = parser.getAttributeValue(null, "duration").orEmpty()
                        }
                        if (candidate.isNotBlank() && (
                                currentTag == "enclosure" ||
                                    type.startsWith("audio", true) ||
                                    medium.equals("audio", true) ||
                                    looksLikeAudio(candidate)
                                )
                        ) enclosureUrl = candidate
                    }

                    if (currentTag == "link") {
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        val rel = parser.getAttributeValue(null, "rel").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        if (inItem && href.isNotBlank()) {
                            if (rel.equals("enclosure", true) || type.startsWith("audio", true)) {
                                enclosureUrl = href
                            } else if (rel.isBlank() || rel.equals("alternate", true)) {
                                pageUrl = pageUrl ?: href
                            }
                        }
                    }

                    if (currentTag in setOf("itunes:image", "media:thumbnail")) {
                        val candidate = parser.getAttributeValue(null, "href")
                            ?: parser.getAttributeValue(null, "url")
                        if (inItem) artwork = candidate ?: artwork else feedImage = candidate ?: feedImage
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        if (inItem) {
                            when (currentTag) {
                                "title" -> title += text
                                "description", "summary", "content:encoded", "media:description" ->
                                    if (description.isBlank()) description = text
                                "pubdate", "published", "updated", "dc:date" -> if (published.isBlank()) published = text
                                "guid", "id" -> if (guid.isBlank()) guid = text
                                "link" -> if (pageUrl.isNullOrBlank()) pageUrl = text
                                "itunes:duration", "duration" -> duration = text
                                "itunes:season" -> if (seasonNumber == null) seasonNumber = text.toIntOrNull()
                                "itunes:episode" -> if (episodeNumber == null) episodeNumber = text.toIntOrNull()
                                "yt:videoid", "videoid" -> youtubeVideoId = text
                            }
                        } else {
                            when {
                                currentTag == "title" && feedTitle == null -> feedTitle = text
                                currentTag in setOf("description", "subtitle") && feedDescription.isBlank() -> feedDescription = stripHtml(text)
                                inFeedImage && currentTag == "url" && feedImage.isNullOrBlank() -> feedImage = text
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val end = parser.name.lowercase(Locale.ROOT)
                    if (end == "image" && !inItem) inFeedImage = false
                    if (end == "item" || end == "entry") {
                        inItem = false
                        if (pageUrl.isNullOrBlank() && youtubeVideoId.isNotBlank()) {
                            pageUrl = "https://www.youtube.com/watch?v=$youtubeVideoId"
                        }
                        val sourceType = determineSource(enclosureUrl, pageUrl)
                        val identity = guid.ifBlank { enclosureUrl ?: pageUrl ?: "$title|$published" }
                        if (title.isNotBlank() || enclosureUrl != null || pageUrl != null) {
                            candidates += ParsedEpisodeCandidate(
                                episode = EpisodeEntity(
                                    id = sha256("${show.id}|$identity"),
                                    showId = show.id,
                                    title = title.ifBlank { "Unbenannte Folge" },
                                    description = stripHtml(description),
                                    pageUrl = pageUrl,
                                    enclosureUrl = enclosureUrl,
                                    sourceType = sourceType,
                                    publishedAtEpochMs = parseDate(published),
                                    publishedText = published,
                                    artworkUrl = artwork,
                                    durationMs = parseDuration(duration),
                                ),
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                sourceOrder = sourceOrder++,
                            )
                        }
                    }
                }
            }
            parser.next()
        }

        // Publication time is authoritative across seasons. iTunes season/episode metadata is a
        // fallback for entries without a usable date; source order is used only as a final stable
        // tie-breaker and never to decide which end of a feed is "new".
        val episodes = candidates
            .sortedWith(
                compareByDescending<ParsedEpisodeCandidate> { it.episode.publishedAtEpochMs ?: Long.MIN_VALUE }
                    .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                    .thenByDescending { it.episodeNumber ?: Int.MIN_VALUE }
                    .thenBy { it.sourceOrder },
            )
            .take(maxEpisodes.coerceAtLeast(1))
            .map { it.episode }

        return ParsedFeed(
            feedTitle,
            feedDescription,
            feedImage,
            episodes,
            candidates.mapTo(linkedSetOf()) { it.episode.id },
        )
    }

    private fun determineSource(enclosure: String?, page: String?): EpisodeSourceType {
        if (!enclosure.isNullOrBlank()) return EpisodeSourceType.DIRECT_AUDIO
        val url = page.orEmpty().lowercase()
        return when {
            "youtube.com" in url || "youtu.be" in url || "music.youtube.com" in url -> EpisodeSourceType.YOUTUBE
            "soundcloud.com" in url -> EpisodeSourceType.SOUNDCLOUD
            "bandcamp.com" in url -> EpisodeSourceType.BANDCAMP
            "peertube" in url -> EpisodeSourceType.PEERTUBE
            else -> EpisodeSourceType.UNKNOWN_WEBPAGE
        }
    }

    private fun looksLikeAudio(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".m3u8", ".mpd").any(path::endsWith)
    }

    @Suppress("DEPRECATION")
    private fun stripHtml(value: String): String {
        if (value.isBlank()) return ""
        val styled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(value)
        }
        return styled.toString()
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trimEnd() }
            .toList()
            .joinToString("\n")
            // FROM_HTML_MODE_LEGACY inserts paragraph spacing that makes tracklists look as if
            // every line were a separate large paragraph. Preserve line breaks, not blank rows.
            .replace(Regex("\n[ \\t]*\n+"), "\n")
            .trim()
    }

    private fun parseDuration(value: String): Long? {
        if (value.isBlank()) return null
        value.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val parts = value.split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty()) return null
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return seconds * 1000
    }

    private fun parseDate(value: String): Long? {
        if (value.isBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        patterns.forEach { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = true
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
