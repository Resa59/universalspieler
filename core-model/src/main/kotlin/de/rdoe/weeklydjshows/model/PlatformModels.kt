package de.rdoe.weeklydjshows.model

/** A platform hint only; playback still performs a real availability check. */
enum class PlatformAvailability { UNKNOWN, AVAILABLE, SCHEDULED }

data class PlatformEpisode(
    val stableId: String,
    val url: String,
    val title: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    /** Real publication/addition time supplied by the platform, never the refresh time. */
    val publishedAtEpochMs: Long? = null,
    val publishedText: String = "",
    val availability: PlatformAvailability = PlatformAvailability.UNKNOWN,
    val scheduledForEpochMs: Long? = null,
    val sourceType: EpisodeSourceType,
)

data class PlatformListing(
    val title: String?,
    val artworkUrl: String?,
    val publisher: String? = null,
    val description: String = "",
    val episodes: List<PlatformEpisode>,
)
