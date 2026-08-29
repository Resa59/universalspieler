package de.rdoe.weeklydjshows.resolver.direct

import de.rdoe.weeklydjshows.model.*
import de.rdoe.weeklydjshows.resolver.StreamResolver
import java.io.File

class DirectStreamResolver : StreamResolver {
    override fun supports(request: PlaybackRequest): Boolean =
        !request.localFilePath.isNullOrBlank() || !request.enclosureUrl.isNullOrBlank()

    override suspend fun resolve(request: PlaybackRequest, forceRefresh: Boolean): ResolveResult {
        val now = System.currentTimeMillis()
        request.localFilePath?.takeIf { it.isNotBlank() && File(it).isFile }?.let { path ->
            val file = File(path)
            return ResolveResult.Success(
                ResolvedMediaSource(
                    originalUrl = request.originalPageUrl ?: request.enclosureUrl ?: file.toURI().toString(),
                    playbackUrl = file.toURI().toString(),
                    deliveryType = DeliveryType.LOCAL_FILE,
                    mimeType = mimeFor(path),
                    title = request.title,
                    artistOrChannel = request.showTitle,
                    artworkUrl = request.artworkUrl,
                    resolvedAtEpochMs = now,
                    resolverId = "direct-local-v1",
                ),
            )
        }

        val url = request.enclosureUrl
            ?: return ResolveResult.Failure(
                ResolverError(ResolverErrorType.NO_PLAYABLE_AUDIO, "Keine direkte Audiodatei vorhanden."),
            )
        val lower = url.substringBefore('?').lowercase()
        val delivery = when {
            lower.endsWith(".m3u8") -> DeliveryType.HLS
            lower.endsWith(".mpd") -> DeliveryType.DASH
            else -> DeliveryType.PROGRESSIVE_HTTP
        }
        return ResolveResult.Success(
            ResolvedMediaSource(
                originalUrl = request.originalPageUrl ?: url,
                playbackUrl = url,
                deliveryType = delivery,
                mimeType = mimeFor(lower),
                title = request.title,
                artistOrChannel = request.showTitle,
                artworkUrl = request.artworkUrl,
                resolvedAtEpochMs = now,
                resolverId = "direct-http-v1",
            ),
        )
    }

    private fun mimeFor(value: String): String? = when (value.substringBefore('?').lowercase()) {
        in listOf(".m3u8") -> "application/x-mpegURL"
        else -> when {
            value.contains(".mp3") -> "audio/mpeg"
            value.contains(".m4a") || value.contains(".mp4") -> "audio/mp4"
            value.contains(".aac") -> "audio/aac"
            value.contains(".ogg") || value.contains(".oga") -> "audio/ogg"
            value.contains(".opus") -> "audio/opus"
            value.contains(".m3u8") -> "application/x-mpegURL"
            value.contains(".mpd") -> "application/dash+xml"
            else -> null
        }
    }
}
