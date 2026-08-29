package de.rdoe.weeklydjshows.discovery.internal

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

object TextTools {
    /** Extracts a share URL even when an app puts a title or marketing text around it. */
    fun firstHttpUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
            .find(value)
            ?.value
            ?.trimEnd('.', ',', ';', '!', ')', ']', '}')
            ?.takeIf { normalizeUrl(it) != null }
    }

    fun stripHtml(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun normalizeText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun tokens(value: String?): Set<String> = normalizeText(value)
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

    /** True when a platform playlist title is clearly one numbered episode of the query. */
    fun looksLikeEpisodeTitleForQuery(query: String?, title: String?): Boolean {
        val normalizedQuery = normalizeText(query)
        if (normalizedQuery.length < 8 || normalizedQuery.split(' ').count { it.isNotBlank() } < 2) return false
        val normalizedTitle = normalizeText(title)
        val queryIndex = normalizedTitle.indexOf(normalizedQuery)
        if (queryIndex < 0) return false
        val suffix = normalizedTitle.substring(queryIndex + normalizedQuery.length).trim()
        return EPISODE_TITLE_SUFFIX.containsMatchIn(suffix)
    }

    fun similarity(a: String?, b: String?): Double {
        val na = normalizeText(a)
        val nb = normalizeText(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) {
            val ratio = min(na.length, nb.length).toDouble() / max(na.length, nb.length).toDouble()
            return 0.72 + 0.28 * ratio
        }
        val ta = tokens(na)
        val tb = tokens(nb)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union
        val prefix = commonPrefixLength(na, nb).toDouble() / min(12, min(na.length, nb.length)).coerceAtLeast(1)
        return (jaccard * 0.85 + prefix * 0.15).coerceIn(0.0, 1.0)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        val limit = min(a.length, b.length)
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private val EPISODE_TITLE_SUFFIX = Regex("^(?:#?\\d{1,5}\\b|episode\\s*#?\\d+\\b|ep\\s*#?\\d+\\b).*")

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun normalizeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = when {
                uri.port == -1 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val path = (uri.rawPath ?: "").replace(Regex("/{2,}"), "/")
                .let { if (it.length > 1) it.trimEnd('/') else it }
            val query = normalizeQuery(uri.rawQuery)
            URI(scheme, null, host, port, path.ifEmpty { "/" }, query, null).toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        val ignored = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid")
        val pairs = rawQuery.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = URLDecoder.decode(pieces[0], "UTF-8")
            if (key.lowercase(Locale.ROOT) in ignored) null else part
        }
        return pairs.sorted().joinToString("&").takeIf { it.isNotEmpty() }
    }

    fun host(raw: String?): String? = try { URI(raw).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }

    fun stableId(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(parts.joinToString("\u001f") { it.orEmpty() }.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { raw ->
            return if (raw > 100_000_000_000L) raw else raw * 1000L
        }
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy-MM",
            "yyyy"
        )
        for (pattern in formats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val position = ParsePosition(0)
            val date: Date = parser.parse(value.trim(), position) ?: continue
            if (position.index >= value.trim().length - 1) return date.time
        }
        return null
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
