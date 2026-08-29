package de.rdoe.weeklydjshows.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.concurrent.ConcurrentHashMap

object PlaybackHeaders {
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, values: Map<String, String>) {
        if (values.isNotEmpty()) headers[url] = values
    }

    fun forUrl(url: String): Map<String, String> = headers[url].orEmpty()

    fun clear(url: String) { headers.remove(url) }
}

class HeaderInjectingDataSource private constructor(
    private val upstream: DataSource,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val extra = PlaybackHeaders.forUrl(dataSpec.uri.toString())
        if (extra.isEmpty()) return upstream.open(dataSpec)
        val merged = dataSpec.httpRequestHeaders + extra
        return upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(merged).build())
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = HeaderInjectingDataSource(upstream.createDataSource())
    }
}
