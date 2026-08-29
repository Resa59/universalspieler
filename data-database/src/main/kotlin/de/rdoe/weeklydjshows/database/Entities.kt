package de.rdoe.weeklydjshows.database

import androidx.room.*
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.ResolverErrorType
import de.rdoe.weeklydjshows.model.ShowSourceType

enum class DownloadStatus { NONE, QUEUED, DOWNLOADING, COMPLETE, FAILED }

/** Controls how episodes from one show participate in the global "Neu" timeline. */
enum class LatestMode { ALL, LATEST_ONLY, NONE }

/** Bundled shows can follow catalogue updates; user shows are never removed by one. */
enum class ShowOrigin { BUNDLED, USER }

/** User-facing separation used by the show grid, refresh policy and the "Neu" timeline. */
enum class PodcastCategory { WORD, MUSIC }

/** Playback eligibility for premieres and other not-yet-released platform entries. */
enum class EpisodeAvailability { UNKNOWN, AVAILABLE, SCHEDULED }

@Entity(
    tableName = "shows",
    indices = [Index("subscribed"), Index("sortOrder")],
)
data class ShowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val feedUrl: String? = null,
    val platformUrl: String? = null,
    val sourceType: ShowSourceType = ShowSourceType.RSS,
    val description: String = "",
    val artworkUrl: String? = null,
    val subscribed: Boolean = true,
    val origin: ShowOrigin = ShowOrigin.USER,
    /** Version/rank of the bundled catalogue this row came from, if any. */
    val standardCatalogVersion: Int? = null,
    /** A moved show remains anchored between the same neighbours across catalogue updates. */
    val orderAnchorBeforeId: String? = null,
    val orderAnchorAfterId: String? = null,
    /**
     * Compatibility mirror for 1.2.7 exports/database rows. New code uses [latestMode].
     * It stays in the schema so the 3 -> 4 migration is additive and update-safe.
     */
    val hideFromLatest: Boolean = false,
    val latestMode: LatestMode = LatestMode.ALL,
    val category: PodcastCategory = PodcastCategory.MUSIC,
    /** True only after the user explicitly moved this show to the other category. */
    val categoryUserAssigned: Boolean = false,
    /** RSS only: after a successful refresh remove unprotected episodes absent from that feed. */
    val autoPruneMissingEpisodes: Boolean = false,
    /** Current user-visible order. It may deliberately differ from [standardSortOrder]. */
    val sortOrder: Int = 0,
    /** Position in the catalogue version shipped by the app; null means user-added. */
    val standardSortOrder: Int? = null,
    /** Marks a deliberate drag/top/bottom action so catalogue updates preserve its anchors. */
    val orderCustomized: Boolean = false,
    val legacyModuleId: Long? = null,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
    val lastRefreshAtEpochMs: Long? = null,
    val lastRefreshError: String? = null,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = ShowEntity::class,
            parentColumns = ["id"],
            childColumns = ["showId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("showId"),
        Index("publishedAtEpochMs"),
        Index("liked"),
        Index("downloadStatus"),
        Index("lastPlayedAtEpochMs"),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val showId: String,
    val title: String,
    val description: String = "",
    val pageUrl: String? = null,
    val enclosureUrl: String? = null,
    val sourceType: EpisodeSourceType = EpisodeSourceType.UNKNOWN_WEBPAGE,
    val publishedAtEpochMs: Long? = null,
    val publishedText: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val liked: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NONE,
    val localFilePath: String? = null,
    /** Artwork stored beside an explicit episode download. This is not temporary cache data. */
    val localArtworkPath: String? = null,
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long? = null,
    val positionMs: Long = 0,
    val playbackDurationMs: Long? = null,
    val lastPlayedAtEpochMs: Long? = null,
    /** Set once an episode reaches the end threshold. Kept even when it is played again. */
    val completedAtEpochMs: Long? = null,
    val discoveredAtEpochMs: Long = System.currentTimeMillis(),
    val availability: EpisodeAvailability = EpisodeAvailability.UNKNOWN,
    val scheduledForEpochMs: Long? = null,
    val availabilityCheckedAtEpochMs: Long? = null,
    val resolverErrorType: ResolverErrorType? = null,
    val resolverErrorMessage: String? = null,
)

data class EpisodeWithShow(
    @Embedded val episode: EpisodeEntity,
    @Relation(parentColumn = "showId", entityColumn = "id")
    val show: ShowEntity,
)

@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["episodeId"], unique = true), Index("position")],
)
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0,
    val episodeId: String,
    val position: Int,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
    /**
     * Set only when a scheduled entry really reaches the head of the durable queue and playback
     * is attempted. Listing refreshes and manual Play taps deliberately do not touch this value.
     */
    val availabilityAttemptedAtEpochMs: Long? = null,
)

data class QueueEntryWithEpisode(
    @Embedded val queue: QueueEntryEntity,
    @Relation(
        entity = EpisodeEntity::class,
        parentColumn = "episodeId",
        entityColumn = "id",
    )
    val episode: EpisodeWithShow,
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episodeId", "playedAtEpochMs"], unique = true),
        Index("episodeId"),
        Index("playedAtEpochMs"),
    ],
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val historyId: Long = 0,
    val episodeId: String,
    val playedAtEpochMs: Long = System.currentTimeMillis(),
)

data class PlaybackHistoryWithEpisode(
    @Embedded val history: PlaybackHistoryEntity,
    @Relation(
        entity = EpisodeEntity::class,
        parentColumn = "episodeId",
        entityColumn = "id",
    )
    val episode: EpisodeWithShow,
)

class DatabaseConverters {
    @TypeConverter fun sourceToString(value: EpisodeSourceType?): String? = value?.name
    @TypeConverter fun stringToSource(value: String?): EpisodeSourceType? = value?.let(EpisodeSourceType::valueOf)
    @TypeConverter fun showSourceToString(value: ShowSourceType?): String? = value?.name
    @TypeConverter fun stringToShowSource(value: String?): ShowSourceType? = value?.let(ShowSourceType::valueOf)
    @TypeConverter fun downloadToString(value: DownloadStatus?): String? = value?.name
    @TypeConverter fun stringToDownload(value: String?): DownloadStatus? = value?.let(DownloadStatus::valueOf)
    @TypeConverter fun latestModeToString(value: LatestMode?): String? = value?.name
    @TypeConverter fun stringToLatestMode(value: String?): LatestMode? = value?.let(LatestMode::valueOf)
    @TypeConverter fun originToString(value: ShowOrigin?): String? = value?.name
    @TypeConverter fun stringToOrigin(value: String?): ShowOrigin? = value?.let(ShowOrigin::valueOf)
    @TypeConverter fun categoryToString(value: PodcastCategory?): String? = value?.name
    @TypeConverter fun stringToCategory(value: String?): PodcastCategory? = value?.let(PodcastCategory::valueOf)
    @TypeConverter fun availabilityToString(value: EpisodeAvailability?): String? = value?.name
    @TypeConverter fun stringToAvailability(value: String?): EpisodeAvailability? = value?.let(EpisodeAvailability::valueOf)
    @TypeConverter fun errorToString(value: ResolverErrorType?): String? = value?.name
    @TypeConverter fun stringToError(value: String?): ResolverErrorType? = value?.let(ResolverErrorType::valueOf)
}
