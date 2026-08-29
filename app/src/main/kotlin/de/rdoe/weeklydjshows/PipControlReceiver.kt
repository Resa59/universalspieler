package de.rdoe.weeklydjshows

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import de.rdoe.weeklydjshows.playback.PlaybackService
import de.rdoe.weeklydjshows.playback.SESSION_ACTION_NEXT

/** Executes the three native PiP actions exposed after the user taps the square mini player. */
class PipControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync()
        future.addListener({
            var controller: MediaController? = null
            try {
                controller = future.get()
                when (intent.action) {
                    ACTION_SEEK_BACK -> controller.seekBack()
                    ACTION_SEEK_FORWARD -> controller.seekForward()
                    ACTION_TOGGLE_PLAY_PAUSE -> if (controller.playWhenReady) controller.pause() else controller.play()
                    ACTION_PREVIOUS -> if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
                    ACTION_NEXT -> controller.sendCustomCommand(
                        SessionCommand(SESSION_ACTION_NEXT, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                }
            } catch (_: Throwable) {
                // The PiP window can be closed while an action is in flight.
            } finally {
                controller?.release()
                pendingResult.finish()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    companion object {
        const val ACTION_SEEK_BACK = "de.rdoe.weeklydjshows.action.PIP_SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "de.rdoe.weeklydjshows.action.PIP_SEEK_FORWARD"
        const val ACTION_TOGGLE_PLAY_PAUSE = "de.rdoe.weeklydjshows.action.PIP_TOGGLE_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "de.rdoe.weeklydjshows.action.PIP_PREVIOUS"
        const val ACTION_NEXT = "de.rdoe.weeklydjshows.action.PIP_NEXT"
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_SEEK_BACK,
            ACTION_SEEK_FORWARD,
            ACTION_TOGGLE_PLAY_PAUSE,
            ACTION_PREVIOUS,
            ACTION_NEXT,
        )
    }
}
