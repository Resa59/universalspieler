package de.rdoe.weeklydjshows.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.model.*
import de.rdoe.weeklydjshows.resolver.CompositeStreamResolver
import de.rdoe.weeklydjshows.resolver.StreamResolver
import de.rdoe.weeklydjshows.resolver.direct.DirectStreamResolver
import de.rdoe.weeklydjshows.resolver.newpipe.NewPipeStreamResolver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface PlaybackStartResult {
    data object Started : PlaybackStartResult
    data class Failed(val error: ResolverError) : PlaybackStartResult
}

class PlaybackRepository(
    private val context: Context,
    private val database: WeeklyDjDatabase = WeeklyDjDatabase.get(context),
    private val resolver: StreamResolver = CompositeStreamResolver(
        listOf(DirectStreamResolver(), NewPipeStreamResolver(context)),
    ),
) {
    suspend fun play(episodeId: String): PlaybackStartResult {
        val resolved = resolveEpisode(episodeId) ?: return PlaybackStartResult.Failed(
            ResolverError(ResolverErrorType.UNKNOWN, "Folge wurde nicht gefunden."),
        )
        if (resolved.second is ResolveResult.Failure) {
            val failure = resolved.second as ResolveResult.Failure
            return PlaybackStartResult.Failed(failure.error)
        }
        val source = (resolved.second as ResolveResult.Success).source
        val targetWasQueued = database.queueDao().find(episodeId) != null
        val item = mediaItem(resolved.first, source, fromQueue = targetWasQueued)
        val controller = buildController()
        try {
            // A manual play replaces only the current item. The user's explicit queue is durable.
            returnInterruptedQueueItemToFront(controller, episodeId)
            database.queueDao().remove(episodeId)
            val startPosition = resolved.first.episode.resumablePositionMs()
            // Supplying the position in the same Media3 operation avoids a cold-service race in
            // which prepare/play could outrun a separate seek and restart the episode at zero.
            controller.setMediaItem(item, startPosition)
            controller.prepare()
            controller.play()
            restoreQueue(controller)
        } finally {
            controller.release()
        }
        return PlaybackStartResult.Started
    }

    /** Resolves and restores an episode into the media session without starting audio. */
    suspend fun preparePaused(episodeId: String): PlaybackStartResult {
        val resolved = resolveEpisode(episodeId) ?: return PlaybackStartResult.Failed(
            ResolverError(ResolverErrorType.UNKNOWN, "Folge wurde nicht gefunden."),
        )
        val source = (resolved.second as? ResolveResult.Success)?.source
            ?: return PlaybackStartResult.Failed((resolved.second as ResolveResult.Failure).error)
        val targetWasQueued = database.queueDao().find(episodeId) != null
        val controller = buildController()
        try {
            if (controller.currentMediaItem?.mediaId == episodeId) {
                controller.pause()
                return PlaybackStartResult.Started
            }
            returnInterruptedQueueItemToFront(controller, episodeId)
            if (targetWasQueued) database.queueDao().remove(episodeId)
            val startPosition = resolved.first.episode.resumablePositionMs()
            controller.setMediaItem(
                mediaItem(resolved.first, source, fromQueue = targetWasQueued),
                startPosition,
            )
            controller.prepare()
            controller.pause()
            restoreQueue(controller)
        } finally {
            controller.release()
        }
        return PlaybackStartResult.Started
    }

    suspend fun addToQueue(episodeId: String): PlaybackStartResult {
        val row = database.episodeDao().getWithShow(episodeId) ?: return PlaybackStartResult.Failed(
            ResolverError(ResolverErrorType.UNKNOWN, "Folge wurde nicht gefunden."),
        )
        val queueDao = database.queueDao()
        if (queueDao.find(episodeId) != null) return PlaybackStartResult.Started
        queueDao.insert(
            QueueEntryEntity(
                episodeId = episodeId,
                position = queueDao.maxPosition() + 1,
            ),
        )
        // A platform listing has already identified this entry as upcoming. Merely adding it to
        // the queue must not probe the stream; it is checked only when its queue position is due.
        if (row.episode.availability == EpisodeAvailability.SCHEDULED) {
            return PlaybackStartResult.Started
        }
        // Do not let a later ordinary item jump across an earlier planned queue position merely
        // because the planned item intentionally has no Media3 stream yet.
        if (hasScheduledBarrierBefore(episodeId)) return PlaybackStartResult.Started
        val resolved = resolveEpisode(episodeId) ?: return PlaybackStartResult.Started
        val success = resolved.second as? ResolveResult.Success
        if (success == null) {
            queueDao.remove(episodeId)
            return PlaybackStartResult.Failed((resolved.second as ResolveResult.Failure).error)
        }
        val controller = buildController()
        try {
            controller.addMediaItem(mediaItem(resolved.first, success.source, fromQueue = true))
        } finally {
            controller.release()
        }
        return PlaybackStartResult.Started
    }

    suspend fun removeFromQueue(episodeId: String) {
        database.queueDao().remove(episodeId)
        val controller = buildController()
        try {
            val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
            val index = (start until controller.mediaItemCount)
                .firstOrNull { controller.getMediaItemAt(it).mediaId == episodeId }
            if (index != null) controller.removeMediaItem(index)
        } finally {
            controller.release()
        }
    }

    suspend fun clearQueue() {
        database.queueDao().clear()
        val controller = buildController()
        try {
            val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
            if (start < controller.mediaItemCount) controller.removeMediaItems(start, controller.mediaItemCount)
        } finally {
            controller.release()
        }
    }

    suspend fun reorderQueue(episodeIds: List<String>) {
        database.queueDao().reorder(episodeIds)
        val controller = buildController()
        try {
            val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
            episodeIds.forEachIndexed { offset, episodeId ->
                val target = start + offset
                val current = (target until controller.mediaItemCount)
                    .firstOrNull { controller.getMediaItemAt(it).mediaId == episodeId }
                if (current != null && current != target) controller.moveMediaItem(current, target)
            }
        } finally {
            controller.release()
        }
    }

    suspend fun restartQueuedEpisode(episodeId: String) {
        database.episodeDao().restartFromBeginning(episodeId)
        val controller = buildController()
        try {
            val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
            val index = (start until controller.mediaItemCount)
                .firstOrNull { controller.getMediaItemAt(it).mediaId == episodeId }
            if (index != null) {
                val current = controller.getMediaItemAt(index)
                val metadata = current.mediaMetadata
                val extras = Bundle(metadata.extras ?: Bundle()).apply {
                    putLong(MEDIA_EXTRA_RESUME_POSITION_MS, 0L)
                }
                controller.replaceMediaItem(
                    index,
                    current.buildUpon()
                        .setMediaMetadata(metadata.buildUpon().setExtras(extras).build())
                        .build(),
                )
            }
        } finally {
            controller.release()
        }
    }

    suspend fun resolveEpisode(
        episodeId: String,
        forceRefresh: Boolean = false,
        preferredQuality: StreamingQuality? = null,
        downloadCompatibleOnly: Boolean = false,
        ignoreLocalFile: Boolean = false,
    ): Pair<EpisodeWithShow, ResolveResult>? {
        val row = database.episodeDao().getWithShow(episodeId) ?: return null
        val episode = row.episode
        val localPath = if (ignoreLocalFile) null else episode.localFilePath?.takeIf {
            episode.downloadStatus == DownloadStatus.COMPLETE && File(it).isFile
        }
        val localArtwork = if (ignoreLocalFile) null else episode.localArtworkPath
            ?.takeIf { File(it).isFile }
            ?.let { Uri.fromFile(File(it)).toString() }
        val request = PlaybackRequest(
            episodeId = episode.id,
            originalPageUrl = episode.pageUrl,
            enclosureUrl = episode.enclosureUrl,
            sourceType = episode.sourceType,
            title = episode.title,
            showTitle = row.show.title,
            artworkUrl = localArtwork ?: episode.artworkUrl ?: row.show.artworkUrl,
            storedPositionMs = episode.positionMs,
            localFilePath = localPath,
            preferredQuality = preferredQuality ?: if (
                localPath == null && episode.enclosureUrl == null
            ) currentStreamingQuality() else null,
            requireProgressiveHttp = downloadCompatibleOnly,
        )
        val result = if (episode.sourceType == EpisodeSourceType.SPOTIFY && localPath == null) {
            ResolveResult.Failure(
                ResolverError(
                    ResolverErrorType.DRM_PROTECTED,
                    "Spotify-Playlisttitel werden in der App aktualisiert und einsortiert, die geschützte Vollwiedergabe wird aber von Spotify geöffnet.",
                    episode.pageUrl,
                ),
            )
        } else if (episode.sourceType == EpisodeSourceType.MIXCLOUD && localPath == null) {
            ResolveResult.Failure(
                ResolverError(
                    ResolverErrorType.UNSUPPORTED_URL,
                    "Mixcloud stellt über seine offizielle API keine direkte Audiostream-Adresse für Drittanbieter-Player bereit.",
                    episode.pageUrl,
                ),
            )
        } else {
            resolver.resolve(request, forceRefresh)
        }
        val checkedAt = System.currentTimeMillis()
        val checkedResult = when (result) {
            is ResolveResult.Success -> {
                database.episodeDao().setResolverError(episode.id, null, null)
                if (episode.sourceType == EpisodeSourceType.YOUTUBE) {
                    database.episodeDao().setAvailability(
                        episode.id,
                        EpisodeAvailability.AVAILABLE,
                        null,
                        checkedAt,
                    )
                }
                result
            }
            is ResolveResult.Failure -> {
                val failure = if (episode.isScheduledNearOrInFuture(checkedAt)) {
                    ResolveResult.Failure(
                        ResolverError(
                            ResolverErrorType.NOT_YET_AVAILABLE,
                            scheduledMessage(episode.scheduledForEpochMs),
                            episode.pageUrl,
                            result.error.causeClass,
                            recoverable = true,
                        ),
                    )
                } else {
                    result
                }
                database.episodeDao().setResolverError(
                    episode.id,
                    failure.error.type,
                    failure.error.message,
                )
                if (failure.error.type == ResolverErrorType.NOT_YET_AVAILABLE) {
                    database.episodeDao().setAvailability(
                        episode.id,
                        EpisodeAvailability.SCHEDULED,
                        episode.scheduledForEpochMs,
                        checkedAt,
                    )
                }
                failure
            }
        }
        return row to checkedResult
    }

    private fun currentStreamingQuality(): StreamingQuality {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        val mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val prefs = context.getSharedPreferences(StreamingPreferenceKeys.FILE, Context.MODE_PRIVATE)
        val key = if (mobile) StreamingPreferenceKeys.MOBILE_QUALITY else StreamingPreferenceKeys.WIFI_QUALITY
        val fallback = if (mobile) StreamingPreferenceKeys.DEFAULT_MOBILE else StreamingPreferenceKeys.DEFAULT_WIFI
        return runCatching {
            StreamingQuality.valueOf(prefs.getString(key, fallback) ?: fallback)
        }.getOrDefault(if (mobile) StreamingQuality.MEDIUM else StreamingQuality.HIGH)
    }

    fun mediaItem(
        row: EpisodeWithShow,
        source: ResolvedMediaSource,
        resumePositionOverrideMs: Long? = null,
        fromQueue: Boolean = false,
    ): MediaItem {
        PlaybackHeaders.register(source.playbackUrl, source.requestHeaders)
        val localArtwork = row.episode.localArtworkPath
            ?.takeIf { File(it).isFile }
            ?.let { Uri.fromFile(File(it)).toString() }
        val resumePositionMs = resumePositionOverrideMs ?: row.episode.resumablePositionMs()
        // A Chromecast cannot read file:// artwork or audio from the phone. Keep those local URIs
        // for offline playback, but also carry network alternatives for the Cast converter.
        val castArtworkUrl = sequenceOf(
            source.artworkUrl,
            row.episode.artworkUrl,
            row.show.artworkUrl,
        ).firstOrNull(::isHttpUrl)
        val castPlaybackUrl = sequenceOf(
            source.playbackUrl,
            row.episode.enclosureUrl,
        ).firstOrNull(::isHttpUrl)
        val extras = Bundle().apply {
            putLong(MEDIA_EXTRA_RESUME_POSITION_MS, resumePositionMs)
            putBoolean(MEDIA_EXTRA_FROM_PERSISTED_QUEUE, fromQueue)
            castArtworkUrl?.let { putString(MEDIA_EXTRA_CAST_ARTWORK_URI, it) }
            castPlaybackUrl?.let { putString(MEDIA_EXTRA_CAST_PLAYBACK_URI, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(source.title ?: row.episode.title)
            .setArtist(source.artistOrChannel ?: row.show.title)
            .setAlbumTitle(row.show.title)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .apply {
                (localArtwork ?: source.artworkUrl ?: row.episode.artworkUrl ?: row.show.artworkUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(row.episode.id)
            .setUri(source.playbackUrl)
            .setMimeType(source.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun restoreQueue(controller: MediaController) {
        resolveQueueMediaItems().forEach(controller::addMediaItem)
    }

    /** Restores the playable prefix. A planned item is a boundary resolved only when it is due. */
    suspend fun resolveQueueMediaItems(): List<MediaItem> = buildList {
        for (entry in database.queueDao().getAll()) {
            val row = database.episodeDao().getWithShow(entry.episodeId) ?: continue
            if (row.episode.availability == EpisodeAvailability.SCHEDULED) break
            val resolved = resolveEpisode(entry.episodeId) ?: continue
            val success = resolved.second as? ResolveResult.Success ?: continue
            add(mediaItem(resolved.first, success.source, fromQueue = true))
        }
    }

    /** Called exactly when the loaded player queue has ended; planned entries remain in Room. */
    suspend fun resolveNextQueueItemAtTurn(): MediaItem? {
        for (entry in database.queueDao().getAll().sortedBy { it.position }) {
            val row = database.episodeDao().getWithShow(entry.episodeId) ?: continue
            if (row.episode.availability == EpisodeAvailability.SCHEDULED) {
                if (entry.availabilityAttemptedAtEpochMs != null) continue
                // Mark before resolving so cancellation or an extractor exception cannot turn
                // into repeated polling. A normal show refresh after the planned time resets it.
                database.queueDao().markAvailabilityAttempt(entry.episodeId, System.currentTimeMillis())
            }
            val resolved = resolveEpisode(entry.episodeId) ?: continue
            val success = resolved.second as? ResolveResult.Success ?: continue
            return mediaItem(resolved.first, success.source, fromQueue = true)
        }
        return null
    }

    /**
     * Starts the first currently playable durable queue entry. This is used by explicit Bluetooth
     * autoplay and, unlike iterating over episode IDs with [play], preserves the one-at-turn rule
     * for scheduled entries.
     */
    suspend fun playNextQueueItemAtTurn(): Boolean {
        val item = resolveNextQueueItemAtTurn() ?: return false
        val controller = buildController()
        try {
            returnInterruptedQueueItemToFront(controller, item.mediaId)
            database.queueDao().remove(item.mediaId)
            val startPosition = item.mediaMetadata.extras
                ?.getLong(MEDIA_EXTRA_RESUME_POSITION_MS, 0L)
                ?.coerceAtLeast(0L)
                ?: 0L
            controller.setMediaItem(item, startPosition)
            controller.prepare()
            controller.play()
            restoreQueue(controller)
        } finally {
            controller.release()
        }
        return true
    }

    private suspend fun hasScheduledBarrierBefore(episodeId: String): Boolean {
        for (entry in database.queueDao().getAll().sortedBy { it.position }) {
            if (entry.episodeId == episodeId) return false
            if (database.episodeDao().get(entry.episodeId)?.availability == EpisodeAvailability.SCHEDULED) {
                return true
            }
        }
        return false
    }

    /**
     * Only a title carrying the queue-origin marker is restored. This avoids turning ordinary
     * manual sampling into an ever-growing queue while preserving an interrupted queued title.
     */
    private suspend fun returnInterruptedQueueItemToFront(controller: MediaController, nextId: String) {
        val current = controller.currentMediaItem ?: return
        val currentId = current.mediaId.takeIf { it.isNotBlank() && it != nextId } ?: return
        if (current.mediaMetadata.extras?.getBoolean(MEDIA_EXTRA_FROM_PERSISTED_QUEUE, false) != true) return
        val position = controller.currentPosition.coerceAtLeast(0L)
        val episode = database.episodeDao().get(currentId) ?: return
        val completedAtEpochMs = episode.completedAtEpochMs
        if (completedAtEpochMs != null &&
            (episode.lastPlayedAtEpochMs ?: 0L) <= completedAtEpochMs
        ) return
        val duration = controller.duration.takeIf { it > 0L }
        val completed = duration?.let { position >= (it * 0.95).toLong() } == true
        if (position > 0L) {
            database.episodeDao().updatePlayback(
                currentId,
                position,
                duration,
                System.currentTimeMillis(),
                completed,
            )
        }
        if (completed || database.queueDao().find(currentId) != null) return
        val existing = database.queueDao().getAll()
        existing.forEachIndexed { index, entry ->
            database.queueDao().setPosition(entry.episodeId, index + 1)
        }
        database.queueDao().insert(QueueEntryEntity(episodeId = currentId, position = 0))
    }

    private suspend fun buildController(): MediaController = suspendCancellableCoroutine { continuation ->
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        continuation.invokeOnCancellation { future.cancel(true) }
        future.addListener({
            runCatching { future.get() }
                .onSuccess { if (continuation.isActive) continuation.resume(it) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun isHttpUrl(value: String?): Boolean = value?.let { url ->
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    } == true

    private fun EpisodeEntity.isScheduledNearOrInFuture(now: Long): Boolean =
        availability == EpisodeAvailability.SCHEDULED &&
            scheduledForEpochMs?.let { it >= now - SCHEDULED_GRACE_MS } != false

    /** A completed title restarts once; a later partially replayed run is resumable. */
    private fun EpisodeEntity.resumablePositionMs(): Long {
        val completedAtEpochMs = this.completedAtEpochMs
        return positionMs.takeIf { position ->
            position > 0L && (
                completedAtEpochMs == null ||
                    (lastPlayedAtEpochMs ?: 0L) > completedAtEpochMs
                )
        } ?: 0L
    }

    private fun scheduledMessage(scheduledForEpochMs: Long?): String {
        val at = scheduledForEpochMs ?: return "Diese Folge ist geplant und noch nicht verfügbar."
        val date = Date(at)
        val day = DateFormat.getMediumDateFormat(context).format(date)
        val time = DateFormat.getTimeFormat(context).format(date)
        return "Diese Folge ist für $day um $time geplant und noch nicht verfügbar."
    }

    private companion object {
        const val SCHEDULED_GRACE_MS = 5 * 60_000L
    }
}

internal const val MEDIA_EXTRA_RESUME_POSITION_MS = "de.rdoe.weeklydjshows.RESUME_POSITION_MS"
internal const val MEDIA_EXTRA_FROM_PERSISTED_QUEUE = "de.rdoe.weeklydjshows.FROM_PERSISTED_QUEUE"
internal const val MEDIA_EXTRA_CAST_ARTWORK_URI = "de.rdoe.weeklydjshows.CAST_ARTWORK_URI"
internal const val MEDIA_EXTRA_CAST_PLAYBACK_URI = "de.rdoe.weeklydjshows.CAST_PLAYBACK_URI"
