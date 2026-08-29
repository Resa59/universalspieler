package de.rdoe.weeklydjshows.model

enum class EpisodeSourceType {
    DIRECT_AUDIO,
    YOUTUBE,
    SOUNDCLOUD,
    MIXCLOUD,
    SPOTIFY,
    BANDCAMP,
    PEERTUBE,
    UNKNOWN_WEBPAGE,
}

enum class ShowSourceType {
    RSS,
    YOUTUBE_CHANNEL,
    YOUTUBE_PLAYLIST,
    SOUNDCLOUD,
    MIXCLOUD,
    SPOTIFY_PLAYLIST,
    BANDCAMP,
    PEERTUBE,
    PLATFORM_LINK,
}

enum class StreamingQuality(val targetKbps: Int?) {
    DATA_SAVER(64),
    MEDIUM(128),
    HIGH(160),
    VERY_HIGH(256),
    /** Select the best audio stream the source exposes instead of aiming at a bitrate. */
    MAXIMUM(null),
}

/** Shared preference contract intentionally lives outside the Android/UI modules. */
object StreamingPreferenceKeys {
    const val FILE = "weekly_dj_settings"
    const val WIFI_QUALITY = "wifi_stream_quality"
    const val MOBILE_QUALITY = "mobile_stream_quality"
    const val DOWNLOAD_QUALITY = "download_quality"
    const val DEFAULT_WIFI = "HIGH"
    const val DEFAULT_MOBILE = "MEDIUM"
    const val DEFAULT_DOWNLOAD = "MAXIMUM"
}

data class PlaybackRequest(
    val episodeId: String,
    val originalPageUrl: String?,
    val enclosureUrl: String?,
    val sourceType: EpisodeSourceType,
    val title: String,
    val showTitle: String?,
    val artworkUrl: String?,
    val storedPositionMs: Long = 0,
    val localFilePath: String? = null,
    /** Explicit for downloads and normal playback so resolver caches remain quality-aware. */
    val preferredQuality: StreamingQuality? = null,
    /** Downloads need a single directly retrievable file instead of a DASH/HLS manifest. */
    val requireProgressiveHttp: Boolean = false,
)

enum class DeliveryType {
    PROGRESSIVE_HTTP,
    HLS,
    DASH,
    LOCAL_FILE,
}

data class ResolvedMediaSource(
    val originalUrl: String,
    val playbackUrl: String,
    val deliveryType: DeliveryType,
    val mimeType: String?,
    val requestHeaders: Map<String, String> = emptyMap(),
    val title: String? = null,
    val artistOrChannel: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val resolvedAtEpochMs: Long,
    val validUntilEpochMs: Long? = null,
    val resolverId: String,
)

enum class ResolverErrorType {
    UNSUPPORTED_URL,
    NETWORK_ERROR,
    TEMPORARILY_UNAVAILABLE,
    EXTRACTION_FAILED,
    NO_PLAYABLE_AUDIO,
    LOGIN_REQUIRED,
    AGE_RESTRICTED,
    REGION_RESTRICTED,
    PRIVATE_CONTENT,
    REMOVED_CONTENT,
    DRM_PROTECTED,
    WEBVIEW_REQUIRED,
    RATE_LIMITED,
    INVALID_RESPONSE,
    NOT_YET_AVAILABLE,
    UNKNOWN,
}

data class ResolverError(
    val type: ResolverErrorType,
    val message: String,
    val originalUrl: String? = null,
    val causeClass: String? = null,
    val recoverable: Boolean = false,
)

sealed interface ResolveResult {
    data class Success(val source: ResolvedMediaSource) : ResolveResult
    data class Failure(val error: ResolverError) : ResolveResult
}
