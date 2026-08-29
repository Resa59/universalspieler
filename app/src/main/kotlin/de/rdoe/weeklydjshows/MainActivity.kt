package de.rdoe.weeklydjshows

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.rdoe.weeklydjshows.uicomponents.WeeklyDjTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    var isInMiniPlayerMode by mutableStateOf(false)
        private set
    var bluetoothDisplayRequest by mutableStateOf<BluetoothDisplayMode?>(null)
        private set
    private var idleExitJob: Job? = null
    private var bluetoothPresentationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBluetoothAutostartWindowPolicy(intent)
        enableEdgeToEdge()
        setContent {
            WeeklyDjTheme { WeeklyDjShowsUi(viewModel) }
        }
        handleExternalIntent(intent)
        if (coldStartSyncScheduled.compareAndSet(false, true) && AppSettings.read(this).refreshOnColdStart) {
            AppSyncScheduler.initialSync(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyBluetoothAutostartWindowPolicy(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The overlay represents the background state. Its persisted coordinates are retained
        // by the service and restored when the app moves to the background again.
        OverlayMiniPlayerService.hide(this)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Do not leave ordinary app sessions visible above the lock screen. This window
            // policy is only for the explicit per-device Bluetooth autostart event.
            setShowWhenLocked(false)
        }
        super.onStop()
        startIdleExitCountdown()
    }

    override fun onResume() {
        super.onResume()
        idleExitJob?.cancel()
        idleExitJob = null
        BluetoothConnectionReceiver.cancelOpenNotification(this)
    }

    override fun onUserLeaveHint() {
        val settings = AppSettings.read(this)
        val player = viewModel.player.value
        if (settings.autoMiniPlayerOnBackground && player.isPlaying) {
            when (settings.miniPlayerImplementation) {
                MiniPlayerImplementation.CUSTOM_OVERLAY -> OverlayMiniPlayerService.show(this)
                MiniPlayerImplementation.SYSTEM_PIP -> enterMiniPlayer(
                    settings.miniPlayerControls,
                    player.isPlaying,
                    player.hasPrevious,
                    player.hasNext,
                )
            }
        }
        super.onUserLeaveHint()
    }

    private fun startIdleExitCountdown() {
        idleExitJob?.cancel()
        if (!AppSettings.read(this).exitAfterIdle || isInMiniPlayerMode) return
        idleExitJob = lifecycleScope.launch {
            viewModel.player
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collectLatest { playing ->
                    if (playing) return@collectLatest
                    delay(IDLE_EXIT_DELAY_MS)
                    if (!viewModel.player.value.isPlaying && !isInMiniPlayerMode) {
                        finishAndRemoveTask()
                    }
                }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInMiniPlayerMode = isInPictureInPictureMode
    }

    private fun applyBluetoothAutostartWindowPolicy(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BLUETOOTH_AUTOSTART, false) != true) return
        val settings = AppSettings.read(this)
        bluetoothDisplayRequest = settings.bluetoothDisplayMode
            .takeIf { settings.bluetoothLaunchMode == BluetoothLaunchMode.FULL_APP }
        BluetoothAutostartDiagnostics.markReached(
            this,
            intent.getLongExtra(EXTRA_BLUETOOTH_AUTOSTART_ATTEMPT_ID, 0L),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // The Bluetooth rule is an explicit user opt-in. Wake the display and make the
            // activity visible above the keyguard, but never dismiss or bypass the keyguard.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        bluetoothPresentationJob?.cancel()
        bluetoothPresentationJob = lifecycleScope.launch {
            if (AppSettings.read(this@MainActivity).bluetoothLaunchMode != BluetoothLaunchMode.MINI_PLAYER) {
                return@launch
            }
            val ready = viewModel.prepareBluetoothAutostartOffer()
            if (!ready) return@launch
            delay(250L)
            val settings = AppSettings.read(this@MainActivity)
            val player = viewModel.player.value
            when (settings.miniPlayerImplementation) {
                MiniPlayerImplementation.CUSTOM_OVERLAY -> {
                    if (OverlayMiniPlayerService.show(this@MainActivity)) moveTaskToBack(true)
                }
                MiniPlayerImplementation.SYSTEM_PIP -> enterMiniPlayer(
                    settings.miniPlayerControls,
                    player.isPlaying,
                    player.hasPrevious,
                    player.hasNext,
                )
            }
        }
    }

    fun consumeBluetoothDisplayRequest() {
        bluetoothDisplayRequest = null
    }

    fun enterMiniPlayer(
        controls: MiniPlayerControls,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return false
        }
        return enterMiniPlayerO(controls, isPlaying, hasPrevious, hasNext)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterMiniPlayerO(
        controls: MiniPlayerControls,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Boolean = enterPictureInPictureMode(
        miniPlayerParams(controls, isPlaying, hasPrevious, hasNext),
    )

    fun updateMiniPlayerActions(
        controls: MiniPlayerControls,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
        windowWidthDp: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) return
        setPictureInPictureParams(
            miniPlayerParams(
                controls = controls,
                isPlaying = isPlaying,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                windowWidthDp = windowWidthDp,
            ),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun miniPlayerParams(
        controls: MiniPlayerControls,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
        windowWidthDp: Int? = null,
    ): PictureInPictureParams {
        val seekBack = pipAction(
            action = PipControlReceiver.ACTION_SEEK_BACK,
            requestCode = 4101,
            iconRes = R.drawable.ic_pip_replay_10,
            title = "10 Sekunden zurück",
        )
        val seekForward = pipAction(
            action = PipControlReceiver.ACTION_SEEK_FORWARD,
            requestCode = 4102,
            iconRes = R.drawable.ic_pip_forward_30,
            title = "30 Sekunden vor",
        )
        val previous = pipAction(
            action = PipControlReceiver.ACTION_PREVIOUS,
            requestCode = 4104,
            iconRes = R.drawable.ic_pip_previous,
            title = "Vorherige Folge",
            enabled = hasPrevious,
        )
        val next = pipAction(
            action = PipControlReceiver.ACTION_NEXT,
            requestCode = 4105,
            iconRes = R.drawable.ic_pip_next,
            title = "Nächste Folge",
            enabled = hasNext,
        )
        val sideActions = if (controls == MiniPlayerControls.SEEK) {
            listOf(
                seekBack,
                seekForward,
            )
        } else {
            listOf(
                previous,
                next,
            )
        }
        val toggle = pipAction(
            action = PipControlReceiver.ACTION_TOGGLE_PLAY_PAUSE,
            requestCode = 4103,
            iconRes = if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            title = if (isPlaying) "Pause" else "Abspielen",
        )
        val canShowBothControlGroups = maxNumPictureInPictureActions >= 5 &&
            (windowWidthDp ?: 0) >= PIP_COMBINED_CONTROLS_MIN_WIDTH_DP
        val actions = if (canShowBothControlGroups) {
            listOf(previous, seekBack, toggle, seekForward, next)
        } else {
            listOf(sideActions[0], toggle, sideActions[1])
        }
        return PictureInPictureParams.Builder()
            // A single square aspect ratio avoids pretending the aspect-ratio selector controls
            // absolute PiP size. Android owns resizing and pinch-to-zoom on supported devices.
            .setAspectRatio(Rational(1, 1))
            .setActions(actions)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(false)
            }
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String,
        enabled: Boolean = true,
    ): RemoteAction {
        val intent = Intent(this, PipControlReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent,
        ).apply { isEnabled = enabled }
    }

    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { item ->
                item.text?.toString() ?: item.uri?.toString()
            }
        viewModel.handleSharedText(sharedText)
    }

    companion object {
        private const val IDLE_EXIT_DELAY_MS = 10 * 60 * 1000L
        private const val PIP_COMBINED_CONTROLS_MIN_WIDTH_DP = 150
        private val coldStartSyncScheduled = AtomicBoolean(false)
        const val EXTRA_BLUETOOTH_AUTOSTART = "de.rdoe.weeklydjshows.extra.BLUETOOTH_AUTOSTART"
        const val EXTRA_BLUETOOTH_AUTOSTART_ATTEMPT_ID =
            "de.rdoe.weeklydjshows.extra.BLUETOOTH_AUTOSTART_ATTEMPT_ID"
    }
}
