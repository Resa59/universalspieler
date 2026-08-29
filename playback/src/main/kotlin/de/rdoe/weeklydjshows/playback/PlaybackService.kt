package de.rdoe.weeklydjshows.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.rdoe.weeklydjshows.database.EpisodeWithShow
import de.rdoe.weeklydjshows.database.PlaybackHistoryEntity
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import de.rdoe.weeklydjshows.model.DeliveryType
import de.rdoe.weeklydjshows.model.ResolveResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: Player
    private lateinit var repository: PlaybackRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retriedMediaId: String? = null
    private var trackedMediaId: String? = null
    private var trackedPositionMs: Long = 0
    private var trackedDurationMs: Long? = null
    private var suppressNextHistoryMediaId: String? = null
    private var pendingHistoryMediaId: String? = null
    private var refreshingRemoteMediaId: String? = null
    private var advancingPersistedQueue = false
    private var downloadSwitchJob: Job? = null
    private var watchedDownloadMediaId: String? = null
    private var switchedDownloadPath: String? = null
    private var currentLiked = false
    private var hasPersistedQueueItems = false
    private val seekBackCommand = SessionCommand(SESSION_ACTION_SEEK_BACK_10, Bundle.EMPTY)
    private val seekForwardCommand = SessionCommand(SESSION_ACTION_SEEK_FORWARD_30, Bundle.EMPTY)
    private val toggleLikeCommand = SessionCommand(SESSION_ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    private val previousCommand = SessionCommand(SESSION_ACTION_PREVIOUS, Bundle.EMPTY)
    private val nextCommand = SessionCommand(SESSION_ACTION_NEXT, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val mediaNotificationProvider = DefaultMediaNotificationProvider(this).apply {
            // Explicit app-specific monochrome icon for the collapsed status bar/One UI media UI.
            setSmallIcon(R.drawable.ic_notification_headphones)
        }
        setMediaNotificationProvider(mediaNotificationProvider)
        val playbackHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        // Downloads already use OkHttp successfully for SoundCloud. Using the same HTTP stack for
        // ExoPlayer also preserves request headers across SoundCloud's CDN redirects.
        val httpFactory = OkHttpDataSource.Factory(playbackHttpClient)
            .setUserAgent(DEFAULT_USER_AGENT)
        val defaultFactory = DefaultDataSource.Factory(this, httpFactory)
        val routingFactory = HeaderInjectingDataSource.Factory(defaultFactory)
        val localPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(routingFactory))
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val remotePlayer = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(WeeklyDjCastMediaItemConverter())
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        // CastPlayer keeps the existing ExoPlayer as the local player and moves the same session
        // state to/from the Cast receiver when a Google Cast route connects or disconnects.
        player = CastPlayer.Builder(this)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .build()
        repository = PlaybackRepository(applicationContext)
        val sessionBuilder = MediaLibrarySession.Builder(
            this,
            player,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    // Start with MediaLibrarySession's own result so browser/library commands are
                    // retained for clients such as Android Auto, then add our media actions.
                    val defaults = super.onConnect(session, controller)
                    val sessionCommands = defaults.availableSessionCommands.buildUpon()
                        .add(seekBackCommand)
                        .add(seekForwardCommand)
                        .add(toggleLikeCommand)
                        .add(previousCommand)
                        .add(nextCommand)
                        .build()
                    var playerCommands = defaults.availablePlayerCommands
                    if (session.isMediaNotificationController(controller)) {
                        // Primary queue actions and overflow actions are supplied below. Suppress
                        // automatic side buttons so One UI cannot replace them when the queue
                        // changes. Play/Pause remains the central player action.
                        playerCommands = playerCommands.buildUpon()
                            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                            .remove(Player.COMMAND_SEEK_BACK)
                            .remove(Player.COMMAND_SEEK_FORWARD)
                            .remove(Player.COMMAND_SEEK_TO_NEXT)
                            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                            .build()
                    }
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == SESSION_ACTION_SEEK_BACK_10) {
                        player.seekBack()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == SESSION_ACTION_SEEK_FORWARD_30) {
                        player.seekForward()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == SESSION_ACTION_TOGGLE_LIKE) {
                        toggleCurrentLike()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == SESSION_ACTION_PREVIOUS) {
                        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == SESSION_ACTION_NEXT) {
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                        else advancePersistedQueue(advanceNow = true)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            },
        )
            .setCustomLayout(notificationButtons(liked = false))
            .setMediaButtonPreferences(notificationButtons(liked = false))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        session = sessionBuilder.build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val nextId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
                val previousId = trackedMediaId
                if (previousId != null && previousId != nextId) {
                    persistSnapshot(
                        id = previousId,
                        position = trackedPositionMs,
                        duration = trackedDurationMs,
                        forceCompleted = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }
                // Speed is deliberately a per-title choice. Podcast speed must never leak into
                // the next music track (or vice versa). A same-item stream retry keeps its speed.
                if (nextId != null && nextId != previousId) {
                    player.setPlaybackParameters(PlaybackParameters(1f, 1f))
                }
                trackedMediaId = nextId
                val resumePosition = mediaItem?.mediaMetadata?.extras
                    ?.getLong(MEDIA_EXTRA_RESUME_POSITION_MS, 0L)
                    ?.coerceAtLeast(0L)
                    ?: 0L
                trackedPositionMs = resumePosition
                trackedDurationMs = null
                retriedMediaId = null
                pendingHistoryMediaId = nextId
                currentLiked = false
                switchedDownloadPath = null
                watchForCompletedDownload(nextId)
                updateNotificationButtons()
                nextId?.let(::refreshCurrentLike)
                // Queued items carry their saved position with them. This applies before normal
                // playback advances, so an automatically started queue item resumes just like a
                // manually opened episode.
                if (nextId != null && resumePosition > 0L) {
                    player.seekTo(resumePosition)
                }
                // Adding the first item to an otherwise empty controller can itself cause a
                // media-item transition. Only count it as heard (and consume it from the
                // persisted queue) once playback really starts.
                if (nextId != null && player.isPlaying) {
                    pendingHistoryMediaId = null
                    recordPlaybackStart(nextId)
                }
                ensureRemotePlayableSource()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.playWhenReady) advancePersistedQueue()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateNotificationButtons()
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    ensureRemotePlayableSource()
                } else {
                    refreshingRemoteMediaId = null
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                        ?.let(::switchToCompletedDownloadIfAvailable)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                    if (id != null && pendingHistoryMediaId == id) {
                        pendingHistoryMediaId = null
                        recordPlaybackStart(id)
                    }
                } else {
                    persistPosition()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpError = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                if (httpError != null && httpError.responseCode in setOf(401, 403)) refreshExpiredSource()
            }
        })

        serviceScope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) persistPosition()
            }
        }
        serviceScope.launch {
            // Event-driven only: a queue edit or a normal show refresh can make the first planned
            // entry available. There is deliberately no timer that probes upcoming streams.
            WeeklyDjDatabase.get(applicationContext).queueDao().observeDetailed().collect { entries ->
                hasPersistedQueueItems = entries.isNotEmpty()
                updateNotificationButtons()
                if (player.playbackState == Player.STATE_ENDED) advancePersistedQueue()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private fun notificationButtons(liked: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Vorherige Folge")
            .setSessionCommand(previousCommand)
            .setEnabled(player.hasPreviousMediaItem())
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Nächste Folge")
            .setSessionCommand(nextCommand)
            .setEnabled(player.hasNextMediaItem() || hasPersistedQueueItems)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        // Android mobile media controls expose only two navigation and two overflow positions in
        // addition to Play/Pause. Put the three explicitly requested actions first; larger
        // controllers may render the remaining seek preference as well.
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Gefällt mir entfernen" else "Gefällt mir")
            .setSessionCommand(toggleLikeCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("10 Sekunden zurück")
            .setSessionCommand(seekBackCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("30 Sekunden vor")
            .setSessionCommand(seekForwardCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun updateNotificationButtons() {
        session?.let { currentSession ->
            val buttons = notificationButtons(currentLiked)
            currentSession.setCustomLayout(buttons)
            currentSession.setMediaButtonPreferences(buttons)
        }
    }

    private fun refreshCurrentLike(id: String) {
        serviceScope.launch(Dispatchers.IO) {
            val liked = WeeklyDjDatabase.get(applicationContext).episodeDao().get(id)?.liked ?: false
            withContext(Dispatchers.Main.immediate) {
                if (player.currentMediaItem?.mediaId == id) {
                    currentLiked = liked
                    updateNotificationButtons()
                }
            }
        }
    }

    private fun toggleCurrentLike() {
        val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        serviceScope.launch(Dispatchers.IO) {
            val dao = WeeklyDjDatabase.get(applicationContext).episodeDao()
            dao.toggleLiked(id)
            val liked = dao.get(id)?.liked ?: false
            withContext(Dispatchers.Main.immediate) {
                if (player.currentMediaItem?.mediaId == id) {
                    currentLiked = liked
                    updateNotificationButtons()
                }
            }
        }
    }

    /**
     * A download may finish while the same episode is streaming. Observe only that current row and
     * replace the current Media3 item once the final file is atomically published by WorkManager.
     * Position, queue origin, speed and play/pause state survive the source change.
     */
    private fun watchForCompletedDownload(id: String?) {
        if (watchedDownloadMediaId == id) return
        downloadSwitchJob?.cancel()
        downloadSwitchJob = null
        watchedDownloadMediaId = id
        switchedDownloadPath = null
        if (id == null) return
        downloadSwitchJob = serviceScope.launch {
            WeeklyDjDatabase.get(applicationContext).episodeDao().observeWithShow(id).collect { row ->
                val episode = row?.episode ?: return@collect
                val path = episode.localFilePath?.takeIf {
                    episode.downloadStatus == de.rdoe.weeklydjshows.database.DownloadStatus.COMPLETE &&
                        File(it).isFile
                } ?: return@collect
                if (path != switchedDownloadPath) switchCurrentToDownloadedFile(row, path)
            }
        }
    }

    private fun switchToCompletedDownloadIfAvailable(id: String) {
        serviceScope.launch {
            val row = WeeklyDjDatabase.get(applicationContext).episodeDao().getWithShow(id) ?: return@launch
            val path = row.episode.localFilePath?.takeIf {
                row.episode.downloadStatus == de.rdoe.weeklydjshows.database.DownloadStatus.COMPLETE &&
                    File(it).isFile
            } ?: return@launch
            if (path != switchedDownloadPath) switchCurrentToDownloadedFile(row, path)
        }
    }

    private suspend fun switchCurrentToDownloadedFile(row: EpisodeWithShow, path: String) {
        val id = row.episode.id
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE ||
            player.currentMediaItem?.mediaId != id
        ) return
        val current = player.currentMediaItem ?: return
        if (current.localConfiguration?.uri?.scheme.equals("file", ignoreCase = true)) {
            switchedDownloadPath = path
            return
        }
        val resolved = repository.resolveEpisode(id) ?: return
        val success = resolved.second as? ResolveResult.Success ?: return
        if (success.source.deliveryType != DeliveryType.LOCAL_FILE) return

        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady
        val speed = player.playbackParameters
        val replacement = repository.mediaItem(
            resolved.first,
            success.source,
            resumePositionOverrideMs = position,
            fromQueue = current.mediaMetadata.extras
                ?.getBoolean(MEDIA_EXTRA_FROM_PERSISTED_QUEUE, false) == true,
        )
        switchedDownloadPath = path
        suppressNextHistoryMediaId = id
        player.replaceMediaItem(index, replacement)
        player.seekTo(index, position)
        player.prepare()
        player.setPlaybackParameters(speed)
        if (shouldPlay) player.play() else player.pause()
    }

    private fun refreshExpiredSource() {
        val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (retriedMediaId == id) return
        retriedMediaId = id
        val position = player.currentPosition.coerceAtLeast(0)
        serviceScope.launch {
            val resolved = repository.resolveEpisode(
                id,
                forceRefresh = true,
                ignoreLocalFile = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            ) ?: return@launch
            val success = resolved.second as? ResolveResult.Success ?: return@launch
            val item = repository.mediaItem(
                resolved.first,
                success.source,
                resumePositionOverrideMs = position,
                fromQueue = player.currentMediaItem?.mediaMetadata?.extras
                    ?.getBoolean(MEDIA_EXTRA_FROM_PERSISTED_QUEUE, false) == true,
            )
            suppressNextHistoryMediaId = id
            player.setMediaItem(item, position)
            player.prepare()
            player.play()
        }
    }

    /** Reaches the next durable queue position only after the loaded player prefix has ended. */
    private fun advancePersistedQueue(advanceNow: Boolean = false) {
        if (advancingPersistedQueue) return
        advancingPersistedQueue = true
        serviceScope.launch {
            try {
                val next = repository.resolveNextQueueItemAtTurn() ?: return@launch
                if ((0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == next.mediaId }) {
                    return@launch
                }
                val shouldResume = player.playWhenReady
                player.addMediaItem(next)
                if ((advanceNow || player.playbackState == Player.STATE_ENDED) && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    if (shouldResume) player.play()
                }
            } finally {
                advancingPersistedQueue = false
            }
        }
    }

    /**
     * A Cast receiver cannot open a file:// URL from this phone. If a downloaded episode without
     * a known HTTP enclosure was active when the user started casting, resolve a fresh network
     * source and replace just that remote queue item while preserving playback position.
     */
    private fun ensureRemotePlayableSource() {
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        val currentItem = player.currentMediaItem ?: return
        val uri = currentItem.localConfiguration?.uri ?: return
        if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) return
        val id = currentItem.mediaId.takeIf { it.isNotBlank() } ?: return
        if (refreshingRemoteMediaId == id) return
        refreshingRemoteMediaId = id
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        serviceScope.launch {
            try {
                val resolved = repository.resolveEpisode(
                    id,
                    forceRefresh = true,
                    ignoreLocalFile = true,
                ) ?: return@launch
                val success = resolved.second as? ResolveResult.Success ?: return@launch
                if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE ||
                    player.currentMediaItem?.mediaId != id
                ) return@launch
                val replacement = repository.mediaItem(
                    resolved.first,
                    success.source,
                    resumePositionOverrideMs = position,
                    fromQueue = currentItem.mediaMetadata.extras
                        ?.getBoolean(MEDIA_EXTRA_FROM_PERSISTED_QUEUE, false) == true,
                )
                suppressNextHistoryMediaId = id
                player.replaceMediaItem(index, replacement)
                player.seekTo(index, position)
                player.prepare()
                if (playWhenReady) player.play()
            } finally {
                if (refreshingRemoteMediaId == id) refreshingRemoteMediaId = null
            }
        }
    }

    private fun persistPosition() {
        val id = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        trackedMediaId = id
        trackedPositionMs = position
        trackedDurationMs = duration
        persistSnapshot(id, position, duration, forceCompleted = false)
    }

    private fun persistSnapshot(id: String, position: Long, duration: Long?, forceCompleted: Boolean) {
        val completed = forceCompleted || duration?.let {
            position >= (it * 0.95).toLong() || (position >= (it * 0.80).toLong() && it - position <= 30_000)
        } == true
        serviceScope.launch(Dispatchers.IO) {
            WeeklyDjDatabase.get(applicationContext).episodeDao().updatePlayback(
                id,
                position,
                duration,
                System.currentTimeMillis(),
                completed,
            )
        }
    }

    private fun recordPlaybackStart(id: String) {
        if (suppressNextHistoryMediaId == id) {
            suppressNextHistoryMediaId = null
            return
        }
        // A stale retry marker must never suppress a later, unrelated episode.
        suppressNextHistoryMediaId = null
        serviceScope.launch(Dispatchers.IO) {
            val db = WeeklyDjDatabase.get(applicationContext)
            // A finished episode starts at zero, but from this point on it is a new playback
            // session whose new position should be resumable again.
            db.episodeDao().beginNewPlaybackAfterCompletion(id, System.currentTimeMillis())
            db.queueDao().remove(id)
            db.playbackHistoryDao().insert(PlaybackHistoryEntity(episodeId = id))
            db.playbackHistoryDao().prune()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MediaSessionService intentionally keeps playing by default when the task is swiped
        // away. For this app, explicitly treating that gesture as "close" is less surprising.
        persistPosition()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        persistPosition()
        session?.release()
        session = null
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "WeeklyDJShows/1.3.1 (Android)"
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
