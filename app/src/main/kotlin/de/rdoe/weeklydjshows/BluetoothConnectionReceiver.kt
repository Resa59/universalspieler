package de.rdoe.weeklydjshows

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.rdoe.weeklydjshows.playback.PlaybackService
import de.rdoe.weeklydjshows.playback.PlaybackStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Learns Bluetooth recency and executes only automation rules explicitly selected by the user. */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return

        val device = bluetoothDevice(intent) ?: return
        val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val deviceName = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unbenanntes Gerät" }
        BluetoothDeviceHistory.recordConnection(context, address, deviceName)
        val shouldOpen = address in AppSettings.bluetoothAutoOpenDevices(context)
        val shouldResume = address in AppSettings.bluetoothAutoResumeDevices(context)
        if (!shouldOpen && !shouldResume) return

        if (shouldOpen) {
            // Keep the notification only as a last-resort escape hatch. The actual launch below
            // uses Android 14/15's explicit PendingIntent BAL opt-ins on both sender and creator.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) postOpenFallback(context, device)
            openAppFromBluetooth(context)
        }

        if (shouldResume) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            val mode = AppSettings.read(appContext).bluetoothAutoplayMode
            if (mode == BluetoothAutoplayMode.ACTIVE_ONLY && isPlaybackServiceRunning(context)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { resumePausedPlayback(appContext, pendingResult) },
                    AUDIO_ROUTE_GRACE_MS,
                )
            } else {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        delay(AUDIO_ROUTE_GRACE_MS)
                        restorePlayback(appContext, mode)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDevice(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    @Suppress("DEPRECATION")
    private fun isPlaybackServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val serviceName = PlaybackService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceName }
    }

    private fun resumePausedPlayback(context: Context, pendingResult: PendingResult) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            var controller: MediaController? = null
            try {
                controller = future.get()
                val hasActiveItem = controller.currentMediaItem != null &&
                    controller.playbackState != Player.STATE_IDLE &&
                    controller.playbackState != Player.STATE_ENDED
                if (hasActiveItem && !controller.playWhenReady) controller.play()
            } catch (_: Throwable) {
                // A Bluetooth rule is a convenience. Playback stays untouched if the existing
                // media session disappears while the connection event is being handled.
            } finally {
                controller?.release()
                pendingResult.finish()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun restorePlayback(context: Context, mode: BluetoothAutoplayMode) {
        if (mode == BluetoothAutoplayMode.OFF || mode == BluetoothAutoplayMode.ACTIVE_ONLY) return
        val settings = AppSettings.read(context)
        val episodeDao = AppGraph.database.episodeDao()
        val showDao = AppGraph.database.showDao()
        if (mode == BluetoothAutoplayMode.QUEUE_THEN_SELECTED &&
            withContext(Dispatchers.Main.immediate) { AppGraph.playback.playNextQueueItemAtTurn() }
        ) {
            return
        }
        val candidates = when (mode) {
            BluetoothAutoplayMode.RESTORE_INTERRUPTED -> listOfNotNull(episodeDao.getLastResumable()?.episode?.id)
            BluetoothAutoplayMode.QUEUE_THEN_SELECTED,
            BluetoothAutoplayMode.SELECTED_LATEST -> listOfNotNull(
                selectedLatestEpisodeId(settings, showDao, episodeDao),
            )
            BluetoothAutoplayMode.OFF, BluetoothAutoplayMode.ACTIVE_ONLY -> emptyList()
        }
        for (episodeId in candidates) {
            val result = withContext(Dispatchers.Main.immediate) { AppGraph.playback.play(episodeId) }
            if (result == PlaybackStartResult.Started) return
        }
    }

    private suspend fun selectedLatestEpisodeId(
        settings: AppSettingsState,
        showDao: de.rdoe.weeklydjshows.database.ShowDao,
        episodeDao: de.rdoe.weeklydjshows.database.EpisodeDao,
    ): String? {
        val selected = settings.autostartShowId
            ?.let { showDao.get(it) }
            ?.takeIf { it.isAutostartEpisodeSource() }
        val showId = selected?.id
            ?: showDao.getSubscribed().firstOrNull { it.isAutostartEpisodeSource() }?.id
            ?: return null
        return episodeDao.getLatestForShow(showId)?.episode?.id
    }

    private fun postOpenFallback(context: Context, device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    OPEN_CHANNEL_ID,
                    "Bluetooth-Autostart",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openIntent = PendingIntent.getActivity(
            context,
            OPEN_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_BLUETOOTH_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deviceName = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Bluetooth-Gerät" }
        val notification = NotificationCompat.Builder(context, OPEN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bluetooth)
            .setContentTitle("Weekly DJ Shows öffnen")
            .setContentText("$deviceName verbunden · Antippen, falls Android das automatische Öffnen blockiert hat.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(OPEN_NOTIFICATION_ID, notification) }
    }

    private fun openAppFromBluetooth(context: Context) {
        val attemptId = BluetoothAutostartDiagnostics.newAttempt(context)
        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_AUTO_OPEN)
            .putExtra(MainActivity.EXTRA_BLUETOOTH_AUTOSTART, true)
            .putExtra(MainActivity.EXTRA_BLUETOOTH_AUTOSTART_ATTEMPT_ID, attemptId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
                .toBundle()
        } else {
            null
        }
        val pendingIntent = if (creatorOptions != null) {
            PendingIntent.getActivity(
                context,
                AUTO_OPEN_REQUEST_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions,
            )
        } else {
            PendingIntent.getActivity(
                context,
                AUTO_OPEN_REQUEST_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val delivered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val senderOptions = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
                pendingIntent.send(senderOptions)
            } else {
                pendingIntent.send()
            }
        }.isSuccess
        if (delivered) BluetoothAutostartDiagnostics.markDispatched(context, attemptId)
    }

    companion object {
        private const val AUDIO_ROUTE_GRACE_MS = 1_500L
        private const val OPEN_CHANNEL_ID = "bluetooth_autostart"
        private const val OPEN_NOTIFICATION_ID = 12008
        private const val AUTO_OPEN_REQUEST_ID = 12009
        private const val ACTION_AUTO_OPEN = "de.rdoe.weeklydjshows.action.BLUETOOTH_AUTO_OPEN"

        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BluetoothConnectionReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        fun syncEnabled(context: Context) {
            // Keep the lightweight connection receiver available even with no active rule. This
            // is what lets the settings screen build its own "last used" order over time.
            setEnabled(context, true)
        }

        fun cancelOpenNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(OPEN_NOTIFICATION_ID)
        }
    }
}
