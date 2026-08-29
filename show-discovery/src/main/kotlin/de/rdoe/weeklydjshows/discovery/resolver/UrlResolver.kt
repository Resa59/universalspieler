package de.rdoe.weeklydjshows.discovery.resolver

import de.rdoe.weeklydjshows.discovery.feed.FeedVerifier
import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.*
import java.net.URI

interface UrlResolver {
    fun resolve(input: String): ResolutionResult
}

class DefaultUrlResolver(
    private val http: DiscoveryHttpClient,
    private val feedVerifier: FeedVerifier,
    private val secrets: SecretProvider = EmptySecretProvider,
    private val userAgent: String = "WeeklyDJShowsDiscovery/0.1.0"
) : UrlResolver {
    override fun resolve(input: String): ResolutionResult {
        val cleaned = input.trim()
        if (isRedirectingShareUrl(cleaned)) {
            val expanded = followShareRedirect(cleaned)
                ?: return ResolutionResult(
                    cleaned,
                    emptyList(),
                    error = "Der mobile Teilen-Link konnte nicht auf seine eigentliche Quelle aufgelöst werden",
                )
            // Resolve the canonical destination through the normal pipeline. Keeping the original
            // input in the result makes diagnostics understandable while target URLs stay clean.
            return resolve(expanded).copy(input = cleaned)
        }
        applePodcastId(cleaned)?.let { appleId ->
            return resolveApplePodcast(cleaned, appleId)
        }
        parsePlatform(cleaned)?.let { platformTarget ->
            // YouTube exposes small Atom feeds for both channel IDs and playlist IDs. Resolve and
            // verify those here as well, so pasting a YouTube URL behaves exactly like pasting the
            // corresponding feed URL and can immediately produce a subscribable result.
            val platformFeed = platformTarget.feedUrl
            if (platformFeed != null) {
                val verification = feedVerifier.verify(platformFeed, VerificationLevel.BASIC_FEED)
                val verifiedFeed = verification.finalUrl
                    ?.takeIf {
                        verification.status in setOf(
                            FeedStatus.VALID_AUDIO_FEED,
                            FeedStatus.VALID_VIDEO_FEED,
                            FeedStatus.VALID_FEED_WITHOUT_MEDIA,
                        )
                    }
                    ?: platformFeed
                val enriched = platformTarget.copy(
                    feedUrl = verifiedFeed,
                    title = verification.title ?: platformTarget.title,
                    metadata = platformTarget.metadata + ("atomFeedUrl" to verifiedFeed),
                )
                return ResolutionResult(cleaned, listOf(enriched), feedVerifications = listOf(verification))
            }
            return ResolutionResult(cleaned, listOf(platformTarget))
        }
        val normalized = TextTools.normalizeUrl(cleaned)
            ?: return ResolutionResult(cleaned, emptyList(), error = "Not a valid HTTP(S) URL or supported platform URI")
        val direct = feedVerifier.verify(normalized, VerificationLevel.BASIC_FEED)
        if (direct.status == FeedStatus.VALID_AUDIO_FEED || direct.status == FeedStatus.VALID_VIDEO_FEED || direct.status == FeedStatus.VALID_FEED_WITHOUT_MEDIA) {
            return ResolutionResult(
                input = cleaned,
                targets = listOf(targetFromVerification(direct)),
                feedVerifications = listOf(direct)
            )
        }
        return discoverFromWebsite(normalized, direct)
    }

    /** Apple Podcast pages are catalogue records; Apple's lookup response exposes the actual RSS. */
    private fun resolveApplePodcast(input: String, appleId: String): ResolutionResult {
        return try {
            val response = http.execute(
                HttpRequest(
                    url = "https://itunes.apple.com/lookup?id=${TextTools.encode(appleId)}&entity=podcast",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 8_000,
                    maxBytes = 1024 * 1024,
                ),
            )
            if (response.statusCode !in 200..299) {
                return ResolutionResult(input, emptyList(), error = "Apple Podcasts lookup returned HTTP ${response.statusCode}")
            }
            val root = Json.parse(response.text()).asObject()
                ?: return ResolutionResult(input, emptyList(), error = "Apple Podcasts returned invalid catalogue data")
            val item = root.array("results").asSequence().mapNotNull { it.asObject() }.firstOrNull()
                ?: return ResolutionResult(input, emptyList(), error = "Apple Podcasts entry was not found")
            val feedUrl = item.string("feedUrl")
                ?: return ResolutionResult(input, emptyList(), error = "Apple Podcasts does not expose an RSS feed for this entry")
            val verification = feedVerifier.verify(feedUrl, VerificationLevel.BASIC_FEED)
            val valid = verification.status in setOf(
                FeedStatus.VALID_AUDIO_FEED,
                FeedStatus.VALID_VIDEO_FEED,
                FeedStatus.VALID_FEED_WITHOUT_MEDIA,
            )
            if (!valid) {
                return ResolutionResult(
                    input = input,
                    targets = emptyList(),
                    feedVerifications = listOf(verification),
                    error = "Der von Apple hinterlegte RSS-Feed konnte nicht bestätigt werden (${verification.status}).",
                )
            }
            val verifiedFeed = verification.finalUrl ?: feedUrl
            val appleUrl = item.string("collectionViewUrl") ?: item.string("trackViewUrl") ?: input
            val title = verification.title ?: item.string("collectionName") ?: item.string("trackName")
            val requirement = if (verification.status == FeedStatus.VALID_AUDIO_FEED) {
                IntegrationRequirement.DIRECT_RSS_AUDIO
            } else {
                IntegrationRequirement.FEED_AND_PLATFORM_PLAYER
            }
            ResolutionResult(
                input = input,
                targets = listOf(
                    IntegrationTarget(
                        kind = TargetKind.APPLE_PODCAST,
                        url = appleUrl,
                        stableId = appleId,
                        feedUrl = verifiedFeed,
                        requirement = requirement,
                        title = title,
                        metadata = mapOf("appleId" to appleId),
                    ),
                ),
                feedVerifications = listOf(verification),
            )
        } catch (error: Throwable) {
            ResolutionResult(input, emptyList(), error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun discoverFromWebsite(url: String, initial: FeedVerification): ResolutionResult {
        return try {
            val response = http.execute(
                HttpRequest(
                    url = url,
                    headers = mapOf("Accept" to "text/html,application/xhtml+xml", "User-Agent" to userAgent),
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 8_000,
                    maxBytes = 2 * 1024 * 1024
                )
            )
            if (response.statusCode !in 200..299) {
                return ResolutionResult(url, emptyList(), feedVerifications = listOf(initial), error = "Website returned HTTP ${response.statusCode}")
            }
            val html = response.text()
            val candidates = extractFeedLinks(response.finalUrl, html).toMutableSet()
            if (candidates.isEmpty()) candidates += typicalFeedPaths(response.finalUrl)
            val verifications = candidates.take(8).map { feedVerifier.verify(it, VerificationLevel.BASIC_FEED) }
            val valid = verifications.filter { it.status == FeedStatus.VALID_AUDIO_FEED || it.status == FeedStatus.VALID_VIDEO_FEED || it.status == FeedStatus.VALID_FEED_WITHOUT_MEDIA }
            val platformLinks = extractPlatformLinks(response.finalUrl, html).mapNotNull { parsePlatform(it) }
            val targets = valid.map { targetFromVerification(it) } + platformLinks + IntegrationTarget(
                kind = TargetKind.WEBSITE,
                url = response.finalUrl,
                requirement = IntegrationRequirement.RESOLUTION_REQUIRED
            )
            ResolutionResult(
                input = url,
                targets = targets.distinctBy { "${it.kind}|${it.url}" },
                feedVerifications = listOf(initial) + verifications,
                warnings = if (valid.isEmpty()) listOf("No valid RSS/Atom audio feed discovered on the website") else emptyList()
            )
        } catch (error: Throwable) {
            ResolutionResult(url, emptyList(), feedVerifications = listOf(initial), error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun targetFromVerification(verification: FeedVerification): IntegrationTarget {
        val finalUrl = verification.finalUrl ?: verification.requestedUrl
        return when (verification.status) {
            FeedStatus.VALID_AUDIO_FEED -> IntegrationTarget(TargetKind.RSS_AUDIO, finalUrl, feedUrl = finalUrl, requirement = IntegrationRequirement.DIRECT_RSS_AUDIO, title = verification.title)
            FeedStatus.VALID_VIDEO_FEED -> IntegrationTarget(TargetKind.RSS_VIDEO, finalUrl, feedUrl = finalUrl, requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER, title = verification.title)
            else -> IntegrationTarget(TargetKind.ATOM_FEED, finalUrl, feedUrl = finalUrl, requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER, title = verification.title)
        }
    }

    private fun extractFeedLinks(baseUrl: String, html: String): Set<String> {
        val links = mutableSetOf<String>()
        val linkRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        linkRegex.findAll(html).forEach { match ->
            val tag = match.value
            val rel = attribute(tag, "rel")?.lowercase().orEmpty()
            val type = attribute(tag, "type")?.lowercase().orEmpty()
            val href = attribute(tag, "href")
            if (href != null && rel.contains("alternate") && (type.contains("rss") || type.contains("atom") || type.contains("xml"))) {
                resolveRelative(baseUrl, href)?.let { links += it }
            }
        }
        return links
    }

    private fun extractPlatformLinks(baseUrl: String, html: String): Set<String> {
        val result = mutableSetOf<String>()
        Regex("<a\\b[^>]*href\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).forEach { match ->
                val resolved = resolveRelative(baseUrl, match.groupValues[2]) ?: return@forEach
                if (isPlatformUrl(resolved)) result += resolved
            }
        return result.take(20).toSet()
    }

    private fun attribute(tag: String, name: String): String? {
        val regex = Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(tag)?.groupValues?.getOrNull(2)?.trim()
    }

    private fun resolveRelative(baseUrl: String, value: String): String? = try {
        URI(baseUrl).resolve(value.trim()).toString().takeIf { TextTools.normalizeUrl(it) != null }
    } catch (_: Throwable) { null }

    private fun typicalFeedPaths(baseUrl: String): Set<String> = try {
        val base = URI(baseUrl)
        val origin = URI(base.scheme, null, base.host, base.port, "/", null, null)
        setOf("feed", "rss", "feed.xml", "rss.xml", "podcast.xml").map { origin.resolve(it).toString() }.toSet()
    } catch (_: Throwable) { emptySet() }

    private fun isPlatformUrl(url: String): Boolean {
        val host = TextTools.host(url).orEmpty()
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("spotify.com") ||
            host.endsWith("mixcloud.com") || host.endsWith("soundcloud.com") || host.endsWith("podcasts.apple.com")
    }

    private fun applePodcastId(input: String): String? {
        return try {
            val uri = URI(input)
            val host = uri.host?.lowercase() ?: return null
            if (host != "podcasts.apple.com" && !host.endsWith(".podcasts.apple.com")) return null
            Regex("(?:^|/)id(\\d+)(?:$|/)").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isRedirectingShareUrl(input: String): Boolean = try {
        URI(input).host?.lowercase() in REDIRECTING_SHARE_HOSTS
    } catch (_: Throwable) {
        false
    }

    private fun followShareRedirect(input: String): String? {
        return try {
            val response = http.execute(
                HttpRequest(
                    url = input,
                    headers = mapOf(
                        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.5",
                        "User-Agent" to userAgent,
                    ),
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 7_000,
                    maxBytes = 512 * 1024,
                ),
            )
            if (response.statusCode !in 200..399) {
                null
            } else {
                TextTools.normalizeUrl(response.finalUrl)
                    ?.takeUnless(::isRedirectingShareUrl)
                    ?: extractCanonicalDestination(response.finalUrl, response.text())
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractCanonicalDestination(baseUrl: String, html: String): String? {
        val tags = Regex("<(?:meta|link)\\b[^>]*>", RegexOption.IGNORE_CASE)
        tags.findAll(html).forEach { match ->
            val tag = match.value
            val property = attribute(tag, "property")?.lowercase().orEmpty()
            val rel = attribute(tag, "rel")?.lowercase().orEmpty()
            val candidate = when {
                property == "og:url" -> attribute(tag, "content")
                rel.contains("canonical") -> attribute(tag, "href")
                else -> null
            }
            val resolved = candidate?.let { resolveRelative(baseUrl, it) }
            if (resolved != null && !isRedirectingShareUrl(resolved)) return resolved
        }
        return null
    }

    private fun parsePlatform(input: String): IntegrationTarget? {
        parseSpotifyUri(input)?.let { return it }
        val uri = try { URI(input) } catch (_: Throwable) { return null }
        val host = uri.host?.lowercase() ?: return null
        val pathParts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        val query = parseQuery(uri.rawQuery)
        return when {
            // feeds.soundcloud.com is an ordinary podcast RSS host. Treating these URLs as
            // SoundCloud track/profile pages would bypass feed verification and misclassify the
            // legacy feeds that are intentionally hosted there.
            host == "feeds.soundcloud.com" -> null
            host.endsWith("youtube.com") || host == "youtu.be" -> parseYouTube(uri, host, pathParts, query)
            host.endsWith("spotify.com") -> parseSpotifyWeb(pathParts)
            host.endsWith("mixcloud.com") -> parseMixcloud(input, pathParts)
            host.endsWith("soundcloud.com") -> parseSoundCloud(pathParts)
            else -> null
        }
    }

    private fun parseYouTube(uri: URI, host: String, path: List<String>, query: Map<String, String>): IntegrationTarget? {
        val feedPlaylistId = query["playlist_id"]
        if (!feedPlaylistId.isNullOrBlank()) {
            val feed = "https://www.youtube.com/feeds/videos.xml?playlist_id=$feedPlaylistId"
            return IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=$feedPlaylistId",
                stableId = feedPlaylistId,
                feedUrl = feed,
                requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
                metadata = mapOf("atomFeedUrl" to feed),
            )
        }
        val feedChannelId = query["channel_id"]
        if (!feedChannelId.isNullOrBlank()) {
            val feed = "https://www.youtube.com/feeds/videos.xml?channel_id=$feedChannelId"
            return IntegrationTarget(
                TargetKind.YOUTUBE_CHANNEL,
                "https://www.youtube.com/channel/$feedChannelId",
                stableId = feedChannelId,
                feedUrl = feed,
                requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
                metadata = mapOf("atomFeedUrl" to feed),
            )
        }
        val playlistId = query["list"]
        if (!playlistId.isNullOrBlank()) {
            val feed = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
            return IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=$playlistId",
                stableId = playlistId,
                feedUrl = feed,
                requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
                metadata = mapOf("atomFeedUrl" to feed),
            )
        }
        if (host == "youtu.be" && path.isNotEmpty()) {
            return IntegrationTarget(TargetKind.YOUTUBE_VIDEO, uri.toString(), stableId = path[0], requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
        }
        if (path.firstOrNull() == "channel" && path.size >= 2) {
            val id = path[1]
            val feed = "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
            return IntegrationTarget(TargetKind.YOUTUBE_CHANNEL, "https://www.youtube.com/channel/$id", stableId = id, feedUrl = feed, requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER, metadata = mapOf("atomFeedUrl" to feed))
        }
        if (path.firstOrNull()?.startsWith("@") == true || path.firstOrNull() == "user" || path.firstOrNull() == "c") {
            val channelUrl = "https://www.youtube.com/${path.joinToString("/")}"
            return IntegrationTarget(TargetKind.YOUTUBE_CHANNEL, channelUrl, stableId = path.joinToString("/"), requirement = IntegrationRequirement.RESOLUTION_REQUIRED, metadata = mapOf("reason" to "Channel ID must be resolved with YouTube API or a platform adapter"))
        }
        if (path.firstOrNull() == "watch" && query["v"] != null) {
            return IntegrationTarget(TargetKind.YOUTUBE_VIDEO, uri.toString(), stableId = query["v"], requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
        }
        return IntegrationTarget(TargetKind.EXTERNAL_PLATFORM, uri.toString(), requirement = IntegrationRequirement.RESOLUTION_REQUIRED)
    }

    private fun parseSpotifyWeb(path: List<String>): IntegrationTarget? {
        if (path.size < 2) return null
        val type = path[path.size - 2]
        val id = path.last()
        val kind = when (type) {
            "show" -> TargetKind.SPOTIFY_SHOW
            "playlist" -> TargetKind.SPOTIFY_PLAYLIST
            "artist" -> TargetKind.SPOTIFY_ARTIST
            "episode" -> TargetKind.SPOTIFY_EPISODE
            else -> return null
        }
        return IntegrationTarget(kind, "https://open.spotify.com/$type/$id", stableId = id, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, metadata = mapOf("spotifyType" to type))
    }

    private fun parseSpotifyUri(input: String): IntegrationTarget? {
        if (!input.startsWith("spotify:")) return null
        val parts = input.split(':')
        if (parts.size < 3) return null
        val type = parts[1]
        val id = parts[2]
        val kind = when (type) {
            "show" -> TargetKind.SPOTIFY_SHOW
            "playlist" -> TargetKind.SPOTIFY_PLAYLIST
            "artist" -> TargetKind.SPOTIFY_ARTIST
            "episode" -> TargetKind.SPOTIFY_EPISODE
            else -> TargetKind.EXTERNAL_PLATFORM
        }
        return IntegrationTarget(kind, "https://open.spotify.com/$type/$id", stableId = id, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, metadata = mapOf("spotifyUri" to input))
    }

    private fun parseMixcloud(input: String, path: List<String>): IntegrationTarget {
        return if (path.size >= 2) {
            IntegrationTarget(TargetKind.MIXCLOUD_SHOW, input, stableId = "/${path.joinToString("/")}/", requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
        } else {
            IntegrationTarget(TargetKind.MIXCLOUD_PROFILE, input, stableId = path.firstOrNull(), requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
        }
    }

    private fun parseSoundCloud(path: List<String>): IntegrationTarget {
        val playlistLike = path.any { it.equals("sets", true) }
        val canonicalUrl = "https://soundcloud.com/${path.joinToString("/")}"
        return when {
            playlistLike -> IntegrationTarget(TargetKind.SOUNDCLOUD_PLAYLIST, canonicalUrl, stableId = path.joinToString("/"), requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
            path.size >= 2 -> IntegrationTarget(TargetKind.SOUNDCLOUD_TRACK, canonicalUrl, stableId = path.joinToString("/"), requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
            else -> IntegrationTarget(TargetKind.SOUNDCLOUD_PROFILE, canonicalUrl, stableId = path.firstOrNull(), requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.isEmpty()) null else java.net.URLDecoder.decode(pieces[0], "UTF-8") to java.net.URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        }.toMap()
    }

    private companion object {
        val REDIRECTING_SHARE_HOSTS = setOf(
            "on.soundcloud.com",
            "snd.sc",
            "soundcloud.app.goo.gl",
            "spotify.link",
        )
    }
}
