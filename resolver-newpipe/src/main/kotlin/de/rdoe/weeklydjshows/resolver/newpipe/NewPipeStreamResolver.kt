package de.rdoe.weeklydjshows.resolver.newpipe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.rdoe.weeklydjshows.model.*
import de.rdoe.weeklydjshows.resolver.StreamResolver
import de.rdoe.weeklydjshows.resolver.newpipe.potoken.BadWebViewException
import de.rdoe.weeklydjshows.resolver.newpipe.potoken.PoTokenException
import de.rdoe.weeklydjshows.resolver.newpipe.potoken.PoTokenProviderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.URI
import kotlin.math.abs

class NewPipeStreamResolver(context: Context) : StreamResolver {
    private val appContext = context.applicationContext
    private val poTokenProvider = PoTokenProviderImpl(appContext)

    init {
        synchronized(InitLock) {
            if (!initialized) {
                NewPipe.init(NewPipeDownloader)
                initialized = true
            }
        }
    }

    override fun supports(request: PlaybackRequest): Boolean {
        if (request.enclosureUrl != null || request.localFilePath != null) return false
        if (request.sourceType in SUPPORTED_TYPES) return true
        val url = request.originalPageUrl ?: return false
        return runCatching { NewPipe.getServiceByUrl(url); true }.getOrDefault(false)
    }

    override suspend fun resolve(request: PlaybackRequest, forceRefresh: Boolean): ResolveResult =
        withContext(Dispatchers.IO) {
            val url = request.originalPageUrl
                ?: return@withContext failure(ResolverErrorType.UNSUPPORTED_URL, "Kein Plattformlink vorhanden.", null)
            try {
                val service = NewPipe.getServiceByUrl(url)
                val extractor = service.getStreamExtractor(url)
                if (extractor is YoutubeStreamExtractor) {
                    // v0.26.4 exposes the provider setter as a static method on the extractor.
                    YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)
                }
                val info = StreamInfo.getInfo(extractor)
                chooseSource(url, request, info)
            } catch (error: Throwable) {
                mapFailure(url, error)
            }
        }

    private fun chooseSource(url: String, request: PlaybackRequest, info: StreamInfo): ResolveResult {
        val quality = request.preferredQuality ?: preferredQuality()
        val candidates = info.audioStreams
            .filter { it.content.isNotBlank() && it.deliveryMethod != DeliveryMethod.TORRENT }
        val progressive = candidates.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val audio = when {
            request.requireProgressiveHttp -> progressive.maxByOrNull { scoreAudio(it, quality) }
            // SoundCloud exposes equivalent progressive and HLS transcodings. The progressive
            // endpoint is the same path used by the reliable download worker and avoids devices
            // that fail on SoundCloud's short-lived HLS indirection. Keep HLS as a fallback for
            // tracks that genuinely have no progressive transcoding.
            request.sourceType == EpisodeSourceType.SOUNDCLOUD ->
                progressive.maxByOrNull { scoreAudio(it, quality) }
                    ?: candidates.maxByOrNull { scoreAudio(it, quality) }
            else -> candidates.maxByOrNull { scoreAudio(it, quality) }
        }

        val now = System.currentTimeMillis()
        if (audio != null) {
            val contentUrl = audio.content
                ?: return failure(ResolverErrorType.NO_PLAYABLE_AUDIO, "Der Audiostream enthält keine abrufbare Adresse.", url)
            val playbackUrl = if (audio.deliveryMethod == DeliveryMethod.DASH) {
                audio.manifestUrl?.takeIf { it.isNotBlank() } ?: contentUrl
            } else contentUrl
            val delivery = when {
                playbackUrl.contains(".m3u8", true) -> DeliveryType.HLS
                audio.deliveryMethod == DeliveryMethod.DASH || playbackUrl.contains(".mpd", true) -> DeliveryType.DASH
                else -> DeliveryType.PROGRESSIVE_HTTP
            }
            return ResolveResult.Success(
                ResolvedMediaSource(
                    originalUrl = url,
                    playbackUrl = playbackUrl,
                    deliveryType = delivery,
                    mimeType = audio.format?.mimeType,
                    requestHeaders = NewPipeDownloader.playbackHeaders(playbackUrl),
                    title = info.name.ifBlank { request.title },
                    artistOrChannel = info.uploaderName.ifBlank { request.showTitle },
                    artworkUrl = bestArtwork(info) ?: request.artworkUrl,
                    durationMs = info.duration.takeIf { it > 0 }?.times(1000),
                    bitrate = audio.averageBitrate.takeIf { it > 0 },
                    codec = audio.codec,
                    resolvedAtEpochMs = now,
                    validUntilEpochMs = expiryFrom(playbackUrl),
                    resolverId = "newpipe-extractor-v0.26.4",
                ),
            )
        }

        if (request.requireProgressiveHttp) {
            return failure(
                ResolverErrorType.NO_PLAYABLE_AUDIO,
                "Die Plattform liefert für diesen Download keine direkte Audiodatei.",
                url,
            )
        }

        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { hls ->
            return ResolveResult.Success(
                ResolvedMediaSource(
                    originalUrl = url,
                    playbackUrl = hls,
                    deliveryType = DeliveryType.HLS,
                    mimeType = "application/x-mpegURL",
                    requestHeaders = NewPipeDownloader.playbackHeaders(hls),
                    title = info.name,
                    artistOrChannel = info.uploaderName,
                    artworkUrl = bestArtwork(info) ?: request.artworkUrl,
                    durationMs = info.duration.takeIf { it > 0 }?.times(1000),
                    resolvedAtEpochMs = now,
                    validUntilEpochMs = expiryFrom(hls),
                    resolverId = "newpipe-extractor-v0.26.4-hls",
                ),
            )
        }

        info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let { dash ->
            return ResolveResult.Success(
                ResolvedMediaSource(
                    originalUrl = url,
                    playbackUrl = dash,
                    deliveryType = DeliveryType.DASH,
                    mimeType = "application/dash+xml",
                    requestHeaders = NewPipeDownloader.playbackHeaders(dash),
                    title = info.name,
                    artistOrChannel = info.uploaderName,
                    artworkUrl = bestArtwork(info) ?: request.artworkUrl,
                    durationMs = info.duration.takeIf { it > 0 }?.times(1000),
                    resolvedAtEpochMs = now,
                    validUntilEpochMs = expiryFrom(dash),
                    resolverId = "newpipe-extractor-v0.26.4-dash",
                ),
            )
        }
        return failure(ResolverErrorType.NO_PLAYABLE_AUDIO, "Die Plattform liefert keinen abspielbaren Audiostream.", url)
    }

    private fun scoreAudio(stream: AudioStream, qualityPreference: StreamingQuality): Int {
        val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: 128
        val efficientCodec = when {
            stream.codec?.contains("opus", true) == true -> 35
            stream.format?.mimeType?.contains("mp4", true) == true -> 25
            else -> 0
        }
        if (qualityPreference == StreamingQuality.MAXIMUM) {
            // At "Maximum" bitrate/codec quality wins. Progressive HTTP is only a small
            // tie-breaker so a lower-quality direct stream cannot beat a better DASH stream.
            val progressiveTieBreaker = if (stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 5 else 0
            return bitrate.coerceAtMost(1_000) * 10 + efficientCodec + progressiveTieBreaker
        }

        val targetKbps = qualityPreference.targetKbps ?: 256
        val progressive = if (stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) 1_000 else 500
        // Stay close to the requested data rate; overshooting costs extra on mobile data.
        val distance = abs(bitrate.coerceAtMost(384) - targetKbps)
        val overTarget = (bitrate - targetKbps).coerceAtLeast(0)
        val quality = 500 - distance * 2 - overTarget
        return progressive + quality + efficientCodec
    }

    private fun preferredQuality(): StreamingQuality {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        val mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val prefs = appContext.getSharedPreferences(StreamingPreferenceKeys.FILE, Context.MODE_PRIVATE)
        val key = if (mobile) StreamingPreferenceKeys.MOBILE_QUALITY else StreamingPreferenceKeys.WIFI_QUALITY
        val fallback = if (mobile) StreamingPreferenceKeys.DEFAULT_MOBILE else StreamingPreferenceKeys.DEFAULT_WIFI
        return runCatching {
            StreamingQuality.valueOf(prefs.getString(key, fallback) ?: fallback)
        }.getOrDefault(if (mobile) StreamingQuality.MEDIUM else StreamingQuality.HIGH)
    }

    private fun bestArtwork(info: StreamInfo): String? = info.thumbnails
        .maxByOrNull { it.width.toLong().coerceAtLeast(0) * it.height.toLong().coerceAtLeast(0) }
        ?.url

    private fun expiryFrom(url: String): Long? = runCatching {
        URI(url).rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=').equals("expire", true) }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?.times(1000)
    }.getOrNull()

    private fun mapFailure(url: String, raw: Throwable): ResolveResult {
        val error = generateSequence(raw) { it.cause }.last()
        val type = when (error) {
            is BadWebViewException, is PoTokenException -> ResolverErrorType.WEBVIEW_REQUIRED
            is AgeRestrictedContentException -> ResolverErrorType.AGE_RESTRICTED
            is GeographicRestrictionException, is UnsupportedContentInCountryException -> ResolverErrorType.REGION_RESTRICTED
            is PrivateContentException -> ResolverErrorType.PRIVATE_CONTENT
            is ContentNotAvailableException -> ResolverErrorType.REMOVED_CONTENT
            is SignInConfirmNotBotException, is AccountTerminatedException -> ResolverErrorType.LOGIN_REQUIRED
            is SoundCloudGoPlusContentException, is PaidContentException, is YoutubeMusicPremiumContentException -> ResolverErrorType.DRM_PROTECTED
            is ReCaptchaException -> ResolverErrorType.RATE_LIMITED
            is IOException -> ResolverErrorType.NETWORK_ERROR
            is ExtractionException -> ResolverErrorType.EXTRACTION_FAILED
            else -> ResolverErrorType.UNKNOWN
        }
        val message = when (type) {
            ResolverErrorType.WEBVIEW_REQUIRED -> "YouTube-Integritätsprüfung konnte mit dem System-WebView nicht abgeschlossen werden."
            ResolverErrorType.AGE_RESTRICTED -> "Der Inhalt ist altersbeschränkt."
            ResolverErrorType.REGION_RESTRICTED -> "Der Inhalt ist in dieser Region nicht verfügbar."
            ResolverErrorType.PRIVATE_CONTENT -> "Der Inhalt ist privat."
            ResolverErrorType.REMOVED_CONTENT -> "Der Inhalt wurde entfernt oder ist nicht verfügbar."
            ResolverErrorType.LOGIN_REQUIRED -> "Für diesen Inhalt ist eine Anmeldung oder zusätzliche Bestätigung nötig."
            ResolverErrorType.DRM_PROTECTED -> "Der Inhalt ist kostenpflichtig oder geschützt."
            ResolverErrorType.RATE_LIMITED -> "Die Plattform begrenzt Anfragen vorübergehend."
            ResolverErrorType.NETWORK_ERROR -> "Netzwerkfehler beim Auflösen der Plattformquelle."
            ResolverErrorType.EXTRACTION_FAILED -> "Die Plattformquelle konnte nicht ausgelesen werden."
            else -> "Die Plattformquelle konnte nicht wiedergegeben werden."
        }
        return ResolveResult.Failure(
            ResolverError(type, message, url, error.javaClass.simpleName, type in RECOVERABLE),
        )
    }

    private fun failure(type: ResolverErrorType, message: String, url: String?) =
        ResolveResult.Failure(ResolverError(type, message, url))

    companion object {
        private object InitLock
        @Volatile private var initialized = false
        private val SUPPORTED_TYPES = setOf(
            EpisodeSourceType.YOUTUBE,
            EpisodeSourceType.SOUNDCLOUD,
            EpisodeSourceType.BANDCAMP,
            EpisodeSourceType.PEERTUBE,
        )
        private val RECOVERABLE = setOf(
            ResolverErrorType.NETWORK_ERROR,
            ResolverErrorType.TEMPORARILY_UNAVAILABLE,
            ResolverErrorType.RATE_LIMITED,
            ResolverErrorType.EXTRACTION_FAILED,
        )
    }
}
