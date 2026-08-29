package de.rdoe.weeklydjshows.resolver.newpipe

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object NewPipeDownloader : Downloader() {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Request cancelled")
        val method = request.httpMethod()
        val url = request.url()
        val bytes = request.dataToSend()
        val needsBody = method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)
        val body = bytes?.toRequestBody(null) ?: if (needsBody) ByteArray(0).toRequestBody(null) else null
        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("Rate limited", url)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    fun playbackHeaders(url: String): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        url.toHttpUrlOrNull()?.let { httpUrl ->
            cookieJar.loadForRequest(httpUrl).takeIf { it.isNotEmpty() }?.let { cookies ->
                put("Cookie", cookies.joinToString("; ") { it.name + "=" + it.value })
            }
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookiesToSave: List<Cookie>) {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            cookiesToSave.forEach { incoming ->
                cookies.removeAll {
                    it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                }
                cookies += incoming
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }
    }
}
