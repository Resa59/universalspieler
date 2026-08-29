package de.rdoe.weeklydjshows.discovery.model

/** Identifies a directory or platform queried by the discovery module. */
enum class ProviderId {
    APPLE_PODCASTS,
    PODCAST_INDEX,
    GPODDER,
    FEEDLY,
    MIXCLOUD,
    YOUTUBE,
    SPOTIFY,
    SOUNDCLOUD,
    WEBSITE
}

enum class SecretName {
    FEEDLY_TOKEN,
    PODCAST_INDEX_KEY,
    PODCAST_INDEX_SECRET,
    YOUTUBE_API_KEY,
    SPOTIFY_BEARER_TOKEN,
    SOUNDCLOUD_ACCESS_TOKEN
}

/** The host app supplies secrets at call time. They are never included in result/export models. */
fun interface SecretProvider {
    fun get(name: SecretName): String?
}

class MapSecretProvider(private val values: Map<SecretName, String>) : SecretProvider {
    override fun get(name: SecretName): String? = values[name]?.takeIf { it.isNotBlank() }
}

object EmptySecretProvider : SecretProvider {
    override fun get(name: SecretName): String? = null
}

enum class MusicMode {
    /** Keep every result. */
    ALL,
    /** Keep declared music and probable DJ/music shows. */
    DJ_AND_MUSIC,
    /** Keep only results explicitly declared as music by a source/feed. */
    DECLARED_MUSIC_ONLY
}

enum class ResultGroup {
    DECLARED_MUSIC,
    LIKELY_DJ_OR_MUSIC_SHOW,
    OTHER
}

enum class BrowseMode {
    TRENDING,
    POPULAR,
    NEW,
    RECENTLY_UPDATED,
    GENRE,
    RANDOM
}

enum class TargetKind {
    RSS_AUDIO,
    RSS_VIDEO,
    ATOM_FEED,
    WEBSITE,
    APPLE_PODCAST,
    PODCAST_INDEX_PAGE,
    YOUTUBE_CHANNEL,
    YOUTUBE_PLAYLIST,
    YOUTUBE_VIDEO,
    SPOTIFY_SHOW,
    SPOTIFY_PLAYLIST,
    SPOTIFY_ARTIST,
    SPOTIFY_EPISODE,
    MIXCLOUD_PROFILE,
    MIXCLOUD_SHOW,
    SOUNDCLOUD_PROFILE,
    SOUNDCLOUD_PLAYLIST,
    SOUNDCLOUD_TRACK,
    EXTERNAL_PLATFORM
}

enum class IntegrationRequirement {
    /** A normal audio RSS feed can be subscribed to and played by the app. */
    DIRECT_RSS_AUDIO,
    /** A feed can be subscribed to, but entries require a platform-aware player (for example YouTube). */
    FEED_AND_PLATFORM_PLAYER,
    /** No feed is available; update listing and playback require a provider/platform adapter. */
    PLATFORM_ADAPTER_REQUIRED,
    /** The URL needs further resolution before the app can decide how to integrate it. */
    RESOLUTION_REQUIRED,
    /** Useful discovery result, but no automatic subscription contract is currently available. */
    EXTERNAL_ONLY
}

data class IntegrationTarget(
    val kind: TargetKind,
    val url: String,
    val stableId: String? = null,
    val feedUrl: String? = null,
    val requirement: IntegrationRequirement,
    val title: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class SearchRequest(
    val query: String,
    val countries: List<String> = listOf("DE", "US"),
    val language: String? = null,
    val maxResultsPerProvider: Int = 30,
    val musicMode: MusicMode = MusicMode.ALL,
    val includePlatformResults: Boolean = true,
    val verifyTopRssResults: Int = 12,
    val enabledProviders: Set<ProviderId>? = null
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
        require(maxResultsPerProvider in 1..200)
        require(verifyTopRssResults in 0..100)
    }
}

data class BrowseRequest(
    val mode: BrowseMode,
    val genre: String? = null,
    val language: String? = null,
    val country: String = "DE",
    val limit: Int = 50,
    val musicMode: MusicMode = MusicMode.DJ_AND_MUSIC,
    val includePlatformResults: Boolean = true,
    val verifyTopRssResults: Int = 12,
    val enabledProviders: Set<ProviderId>? = null
) {
    init {
        require(limit in 1..200)
        if (mode == BrowseMode.GENRE) require(!genre.isNullOrBlank()) { "GENRE browse requires genre" }
    }
}

enum class ProviderState {
    SEARCHING,
    SUCCESS,
    NO_RESULTS,
    CREDENTIALS_MISSING,
    RATE_LIMITED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    FAILED,
    DISABLED
}

data class ProviderStatus(
    val provider: ProviderId,
    val state: ProviderState,
    val resultCount: Int = 0,
    val message: String? = null,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val durationMillis: Long? = null
)

data class SourceHit(
    val provider: ProviderId,
    val providerItemId: String? = null,
    val providerRank: Int? = null,
    val providerPopularity: Double? = null,
    val title: String,
    val publisher: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    val websiteUrl: String? = null,
    val artworkUrl: String? = null,
    val categories: Set<String> = emptySet(),
    val language: String? = null,
    val lastPublishedEpochMillis: Long? = null,
    val declaredMusic: Boolean = false,
    val declaredMusicReason: String? = null,
    val stableIds: Map<String, String> = emptyMap(),
    val targets: List<IntegrationTarget> = emptyList(),
    val rawMetadata: Map<String, String> = emptyMap()
)

data class ProviderResult(
    val provider: ProviderId,
    val hits: List<SourceHit>,
    val status: ProviderStatus
)

data class MusicEvidence(
    val reason: String,
    val weight: Double,
    val source: String
)

data class MusicClassification(
    val group: ResultGroup,
    val probability: Double,
    val declaredBy: Set<ProviderId>,
    val genres: Set<String>,
    val evidence: List<MusicEvidence>
)

enum class FeedKind {
    RSS,
    ATOM,
    RDF,
    UNKNOWN
}

enum class FeedStatus {
    VALID_AUDIO_FEED,
    VALID_VIDEO_FEED,
    VALID_FEED_WITHOUT_MEDIA,
    INVALID_XML,
    NOT_A_FEED,
    UNREACHABLE,
    TOO_LARGE,
    UNSUPPORTED_URL,
    UNKNOWN
}

enum class ActivityStatus {
    ACTIVE_RECENT,
    ACTIVE_REGULAR,
    ACTIVE_IRREGULAR,
    INACTIVE_RECENTLY,
    INACTIVE_LONG,
    LIKELY_DISCONTINUED,
    UNKNOWN
}

enum class RegularityStatus {
    DAILY,
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    MULTIMONTHLY,
    IRREGULAR,
    ONE_OFF,
    INSUFFICIENT_DATA
}

enum class VerificationLevel {
    HEADERS_ONLY,
    BASIC_FEED,
    RECENT_EPISODES,
    FULL_IMPORT_CHECK
}

data class FeedVerification(
    val requestedUrl: String,
    val finalUrl: String? = null,
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val feedKind: FeedKind = FeedKind.UNKNOWN,
    val status: FeedStatus = FeedStatus.UNKNOWN,
    val title: String? = null,
    val description: String? = null,
    val websiteUrl: String? = null,
    val imageUrl: String? = null,
    val episodeImageCount: Int = 0,
    val episodeCount: Int = 0,
    val audioEnclosureCount: Int = 0,
    val videoEnclosureCount: Int = 0,
    val lastPublishedEpochMillis: Long? = null,
    val recentEpisodeCount30Days: Int = 0,
    val recentEpisodeCount90Days: Int = 0,
    val activityStatus: ActivityStatus = ActivityStatus.UNKNOWN,
    val regularityStatus: RegularityStatus = RegularityStatus.INSUFFICIENT_DATA,
    val medianIntervalDays: Double? = null,
    val podcastGuid: String? = null,
    val podcastMedium: String? = null,
    val categories: Set<String> = emptySet(),
    val episodeTitles: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null
)

data class DiscoveryResult(
    val internalId: String,
    val title: String,
    val publisher: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val websiteUrl: String? = null,
    val language: String? = null,
    val categories: Set<String> = emptySet(),
    val sources: Set<ProviderId>,
    val sourceHits: List<SourceHit>,
    val targets: List<IntegrationTarget>,
    val preferredTarget: IntegrationTarget?,
    val music: MusicClassification,
    val feedVerification: FeedVerification? = null,
    val relevanceScore: Double,
    val mergeWarnings: List<String> = emptyList()
)

data class DiscoveryResponse(
    val results: List<DiscoveryResult>,
    val providerStatuses: List<ProviderStatus>,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long
)

data class ResolutionResult(
    val input: String,
    val targets: List<IntegrationTarget>,
    val feedVerifications: List<FeedVerification> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null
)

interface DiscoveryListener {
    fun onProviderStatus(status: ProviderStatus) {}
    fun onPartialResults(results: List<DiscoveryResult>) {}
    fun onVerificationProgress(completed: Int, total: Int) {}
}

object NoOpDiscoveryListener : DiscoveryListener
