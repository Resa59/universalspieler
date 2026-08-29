package de.rdoe.weeklydjshows.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isRemote: Boolean = false,
)

class PlaybackConnection(context: Context) : Closeable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var hasPersistedNext = false
    private val toggleLikeCommand = SessionCommand(SESSION_ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    private val nextCommand = SessionCommand(SESSION_ACTION_NEXT, Bundle.EMPTY)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = controller?.let(::publish) ?: Unit
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                mediaController.addListener(listener)
                publish(mediaController)
            }
        }, ContextCompat.getMainExecutor(app))

        scope.launch {
            while (isActive) {
                delay(500)
                controller?.let(::publish)
            }
        }
        scope.launch {
            WeeklyDjDatabase.get(app).queueDao().observe().collect { queue ->
                hasPersistedNext = queue.isNotEmpty()
                controller?.let(::publish)
            }
        }
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        controller?.let { player ->
            val end = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0, end))
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }
    fun setSpeed(speed: Float) {
        controller?.setPlaybackParameters(PlaybackParameters(speed.coerceIn(0.5f, 2f), 1f))
    }
    fun toggleCurrentLike(): Boolean {
        val mediaController = controller ?: return false
        mediaController.sendCustomCommand(toggleLikeCommand, Bundle.EMPTY)
        return true
    }
    fun next() { controller?.sendCustomCommand(nextCommand, Bundle.EMPTY) }
    fun previous() { controller?.seekToPreviousMediaItem() }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        _state.value = PlayerUiState(
            connected = true,
            mediaId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            artworkUrl = metadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            playbackSpeed = player.playbackParameters.speed,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem() || hasPersistedNext,
            isRemote = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
        )
    }

    override fun close() {
        scope.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
