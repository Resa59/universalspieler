package de.rdoe.weeklydjshows

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.load
import de.rdoe.weeklydjshows.playback.PlaybackService
import de.rdoe.weeklydjshows.playback.SESSION_ACTION_NEXT
import de.rdoe.weeklydjshows.playback.SESSION_ACTION_PREVIOUS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** Optional tiny overlay. Android still requires the playback service's media notification. */
class OverlayMiniPlayerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var artwork: ImageView
    private lateinit var dim: View
    private lateinit var toggle: ImageButton
    private lateinit var progress: View
    private lateinit var loading: ProgressBar
    private var squareControls: LinearLayout? = null
    private var closeButton: ImageButton? = null
    private var episodeTitle: TextView? = null
    private var showTitle: TextView? = null
    private var previous: ImageButton? = null
    private var next: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    private var controller: MediaController? = null
    private var lastArtwork: String? = null
    private var activeLayout = OverlayLayout.SQUARE_COVER
    private var squareControlsVisible = false
    private var squareHideScheduled = false
    private var hasPersistedNext = false
    private val positionPrefs: SharedPreferences by lazy {
        getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
    }
    private val hideSquareControls = Runnable {
        squareHideScheduled = false
        if (controller?.isPlaying == true) setSquareControlsVisible(false)
    }
    private val refresh = object : Runnable {
        override fun run() {
            updatePlayerUi()
            root.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        createOverlay()
        connectController()
        serviceScope.launch {
            AppGraph.database.queueDao().observe().collectLatest { queue ->
                hasPersistedNext = queue.isNotEmpty()
                updatePlayerUi()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::root.isInitialized) {
            val requestedLayout = AppSettings.read(this).overlayLayout
            if (requestedLayout == activeLayout) {
                resize()
            } else {
                savePosition()
                root.removeCallbacks(refresh)
                root.removeCallbacks(hideSquareControls)
                runCatching { windowManager.removeView(root) }
                createOverlay()
                updatePlayerUi()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::root.isInitialized) {
            savePosition()
            root.removeCallbacks(refresh)
            root.removeCallbacks(hideSquareControls)
            runCatching { windowManager.removeView(root) }
        }
        controller?.release()
        controller = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createOverlay() {
        val settings = AppSettings.read(this)
        activeLayout = settings.overlayLayout
        val height = sizePx(settings)
        val width = widthPx(settings)
        root = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(7, 28, 44))
                cornerRadius = dp(12).toFloat()
            }
        }
        episodeTitle = null
        showTitle = null
        previous = null
        next = null
        squareControls = null
        closeButton = null
        squareControlsVisible = false
        if (activeLayout == OverlayLayout.WIDE_CARD) createWideCard(height) else createSquareCover(height)

        progress = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(progress, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        installDragGesture()
        params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX() ?: (resources.displayMetrics.widthPixels - width - dp(8)).coerceAtLeast(0)
            y = savedY() ?: dp(90)
            clampPosition(this)
        }
        windowManager.addView(root, params)
        root.post(refresh)
    }

    private fun createSquareCover(size: Int) {
        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Aktuelle Folge"
        }
        dim = View(this).apply {
            setBackgroundColor(Color.argb(118, 0, 0, 0))
            visibility = View.GONE
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val rewind = playerButton(R.drawable.ic_pip_replay_10, "10 Sekunden zurück") {
            seekRelative(-10_000L)
            scheduleSquareControlsHide()
        }
        toggle = playerButton(R.drawable.ic_pip_play, "Wiedergabe umschalten") {
            togglePlayback()
        }
        val forward = playerButton(R.drawable.ic_pip_forward_30, "30 Sekunden vor") {
            seekRelative(30_000L)
            scheduleSquareControlsHide()
        }
        val controlHeight = min(dp(48), (size * 0.58f).toInt())
        listOf(rewind, toggle, forward).forEach { control ->
            controls.addView(control, LinearLayout.LayoutParams(0, controlHeight, 1f))
        }
        squareControls = controls
        loading = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        val close = playerButton(R.drawable.ic_overlay_close, "Mini-Player schließen") { stopSelf() }.apply {
            visibility = View.GONE
        }
        closeButton = close

        root.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, FrameLayout.LayoutParams(-1, controlHeight, Gravity.CENTER))
        root.addView(loading, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        root.addView(close, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
    }

    private fun createWideCard(height: Int) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val coverFrame = FrameLayout(this)
        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Cover der aktuellen Folge"
        }
        dim = View(this).apply { setBackgroundColor(Color.argb(112, 0, 0, 0)) }
        coverFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        coverFrame.addView(dim, FrameLayout.LayoutParams(-1, -1))
        body.addView(coverFrame, LinearLayout.LayoutParams(height, height - dp(3)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(4), dp(5))
        }
        episodeTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Keine Folge ausgewählt"
        }
        showTitle = TextView(this).apply {
            setTextColor(Color.rgb(179, 207, 221))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(episodeTitle, LinearLayout.LayoutParams(-1, 0, 1f))
        details.addView(showTitle, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previousButton = playerButton(R.drawable.ic_pip_previous, "Vorherige Folge") {
            sendSessionCommand(SESSION_ACTION_PREVIOUS)
        }
        previous = previousButton
        val rewind = playerButton(R.drawable.ic_pip_replay_10, "10 Sekunden zurück") {
            seekRelative(-10_000L)
        }
        toggle = playerButton(R.drawable.ic_pip_play, "Wiedergabe umschalten") { togglePlayback() }
        val forward = playerButton(R.drawable.ic_pip_forward_30, "30 Sekunden vor") {
            seekRelative(30_000L)
        }
        val nextButton = playerButton(R.drawable.ic_pip_next, "Nächste Folge") {
            sendSessionCommand(SESSION_ACTION_NEXT)
        }
        next = nextButton
        val toggleSlot = FrameLayout(this).apply {
            addView(toggle, FrameLayout.LayoutParams(-1, -1))
        }
        loading = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        toggleSlot.addView(loading, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        listOf<View>(previousButton, rewind, toggleSlot, forward, nextButton).forEach { control ->
            controls.addView(control, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        details.addView(controls, LinearLayout.LayoutParams(-1, dp(36)))
        body.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(3) })

        val close = playerButton(R.drawable.ic_overlay_close, "Mini-Player schließen") { stopSelf() }
        closeButton = close
        root.addView(close, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
    }

    private fun playerButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        setPadding(dp(3), dp(3), dp(3), dp(3))
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun togglePlayback() {
        controller?.let { player ->
            if (player.isPlaying) {
                player.pause()
                setSquareControlsVisible(true)
            } else {
                player.prepare()
                player.play()
                scheduleSquareControlsHide()
            }
        }
    }

    private fun seekRelative(deltaMs: Long) {
        controller?.let { player ->
            val rawTarget = (player.currentPosition + deltaMs).coerceAtLeast(0L)
            val target = player.duration.takeIf { it > 0L }?.let { rawTarget.coerceAtMost(it) } ?: rawTarget
            player.seekTo(target)
        }
    }

    private fun sendSessionCommand(action: String) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun setSquareControlsVisible(visible: Boolean) {
        if (activeLayout != OverlayLayout.SQUARE_COVER) return
        squareControlsVisible = visible
        squareControls?.visibility = if (visible) View.VISIBLE else View.GONE
        closeButton?.visibility = if (visible) View.VISIBLE else View.GONE
        dim.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            root.removeCallbacks(hideSquareControls)
            squareHideScheduled = false
        }
    }

    private fun scheduleSquareControlsHide() {
        if (activeLayout != OverlayLayout.SQUARE_COVER) return
        setSquareControlsVisible(true)
        root.removeCallbacks(hideSquareControls)
        squareHideScheduled = true
        root.postDelayed(hideSquareControls, CONTROLS_TIMEOUT_MS)
    }

    private fun installDragGesture() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        root.setOnClickListener {
            if (activeLayout == OverlayLayout.SQUARE_COVER && !squareControlsVisible) {
                scheduleSquareControlsHide()
            } else {
                openMainApp()
            }
        }
        root.setOnTouchListener { _, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    moved = moved || abs(dx) > dp(5) || abs(dy) > dp(5)
                    if (moved) {
                        layout.x = startX + dx.toInt()
                        layout.y = startY + dy.toInt()
                        clampPosition(layout)
                        windowManager.updateViewLayout(root, layout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition()
                    } else {
                        root.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                updatePlayerUi()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updatePlayerUi() {
        if (!::root.isInitialized) return
        val player = controller ?: return
        val art = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
        if (art != lastArtwork) {
            lastArtwork = art
            artwork.load(art) { crossfade(false) }
        }
        if (activeLayout == OverlayLayout.SQUARE_COVER) {
            if (!player.isPlaying) {
                setSquareControlsVisible(true)
                root.removeCallbacks(hideSquareControls)
                squareHideScheduled = false
            } else if (squareControlsVisible) {
                // Keep controls long enough to complete an interaction, then reveal the cover.
                if (!squareHideScheduled) {
                    squareHideScheduled = true
                    root.postDelayed(hideSquareControls, CONTROLS_TIMEOUT_MS)
                }
            } else {
                dim.visibility = View.GONE
            }
        } else {
            dim.visibility = if (player.isPlaying) View.GONE else View.VISIBLE
        }
        toggle.setImageResource(if (player.isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
        loading.visibility = if (AppSyncStatus.running.value) View.VISIBLE else View.GONE
        episodeTitle?.text = player.currentMediaItem?.mediaMetadata?.title ?: "Keine Folge ausgewählt"
        showTitle?.text = player.currentMediaItem?.mediaMetadata?.albumTitle
            ?: player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
        previous?.apply {
            isEnabled = player.hasPreviousMediaItem()
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        next?.apply {
            isEnabled = player.hasNextMediaItem() || hasPersistedNext
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        val ratio = player.duration.takeIf { it > 0L }?.let {
            (player.currentPosition.toFloat() / it.toFloat()).coerceIn(0f, 1f)
        } ?: 0f
        progress.layoutParams = (progress.layoutParams as FrameLayout.LayoutParams).apply {
            width = ((params?.width ?: widthPx()) * ratio).toInt()
        }
    }

    private fun resize() {
        val layout = params ?: return
        layout.width = widthPx()
        layout.height = sizePx()
        clampPosition(layout)
        windowManager.updateViewLayout(root, layout)
        savePosition()
    }

    private fun clampPosition(layout: WindowManager.LayoutParams) {
        val display = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, (display.widthPixels - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (display.heightPixels - layout.height).coerceAtLeast(0))
    }

    private fun savePosition() {
        val layout = params ?: return
        positionPrefs.edit()
            .putInt(positionKey("x"), layout.x)
            .putInt(positionKey("y"), layout.y)
            .apply()
    }

    private fun savedX(): Int? = positionPrefs.takeIf { it.contains(positionKey("x")) }
        ?.getInt(positionKey("x"), 0)

    private fun savedY(): Int? = positionPrefs.takeIf { it.contains(positionKey("y")) }
        ?.getInt(positionKey("y"), 0)

    private fun positionKey(axis: String): String = "${activeLayout.name.lowercase()}_$axis"

    private fun sizePx(settings: AppSettingsState = AppSettings.read(this)): Int = dp(settings.overlaySize.sizeDp)

    private fun widthPx(settings: AppSettingsState = AppSettings.read(this)): Int {
        if (settings.overlayLayout == OverlayLayout.SQUARE_COVER) return sizePx(settings)
        val requested = dp(when (settings.overlaySize) {
            OverlaySize.TINY -> 300
            OverlaySize.SMALL -> 360
            OverlaySize.MEDIUM -> 420
        })
        val available = (resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(1)
        return min(requested, available)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val POSITION_PREFS = "overlay_mini_player_position"
        private const val CONTROLS_TIMEOUT_MS = 3_500L
        private const val DISABLED_ALPHA = 0.3f

        fun show(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            context.startService(Intent(context, OverlayMiniPlayerService::class.java))
            return true
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, OverlayMiniPlayerService::class.java))
        }
    }
}
