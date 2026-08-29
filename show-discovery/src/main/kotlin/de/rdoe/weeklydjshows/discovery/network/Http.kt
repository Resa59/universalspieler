package de.rdoe.weeklydjshows.discovery.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.GZIPInputStream

class ResponseTooLargeException(message: String) : RuntimeException(message)

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 7_000,
    val maxBytes: Int = 3 * 1024 * 1024,
    val allowTruncatedResponse: Boolean = false,
)

data class HttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val truncated: Boolean = false,
) {
    fun text(): String = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()
}

fun interface DiscoveryHttpClient {
    fun execute(request: HttpRequest): HttpResponse
}

/** Uses APIs available on Android and the regular JVM. */
class UrlConnectionHttpClient(
    private val defaultUserAgent: String = "WeeklyDJShowsDiscovery/0.1.0"
) : DiscoveryHttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URI(request.url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = request.method.uppercase(Locale.ROOT)
            connectTimeout = request.connectTimeoutMillis
            readTimeout = request.readTimeoutMillis
            useCaches = false
            setRequestProperty("User-Agent", request.headers["User-Agent"] ?: defaultUserAgent)
            setRequestProperty("Accept-Encoding", "gzip")
            request.headers.forEach { (key, value) ->
                if (!key.equals("User-Agent", true)) setRequestProperty(key, value)
            }
        }
        try {
            val status = connection.responseCode
            val rawStream = if (status >= 400) connection.errorStream else connection.inputStream
            val stream = rawStream?.let {
                if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) GZIPInputStream(it) else it
            }
            val read = stream?.use {
                readLimited(it, request.maxBytes, request.allowTruncatedResponse)
            } ?: ReadResult(ByteArray(0), truncated = false)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key ?: "" }
                .mapValues { it.value.orEmpty() }
            return HttpResponse(
                statusCode = status,
                finalUrl = connection.url.toString(),
                headers = headers,
                body = read.bytes,
                truncated = read.truncated,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, maxBytes: Int, allowTruncated: Boolean): ReadResult {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            val remaining = maxBytes - total
            if (count > remaining) {
                if (!allowTruncated) throw ResponseTooLargeException("Response exceeded $maxBytes bytes")
                if (remaining > 0) output.write(buffer, 0, remaining)
                return ReadResult(output.toByteArray(), truncated = true)
            }
            output.write(buffer, 0, count)
            total += count
        }
        return ReadResult(output.toByteArray(), truncated = false)
    }

    private data class ReadResult(val bytes: ByteArray, val truncated: Boolean)
}
