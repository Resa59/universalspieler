package de.rdoe.weeklydjshows.database

import androidx.room.*
import de.rdoe.weeklydjshows.model.ResolverErrorType
import kotlinx.coroutines.flow.Flow

@Dao
interface ShowDao {
    @Query("SELECT * FROM shows WHERE subscribed = 1 ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeSubscribed(): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE subscribed = 1 ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getSubscribed(): List<ShowEntity>

    @Query("SELECT * FROM shows ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getAll(): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE subscribed = 0 AND (origin = 'BUNDLED' OR legacyModuleId IS NOT NULL) ORDER BY sortOrder, title COLLATE NOCASE")
    fun observeHiddenLegacy(): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ShowEntity?

    @Query("SELECT * FROM shows WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<ShowEntity?>

    @Query("SELECT COUNT(*) FROM shows")
    suspend fun count(): Int

    @Query("SELECT * FROM shows WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeed(feedUrl: String): ShowEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM shows")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM shows WHERE subscribed = 1")
    suspend fun minSubscribedSortOrder(): Int

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM shows WHERE subscribed = 1 AND category = :category")
    suspend fun minSubscribedSortOrder(category: PodcastCategory): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM shows WHERE subscribed = 1 AND category = :category")
    suspend fun maxSubscribedSortOrder(category: PodcastCategory): Int

    @Query("SELECT * FROM shows WHERE subscribed = 1 AND category = :category ORDER BY sortOrder, title COLLATE NOCASE")
    suspend fun getSubscribed(category: PodcastCategory): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE subscribed = 1 AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE")
    fun search(query: String): Flow<List<ShowEntity>>

    @Upsert
    suspend fun upsert(show: ShowEntity)

    @Upsert
    suspend fun upsertAll(shows: List<ShowEntity>)

    @Query("UPDATE shows SET subscribed = :subscribed WHERE id = :id")
    suspend fun setSubscribed(id: String, subscribed: Boolean)

    @Query("UPDATE shows SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE shows SET latestMode = :mode, hideFromLatest = CASE WHEN :mode = 'NONE' THEN 1 ELSE 0 END WHERE id = :id")
    suspend fun setLatestMode(id: String, mode: LatestMode)

    @Query("UPDATE shows SET autoPruneMissingEpisodes = :enabled WHERE id = :id")
    suspend fun setAutoPruneMissingEpisodes(id: String, enabled: Boolean)

    @Query("UPDATE shows SET category = :category, categoryUserAssigned = :userAssigned WHERE id = :id")
    suspend fun setCategory(id: String, category: PodcastCategory, userAssigned: Boolean = true)

    @Query("UPDATE shows SET orderCustomized = :customized WHERE id = :id")
    suspend fun setOrderCustomized(id: String, customized: Boolean)

    @Query("UPDATE shows SET sortOrder = :sortOrder, orderCustomized = :customized, orderAnchorBeforeId = :beforeId, orderAnchorAfterId = :afterId WHERE id = :id")
    suspend fun setOrderPlacement(
        id: String,
        sortOrder: Int,
        customized: Boolean,
        beforeId: String?,
        afterId: String?,
    )

    @Query("UPDATE shows SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE shows SET title = CASE WHEN :title != '' AND title IN ('Feed', 'YouTube-Playlist', 'YouTube-Kanal', 'SoundCloud-Playlist', 'SoundCloud-Profil', 'Spotify-Playlist') THEN :title ELSE title END, artworkUrl = COALESCE(:artworkUrl, artworkUrl), description = CASE WHEN :description != '' THEN :description ELSE description END, lastRefreshAtEpochMs = :at, lastRefreshError = NULL WHERE id = :id")
    suspend fun recordRefresh(id: String, title: String, artworkUrl: String?, description: String, at: Long)

    @Query("UPDATE shows SET lastRefreshAtEpochMs = :at, lastRefreshError = :message WHERE id = :id")
    suspend fun recordRefreshError(id: String, at: Long, message: String)
}

@Dao
interface EpisodeDao {
    @Transaction
    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY (publishedAtEpochMs IS NULL) ASC, publishedAtEpochMs DESC, discoveredAtEpochMs DESC, id ASC")
    fun observeForShow(showId: String): Flow<List<EpisodeWithShow>>

    @Transaction
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN shows s ON s.id = e.showId
        WHERE s.subscribed = 1
          AND s.latestMode != 'NONE'
          AND s.sourceType != 'SPOTIFY_PLAYLIST'
          AND (:includeWord = 1 OR s.category != 'WORD')
          AND (:includeMusic = 1 OR s.category != 'MUSIC')
          AND (:hideScheduled = 0 OR e.availability != 'SCHEDULED')
          AND (
            s.latestMode = 'ALL'
            OR e.id = (
              SELECT newest.id FROM episodes newest
              WHERE newest.showId = e.showId
                AND (:hideScheduled = 0 OR newest.availability != 'SCHEDULED')
              ORDER BY (newest.publishedAtEpochMs IS NULL) ASC,
                       newest.publishedAtEpochMs DESC,
                       newest.discoveredAtEpochMs DESC,
                       newest.id ASC
              LIMIT 1
            )
          )
        ORDER BY (e.publishedAtEpochMs IS NULL) ASC,
                 e.publishedAtEpochMs DESC,
                 e.discoveredAtEpochMs DESC,
                 e.id ASC
        LIMIT :limit
    """)
    fun observeLatest(
        includeWord: Boolean = true,
        includeMusic: Boolean = true,
        hideScheduled: Boolean = false,
        limit: Int = 500,
    ): Flow<List<EpisodeWithShow>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE liked = 1 ORDER BY COALESCE(publishedAtEpochMs, discoveredAtEpochMs) DESC, id ASC")
    fun observeLiked(): Flow<List<EpisodeWithShow>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE downloadStatus != 'NONE' ORDER BY CASE downloadStatus WHEN 'DOWNLOADING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'COMPLETE' THEN 2 ELSE 3 END, COALESCE(publishedAtEpochMs, discoveredAtEpochMs) DESC")
    fun observeDownloads(): Flow<List<EpisodeWithShow>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' OR showId IN (SELECT id FROM shows WHERE title LIKE '%' || :query || '%') ORDER BY COALESCE(publishedAtEpochMs, discoveredAtEpochMs) DESC, id ASC LIMIT :limit")
    fun search(query: String, limit: Int = 300): Flow<List<EpisodeWithShow>>

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun getWithShow(id: String): EpisodeWithShow?

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    fun observeWithShow(id: String): Flow<EpisodeWithShow?>

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun get(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun get(ids: List<String>): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE showId = :showId")
    suspend fun getForShow(showId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes ORDER BY showId, id")
    suspend fun getAll(): List<EpisodeEntity>

    @Transaction
    @Query("SELECT * FROM episodes WHERE positionMs > 0 AND (completedAtEpochMs IS NULL OR COALESCE(lastPlayedAtEpochMs, 0) > completedAtEpochMs) ORDER BY COALESCE(lastPlayedAtEpochMs, 0) DESC, id LIMIT 1")
    suspend fun getLastResumable(): EpisodeWithShow?

    @Transaction
    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY (publishedAtEpochMs IS NULL) ASC, publishedAtEpochMs DESC, discoveredAtEpochMs DESC, id ASC LIMIT 1")
    suspend fun getLatestForShow(showId: String): EpisodeWithShow?

    @Query("SELECT COUNT(*) FROM episodes WHERE showId = :showId")
    suspend fun countForShow(showId: String): Int

    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("UPDATE episodes SET liked = NOT liked WHERE id = :id")
    suspend fun toggleLiked(id: String)

    @Query("UPDATE episodes SET downloadStatus = :status, localFilePath = :localPath, downloadedBytes = :downloaded, downloadTotalBytes = :total WHERE id = :id")
    suspend fun updateDownload(id: String, status: DownloadStatus, localPath: String?, downloaded: Long, total: Long?)

    @Query("UPDATE episodes SET positionMs = :positionMs, playbackDurationMs = :durationMs, lastPlayedAtEpochMs = :playedAt, completedAtEpochMs = CASE WHEN :completed THEN :playedAt ELSE completedAtEpochMs END WHERE id = :id")
    suspend fun updatePlayback(id: String, positionMs: Long, durationMs: Long?, playedAt: Long, completed: Boolean)

    @Query("UPDATE episodes SET localArtworkPath = :path WHERE id = :id")
    suspend fun setLocalArtworkPath(id: String, path: String?)

    @Query("UPDATE episodes SET downloadStatus = 'NONE', localFilePath = NULL, localArtworkPath = NULL, downloadedBytes = 0, downloadTotalBytes = NULL WHERE id = :id")
    suspend fun clearDownload(id: String)

    @Query("UPDATE episodes SET positionMs = 0, playbackDurationMs = NULL, lastPlayedAtEpochMs = NULL, completedAtEpochMs = NULL")
    suspend fun clearPlaybackState()

    @Query("UPDATE episodes SET positionMs = 0 WHERE id = :id")
    suspend fun restartFromBeginning(id: String)

    @Query("UPDATE episodes SET positionMs = 0, lastPlayedAtEpochMs = :startedAt WHERE id = :id AND completedAtEpochMs IS NOT NULL AND COALESCE(lastPlayedAtEpochMs, 0) <= completedAtEpochMs")
    suspend fun beginNewPlaybackAfterCompletion(id: String, startedAt: Long)

    @Query("UPDATE episodes SET resolverErrorType = :type, resolverErrorMessage = :message WHERE id = :id")
    suspend fun setResolverError(id: String, type: ResolverErrorType?, message: String?)

    @Query("UPDATE episodes SET availability = :availability, scheduledForEpochMs = :scheduledFor, availabilityCheckedAtEpochMs = :checkedAt WHERE id = :id")
    suspend fun setAvailability(
        id: String,
        availability: EpisodeAvailability,
        scheduledFor: Long?,
        checkedAt: Long,
    )

    @Query("DELETE FROM episodes WHERE showId = :showId AND liked = 0 AND downloadStatus = 'NONE' AND lastPlayedAtEpochMs IS NULL AND id NOT IN (SELECT id FROM episodes WHERE showId = :showId ORDER BY (publishedAtEpochMs IS NULL) ASC, publishedAtEpochMs DESC, discoveredAtEpochMs DESC, id ASC LIMIT :keep)")
    suspend fun pruneShow(showId: String, keep: Int = 1_000)

    /**
     * Delete stale RSS cache rows in bounded batches. Explicit user state survives automatic
     * cleanup: favourites, downloads and queued episodes are never removed here.
     */
    @Query("DELETE FROM episodes WHERE id IN (:ids) AND liked = 0 AND downloadStatus = 'NONE' AND id NOT IN (SELECT episodeId FROM queue)")
    suspend fun deleteUnprotected(ids: List<String>): Int
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY position")
    fun observe(): Flow<List<QueueEntryEntity>>

    @Query("SELECT * FROM queue ORDER BY position")
    suspend fun getAll(): List<QueueEntryEntity>

    @Transaction
    @Query("SELECT * FROM queue ORDER BY position")
    fun observeDetailed(): Flow<List<QueueEntryWithEpisode>>

    @Query("SELECT * FROM queue WHERE episodeId = :episodeId LIMIT 1")
    suspend fun find(episodeId: String): QueueEntryEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: QueueEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<QueueEntryEntity>)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query("DELETE FROM queue WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: String)

    @Query("UPDATE queue SET position = :position WHERE episodeId = :episodeId")
    suspend fun setPosition(episodeId: String, position: Int)

    @Query("UPDATE queue SET availabilityAttemptedAtEpochMs = :attemptedAt WHERE episodeId = :episodeId")
    suspend fun markAvailabilityAttempt(episodeId: String, attemptedAt: Long)

    /** A normal show refresh after the announced time may enable one new at-turn attempt. */
    @Query("UPDATE queue SET availabilityAttemptedAtEpochMs = NULL WHERE episodeId = :episodeId")
    suspend fun clearAvailabilityAttempt(episodeId: String)

    @Transaction
    suspend fun reorder(episodeIds: List<String>) {
        episodeIds.forEachIndexed { index, episodeId -> setPosition(episodeId, index) }
    }
}

@Dao
interface PlaybackHistoryDao {
    @Transaction
    @Query("""
        SELECT h.* FROM playback_history h
        WHERE h.historyId = (
            SELECT newest.historyId FROM playback_history newest
            WHERE newest.episodeId = h.episodeId
            ORDER BY newest.playedAtEpochMs DESC, newest.historyId DESC
            LIMIT 1
        )
        ORDER BY h.playedAtEpochMs DESC, h.historyId DESC
    """)
    fun observe(): Flow<List<PlaybackHistoryWithEpisode>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: PlaybackHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<PlaybackHistoryEntity>)

    @Query("SELECT * FROM playback_history ORDER BY playedAtEpochMs, historyId")
    suspend fun getAll(): List<PlaybackHistoryEntity>

    @Query("SELECT playedAtEpochMs FROM playback_history WHERE episodeId = :episodeId ORDER BY playedAtEpochMs DESC, historyId DESC")
    fun observePlaybackDates(episodeId: String): Flow<List<Long>>

    @Query("DELETE FROM playback_history WHERE episodeId = :episodeId")
    suspend fun deleteEpisode(episodeId: String)

    @Query("DELETE FROM playback_history")
    suspend fun clear()

    @Query("DELETE FROM playback_history WHERE historyId NOT IN (SELECT historyId FROM playback_history ORDER BY playedAtEpochMs DESC, historyId DESC LIMIT :keep)")
    suspend fun prune(keep: Int = 10000)
}
