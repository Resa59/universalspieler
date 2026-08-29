package de.rdoe.weeklydjshows.discovery.feed

import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

interface FeedVerifier {
    fun verify(url: String, level: VerificationLevel = VerificationLevel.RECENT_EPISODES): FeedVerification
}

class DefaultFeedVerifier(
    private val http: DiscoveryHttpClient,
    private val userAgent: String = "WeeklyDJShowsDiscovery/0.1.0"
) : FeedVerifier {
    override fun verify(url: String, level: VerificationLevel): FeedVerification {
        val normalized = TextTools.normalizeUrl(url)
            ?: return FeedVerification(url, status = FeedStatus.UNSUPPORTED_URL, error = "Only valid HTTP(S) URLs are supported")
        return try {
            val response = http.execute(
                HttpRequest(
                    url = normalized,
                    headers = mapOf(
                        "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.2",
                        "User-Agent" to userAgent
                    ),
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 10_000,
                    maxBytes = if (level == VerificationLevel.HEADERS_ONLY) 64 * 1024 else 4 * 1024 * 1024,
                    // A preview should stay data-bounded without rejecting a perfectly valid
                    // multi-megabyte podcast feed. Parsing recovers complete entries from the
                    // bounded prefix below.
                    allowTruncatedResponse = true,
                )
            )
            if (level == VerificationLevel.HEADERS_ONLY) {
                return FeedVerification(
                    requestedUrl = url,
                    finalUrl = response.finalUrl,
                    httpStatus = response.statusCode,
                    contentType = response.header("Content-Type"),
                    status = if (response.statusCode in 200..299) FeedStatus.UNKNOWN else FeedStatus.UNREACHABLE
                )
            }
            if (response.statusCode !in 200..299) {
                return FeedVerification(
                    requestedUrl = url,
                    finalUrl = response.finalUrl,
                    httpStatus = response.statusCode,
                    contentType = response.header("Content-Type"),
                    status = FeedStatus.UNREACHABLE,
                    error = "HTTP ${response.statusCode}"
                )
            }
            parse(url, response)
        } catch (tooLarge: ResponseTooLargeException) {
            FeedVerification(url, status = FeedStatus.TOO_LARGE, error = tooLarge.message)
        } catch (error: Throwable) {
            FeedVerification(url, status = FeedStatus.UNREACHABLE, error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun parse(requestedUrl: String, response: HttpResponse): FeedVerification {
        val parseBody = if (response.truncated) recoverTruncatedFeed(response.body) else response.body
        if (parseBody == null) {
            return FeedVerification(
                requestedUrl = requestedUrl,
                finalUrl = response.finalUrl,
                httpStatus = response.statusCode,
                contentType = response.header("Content-Type"),
                status = FeedStatus.TOO_LARGE,
                error = "Feed exceeds the preview limit before a complete episode could be read",
            )
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        safeFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        safeFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        safeFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        safeFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = try {
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver(org.xml.sax.EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) })
            builder.parse(ByteArrayInputStream(parseBody))
        } catch (error: Throwable) {
            return FeedVerification(
                requestedUrl = requestedUrl,
                finalUrl = response.finalUrl,
                httpStatus = response.statusCode,
                contentType = response.header("Content-Type"),
                status = FeedStatus.INVALID_XML,
                error = error.message
            )
        }
        val root = document.documentElement ?: return FeedVerification(requestedUrl, finalUrl = response.finalUrl, status = FeedStatus.NOT_A_FEED)
        val rootName = localName(root).lowercase()
        val kind = when (rootName) {
            "rss" -> FeedKind.RSS
            "feed" -> FeedKind.ATOM
            "rdf", "rdf:rdf" -> FeedKind.RDF
            else -> FeedKind.UNKNOWN
        }
        if (kind == FeedKind.UNKNOWN) {
            return FeedVerification(requestedUrl, finalUrl = response.finalUrl, httpStatus = response.statusCode, contentType = response.header("Content-Type"), status = FeedStatus.NOT_A_FEED)
        }

        val container = when (kind) {
            FeedKind.RSS -> firstChild(root, "channel") ?: root
            else -> root
        }
        val title = childText(container, "title")
        val description = firstNonBlank(
            childText(container, "description"),
            childText(container, "subtitle"),
            descendantText(container, "summary", namespaceHint = "itunes"),
        )?.let(TextTools::stripHtml)
        val website = when (kind) {
            FeedKind.ATOM -> linkByRel(container, "alternate") ?: childText(container, "link")
            else -> childText(container, "link")
        }
        val image = firstNonBlank(
            attributeFromDescendant(container, "image", "href", namespaceHint = "itunes"),
            descendantText(container, "url", parentName = "image"),
            attributeFromDescendant(container, "thumbnail", "url", namespaceHint = "media")
        )
        val guid = firstNonBlank(
            descendantText(container, "guid", namespaceHint = "podcast"),
            descendantText(container, "guid")
        )
        val medium = descendantText(container, "medium", namespaceHint = "podcast")
        val categories = descendants(container, "category").mapNotNull { element ->
            element.getAttribute("text").takeIf { it.isNotBlank() } ?: element.textContent?.trim()?.takeIf { it.isNotBlank() }
        }.toSet()

        val entries = when (kind) {
            FeedKind.ATOM -> children(root, "entry")
            else -> descendants(container, "item")
        }.take(100)
        val episodeDates = mutableListOf<Long>()
        val episodeTitles = mutableListOf<String>()
        var audio = 0
        var video = 0
        var episodeImages = 0
        val warnings = mutableListOf<String>()
        if (response.truncated) warnings += "Vorschau auf ${response.body.size / 1024} KiB Feed-Daten begrenzt"
        entries.forEach { entry ->
            childText(entry, "title")?.let { episodeTitles += it }
            val date = firstNonBlank(
                childText(entry, "pubDate"),
                childText(entry, "published"),
                childText(entry, "updated"),
                descendantText(entry, "date", namespaceHint = "dc")
            )?.let { TextTools.parseDate(it) }
            if (date != null) episodeDates += date
            if (attributeFromDescendant(entry, "image", "href", namespaceHint = "itunes") != null ||
                attributeFromDescendant(entry, "thumbnail", "url", namespaceHint = "media") != null) episodeImages++

            val enclosures = descendants(entry, "enclosure") + descendants(entry, "content").filter { localPrefix(it).equals("media", true) }
            val atomEnclosures = descendants(entry, "link").filter { it.getAttribute("rel").equals("enclosure", true) }
            (enclosures + atomEnclosures).forEach enclosureLoop@ { enclosure ->
                val mediaUrl = firstNonBlank(enclosure.getAttribute("url"), enclosure.getAttribute("href")) ?: return@enclosureLoop
                val type = enclosure.getAttribute("type").lowercase()
                when {
                    type.startsWith("audio/") || mediaUrl.matches(Regex("(?i).+\\.(mp3|m4a|aac|ogg|opus|wav)(\\?.*)?$")) -> audio++
                    type.startsWith("video/") || mediaUrl.matches(Regex("(?i).+\\.(mp4|m4v|webm)(\\?.*)?$")) -> video++
                }
            }
        }
        if (entries.isNotEmpty() && episodeDates.isEmpty()) warnings += "No parseable episode dates"
        if (entries.isEmpty()) warnings += "Feed contains no episodes/entries"

        val now = System.currentTimeMillis()
        val sortedDates = episodeDates.distinct().sortedDescending()
        val lastPublished = sortedDates.firstOrNull()
        val recent30 = sortedDates.count { now - it <= TimeUnit.DAYS.toMillis(30) }
        val recent90 = sortedDates.count { now - it <= TimeUnit.DAYS.toMillis(90) }
        val intervals = sortedDates.zipWithNext().map { (newer, older) -> abs(newer - older).toDouble() / TimeUnit.DAYS.toMillis(1).toDouble() }.filter { it > 0.05 }
        val medianDays = TextTools.median(intervals)
        val regularity = determineRegularity(sortedDates.size, intervals, medianDays)
        val activity = determineActivity(lastPublished, medianDays, now)
        val status = when {
            audio > 0 -> FeedStatus.VALID_AUDIO_FEED
            video > 0 -> FeedStatus.VALID_VIDEO_FEED
            else -> FeedStatus.VALID_FEED_WITHOUT_MEDIA
        }
        return FeedVerification(
            requestedUrl = requestedUrl,
            finalUrl = response.finalUrl,
            httpStatus = response.statusCode,
            contentType = response.header("Content-Type"),
            feedKind = kind,
            status = status,
            title = title,
            description = description,
            websiteUrl = website,
            imageUrl = image,
            episodeImageCount = episodeImages,
            episodeCount = entries.size,
            audioEnclosureCount = audio,
            videoEnclosureCount = video,
            lastPublishedEpochMillis = lastPublished,
            recentEpisodeCount30Days = recent30,
            recentEpisodeCount90Days = recent90,
            activityStatus = activity,
            regularityStatus = regularity,
            medianIntervalDays = medianDays,
            podcastGuid = guid,
            podcastMedium = medium,
            categories = categories,
            episodeTitles = episodeTitles.take(30),
            warnings = warnings
        )
    }

    /**
     * The byte budget may end in the middle of an RSS item/Atom entry. Keep everything through
     * the last complete entry and add only the missing root closing tags. ISO-8859-1 is used only
     * as a one-byte-to-one-char search view; the original bytes (and therefore XML encoding) stay
     * untouched.
     */
    private fun recoverTruncatedFeed(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        val view = body.toString(Charsets.ISO_8859_1)
        val rss = Regex("<\\s*rss\\b", RegexOption.IGNORE_CASE).containsMatchIn(view)
        val atom = Regex("<\\s*feed\\b", RegexOption.IGNORE_CASE).containsMatchIn(view)
        val rdf = Regex("<\\s*rdf:rdf\\b", RegexOption.IGNORE_CASE).containsMatchIn(view)
        val closeRegex = if (atom) {
            Regex("</\\s*entry\\s*>", RegexOption.IGNORE_CASE)
        } else {
            Regex("</\\s*item\\s*>", RegexOption.IGNORE_CASE)
        }
        val lastComplete = closeRegex.findAll(view).lastOrNull() ?: return null
        val suffix = when {
            atom -> "</feed>"
            rdf -> "</rdf:RDF>"
            rss -> "</channel></rss>"
            else -> return null
        }
        return body.copyOf(lastComplete.range.last + 1) + suffix.toByteArray(Charsets.US_ASCII)
    }

    private fun determineRegularity(count: Int, intervals: List<Double>, median: Double?): RegularityStatus {
        if (count <= 1) return RegularityStatus.ONE_OFF
        if (intervals.size < 2 || median == null) return RegularityStatus.INSUFFICIENT_DATA
        val deviations = intervals.map { abs(it - median) }
        val mad = TextTools.median(deviations) ?: 0.0
        val variability = if (median > 0) mad / median else 1.0
        if (variability > 0.65) return RegularityStatus.IRREGULAR
        return when {
            median <= 2.5 -> RegularityStatus.DAILY
            median <= 10.5 -> RegularityStatus.WEEKLY
            median <= 20.5 -> RegularityStatus.BIWEEKLY
            median <= 45.0 -> RegularityStatus.MONTHLY
            median <= 120.0 -> RegularityStatus.MULTIMONTHLY
            else -> RegularityStatus.IRREGULAR
        }
    }

    private fun determineActivity(last: Long?, medianDays: Double?, now: Long): ActivityStatus {
        if (last == null) return ActivityStatus.UNKNOWN
        val days = (now - last).coerceAtLeast(0L).toDouble() / TimeUnit.DAYS.toMillis(1).toDouble()
        val expected = (medianDays ?: 14.0).coerceAtLeast(3.0)
        val overdueRatio = days / expected
        return when {
            days <= 30 -> ActivityStatus.ACTIVE_RECENT
            days <= 180 && overdueRatio <= 2.5 -> ActivityStatus.ACTIVE_REGULAR
            days <= 180 -> ActivityStatus.ACTIVE_IRREGULAR
            days <= 365 -> ActivityStatus.INACTIVE_RECENTLY
            days <= 730 -> ActivityStatus.INACTIVE_LONG
            else -> ActivityStatus.LIKELY_DISCONTINUED
        }
    }

    private fun safeFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try { factory.setFeature(name, value) } catch (_: Throwable) { }
    }

    private fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')
    private fun localPrefix(node: Node): String = node.prefix ?: node.nodeName.substringBefore(':', "")

    private fun children(parent: Element, name: String): List<Element> = parent.childNodes.asElements().filter { localName(it).equals(name, true) }
    private fun firstChild(parent: Element, name: String): Element? = children(parent, name).firstOrNull()
    private fun descendants(parent: Element, name: String): List<Element> = parent.getElementsByTagNameNS("*", name).asElements()
        .ifEmpty { parent.getElementsByTagName(name).asElements() }

    private fun childText(parent: Element, name: String): String? = firstChild(parent, name)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun descendantText(parent: Element, name: String, parentName: String? = null, namespaceHint: String? = null): String? {
        return descendants(parent, name).firstOrNull { element ->
            val parentMatches = parentName == null || (element.parentNode?.let { localName(it).equals(parentName, true) } == true)
            val namespaceMatches = namespaceHint == null || localPrefix(element).equals(namespaceHint, true) || element.namespaceURI?.contains(namespaceHint, true) == true
            parentMatches && namespaceMatches
        }?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun attributeFromDescendant(parent: Element, name: String, attribute: String, namespaceHint: String? = null): String? {
        return descendants(parent, name).firstOrNull { element ->
            namespaceHint == null || localPrefix(element).equals(namespaceHint, true) || element.namespaceURI?.contains(namespaceHint, true) == true
        }?.getAttribute(attribute)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun linkByRel(parent: Element, rel: String): String? = descendants(parent, "link")
        .firstOrNull { it.getAttribute("rel").equals(rel, true) }
        ?.getAttribute("href")?.takeIf { it.isNotBlank() }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    private fun NodeList.asElements(): List<Element> = buildList {
        for (i in 0 until length) (item(i) as? Element)?.let { add(it) }
    }
}
