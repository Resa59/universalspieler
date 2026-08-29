package de.rdoe.weeklydjshows

import android.content.Context
import de.rdoe.weeklydjshows.database.PodcastCategory
import de.rdoe.weeklydjshows.model.StreamingPreferenceKeys
import de.rdoe.weeklydjshows.model.StreamingQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every user-facing preference lives in one typed snapshot. Keeping this exhaustive is important:
 * the full backup can restore behaviour as well as catalogue/data state.
 */
data class AppSettingsState(
    val wifiQuality: StreamingQuality = StreamingQuality.HIGH,
    val mobileQuality: StreamingQuality = StreamingQuality.MEDIUM,
    val downloadQuality: StreamingQuality = StreamingQuality.MAXIMUM,
    val showOrderMode: ShowOrderMode = ShowOrderMode.CUSTOM,
    val bluetoothAutoOpenDevices: Set<String> = emptySet(),
    val bluetoothAutoResumeDevices: Set<String> = emptySet(),
    val miniPlayerControls: MiniPlayerControls = MiniPlayerControls.SEEK,
    val startupScreen: StartupScreen = StartupScreen.LATEST,
    val wordPodcastsEnabled: Boolean = true,
    val musicPodcastsEnabled: Boolean = true,
    val wordPodcastsInLatest: Boolean = true,
    val musicPodcastsInLatest: Boolean = true,
    val hideScheduledFromLatest: Boolean = false,
    val refreshOnColdStart: Boolean = true,
    val exitAfterIdle: Boolean = true,
    val resumeOfferEnabled: Boolean = true,
    val miniPlayerImplementation: MiniPlayerImplementation = MiniPlayerImplementation.SYSTEM_PIP,
    val overlaySize: OverlaySize = OverlaySize.SMALL,
    val overlayLayout: OverlayLayout = OverlayLayout.SQUARE_COVER,
    val autoMiniPlayerOnBackground: Boolean = false,
    val bluetoothLaunchMode: BluetoothLaunchMode = BluetoothLaunchMode.FULL_APP,
    val bluetoothAutoplayMode: BluetoothAutoplayMode = BluetoothAutoplayMode.ACTIVE_ONLY,
    val bluetoothDisplayMode: BluetoothDisplayMode = BluetoothDisplayMode.OFFER,
    val autostartOfferMode: AutostartOfferMode = AutostartOfferMode.INTERRUPTED_THEN_SELECTED,
    val autostartShowId: String? = null,
    val appUpdateChecksEnabled: Boolean = true,
    val newPipeChecksEnabled: Boolean = false,
)

enum class ShowOrderMode { CUSTOM, ALPHABETICAL }
enum class MiniPlayerControls { SEEK, EPISODES }
enum class StartupScreen { LATEST, SHOWS }
enum class MiniPlayerImplementation { SYSTEM_PIP, CUSTOM_OVERLAY }
enum class OverlaySize(val sizeDp: Int) { TINY(82), SMALL(108), MEDIUM(148) }
enum class OverlayLayout { SQUARE_COVER, WIDE_CARD }
enum class BluetoothLaunchMode { FULL_APP, MINI_PLAYER }

/** Playback selection is independent from what the foreground UI displays. */
enum class BluetoothAutoplayMode {
    OFF,
    ACTIVE_ONLY,
    RESTORE_INTERRUPTED,
    QUEUE_THEN_SELECTED,
    SELECTED_LATEST,
}

enum class BluetoothDisplayMode { START_SCREEN, OFFER, PLAYER_IF_AVAILABLE }
enum class AutostartOfferMode { INTERRUPTED_ONLY, INTERRUPTED_THEN_SELECTED, SELECTED_LATEST }

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = preferences(appContext)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    fun setWifiQuality(value: StreamingQuality) = change(
        mutate = { putString(StreamingPreferenceKeys.WIFI_QUALITY, value.name) },
        state = { copy(wifiQuality = value) },
    )

    fun setMobileQuality(value: StreamingQuality) = change(
        mutate = { putString(StreamingPreferenceKeys.MOBILE_QUALITY, value.name) },
        state = { copy(mobileQuality = value) },
    )

    fun setDownloadQuality(value: StreamingQuality) = change(
        mutate = { putString(StreamingPreferenceKeys.DOWNLOAD_QUALITY, value.name) },
        state = { copy(downloadQuality = value) },
    )

    fun setShowOrderMode(value: ShowOrderMode) = enumChange(SHOW_ORDER_MODE, value) { copy(showOrderMode = value) }
    fun setMiniPlayerControls(value: MiniPlayerControls) = enumChange(MINI_PLAYER_CONTROLS, value) { copy(miniPlayerControls = value) }
    fun setStartupScreen(value: StartupScreen) = enumChange(STARTUP_SCREEN, value) { copy(startupScreen = value) }
    fun setMiniPlayerImplementation(value: MiniPlayerImplementation) = enumChange(MINI_PLAYER_IMPLEMENTATION, value) { copy(miniPlayerImplementation = value) }
    fun setOverlaySize(value: OverlaySize) = enumChange(OVERLAY_SIZE, value) { copy(overlaySize = value) }
    fun setOverlayLayout(value: OverlayLayout) = enumChange(OVERLAY_LAYOUT, value) { copy(overlayLayout = value) }
    fun setBluetoothLaunchMode(value: BluetoothLaunchMode) = enumChange(BLUETOOTH_LAUNCH_MODE, value) { copy(bluetoothLaunchMode = value) }
    fun setBluetoothAutoplayMode(value: BluetoothAutoplayMode) = enumChange(BLUETOOTH_AUTOPLAY_MODE, value) { copy(bluetoothAutoplayMode = value) }
    fun setBluetoothDisplayMode(value: BluetoothDisplayMode) = enumChange(BLUETOOTH_DISPLAY_MODE, value) { copy(bluetoothDisplayMode = value) }
    fun setAutostartOfferMode(value: AutostartOfferMode) = enumChange(AUTOSTART_OFFER_MODE, value) { copy(autostartOfferMode = value) }

    fun setWordPodcastsEnabled(value: Boolean) = booleanChange(WORD_PODCASTS_ENABLED, value) { copy(wordPodcastsEnabled = value) }
    fun setMusicPodcastsEnabled(value: Boolean) = booleanChange(MUSIC_PODCASTS_ENABLED, value) { copy(musicPodcastsEnabled = value) }
    fun setWordPodcastsInLatest(value: Boolean) = booleanChange(WORD_PODCASTS_IN_LATEST, value) { copy(wordPodcastsInLatest = value) }
    fun setMusicPodcastsInLatest(value: Boolean) = booleanChange(MUSIC_PODCASTS_IN_LATEST, value) { copy(musicPodcastsInLatest = value) }
    fun setHideScheduledFromLatest(value: Boolean) = booleanChange(HIDE_SCHEDULED_FROM_LATEST, value) { copy(hideScheduledFromLatest = value) }
    fun setRefreshOnColdStart(value: Boolean) {
        booleanChange(REFRESH_ON_COLD_START, value) { copy(refreshOnColdStart = value) }
        if (value) AppSyncScheduler.schedule(appContext) else AppSyncScheduler.cancel(appContext)
    }
    fun setExitAfterIdle(value: Boolean) = booleanChange(EXIT_AFTER_IDLE, value) { copy(exitAfterIdle = value) }
    fun setResumeOfferEnabled(value: Boolean) = booleanChange(RESUME_OFFER_ENABLED, value) { copy(resumeOfferEnabled = value) }
    fun setAutoMiniPlayerOnBackground(value: Boolean) = booleanChange(AUTO_MINI_PLAYER_ON_BACKGROUND, value) { copy(autoMiniPlayerOnBackground = value) }
    fun setAppUpdateChecksEnabled(value: Boolean) = booleanChange(APP_UPDATE_CHECKS_ENABLED, value) { copy(appUpdateChecksEnabled = value) }
    fun setNewPipeChecksEnabled(value: Boolean) = booleanChange(NEWPIPE_CHECKS_ENABLED, value) { copy(newPipeChecksEnabled = value) }

    fun setAutostartShowId(value: String?) = change(
        mutate = {
            if (value.isNullOrBlank()) remove(AUTOSTART_SHOW_ID) else putString(AUTOSTART_SHOW_ID, value)
        },
        state = { copy(autostartShowId = value?.takeIf { it.isNotBlank() }) },
    )

    fun setBluetoothAutoOpenDevice(address: String, enabled: Boolean) {
        val updated = _state.value.bluetoothAutoOpenDevices.updated(address, enabled)
        change(
            mutate = { putStringSet(BLUETOOTH_AUTO_OPEN_DEVICES, updated) },
            state = { copy(bluetoothAutoOpenDevices = updated) },
        )
        syncBluetoothReceiverEnabled()
    }

    fun setBluetoothAutoResumeDevice(address: String, enabled: Boolean) {
        val updated = _state.value.bluetoothAutoResumeDevices.updated(address, enabled)
        change(
            mutate = { putStringSet(BLUETOOTH_AUTO_RESUME_DEVICES, updated) },
            state = { copy(bluetoothAutoResumeDevices = updated) },
        )
        syncBluetoothReceiverEnabled()
    }

    /** Atomically replaces every exported preference during a full-backup import. */
    fun replaceAll(value: AppSettingsState) {
        prefs.edit()
            .putString(StreamingPreferenceKeys.WIFI_QUALITY, value.wifiQuality.name)
            .putString(StreamingPreferenceKeys.MOBILE_QUALITY, value.mobileQuality.name)
            .putString(StreamingPreferenceKeys.DOWNLOAD_QUALITY, value.downloadQuality.name)
            .putString(SHOW_ORDER_MODE, value.showOrderMode.name)
            .putStringSet(BLUETOOTH_AUTO_OPEN_DEVICES, value.bluetoothAutoOpenDevices)
            .putStringSet(BLUETOOTH_AUTO_RESUME_DEVICES, value.bluetoothAutoResumeDevices)
            .putString(MINI_PLAYER_CONTROLS, value.miniPlayerControls.name)
            .putString(STARTUP_SCREEN, value.startupScreen.name)
            .putBoolean(WORD_PODCASTS_ENABLED, value.wordPodcastsEnabled)
            .putBoolean(MUSIC_PODCASTS_ENABLED, value.musicPodcastsEnabled)
            .putBoolean(WORD_PODCASTS_IN_LATEST, value.wordPodcastsInLatest)
            .putBoolean(MUSIC_PODCASTS_IN_LATEST, value.musicPodcastsInLatest)
            .putBoolean(HIDE_SCHEDULED_FROM_LATEST, value.hideScheduledFromLatest)
            .putBoolean(REFRESH_ON_COLD_START, value.refreshOnColdStart)
            .putBoolean(EXIT_AFTER_IDLE, value.exitAfterIdle)
            .putBoolean(RESUME_OFFER_ENABLED, value.resumeOfferEnabled)
            .putString(MINI_PLAYER_IMPLEMENTATION, value.miniPlayerImplementation.name)
            .putString(OVERLAY_SIZE, value.overlaySize.name)
            .putString(OVERLAY_LAYOUT, value.overlayLayout.name)
            .putBoolean(AUTO_MINI_PLAYER_ON_BACKGROUND, value.autoMiniPlayerOnBackground)
            .putString(BLUETOOTH_LAUNCH_MODE, value.bluetoothLaunchMode.name)
            .putString(BLUETOOTH_AUTOPLAY_MODE, value.bluetoothAutoplayMode.name)
            .putString(BLUETOOTH_DISPLAY_MODE, value.bluetoothDisplayMode.name)
            .putString(AUTOSTART_OFFER_MODE, value.autostartOfferMode.name)
            .putBoolean(APP_UPDATE_CHECKS_ENABLED, value.appUpdateChecksEnabled)
            .putBoolean(NEWPIPE_CHECKS_ENABLED, value.newPipeChecksEnabled)
            .apply {
                if (value.autostartShowId.isNullOrBlank()) remove(AUTOSTART_SHOW_ID)
                else putString(AUTOSTART_SHOW_ID, value.autostartShowId)
            }
            .commit()
        _state.value = read()
        syncBluetoothReceiverEnabled()
    }

    private fun syncBluetoothReceiverEnabled() = BluetoothConnectionReceiver.setEnabled(appContext, true)

    private fun read(): AppSettingsState = read(appContext)

    private fun booleanChange(
        key: String,
        value: Boolean,
        state: AppSettingsState.() -> AppSettingsState,
    ) = change({ putBoolean(key, value) }, state)

    private fun <T : Enum<T>> enumChange(
        key: String,
        value: T,
        state: AppSettingsState.() -> AppSettingsState,
    ) = change({ putString(key, value.name) }, state)

    private fun change(
        mutate: android.content.SharedPreferences.Editor.() -> android.content.SharedPreferences.Editor,
        state: AppSettingsState.() -> AppSettingsState,
    ) {
        val editor = prefs.edit()
        editor.mutate()
        editor.apply()
        _state.value = _state.value.state()
    }

    internal companion object {
        const val SHOW_ORDER_MODE = "show_order_mode"
        const val BLUETOOTH_AUTO_OPEN_DEVICES = "bluetooth_auto_open_devices"
        const val BLUETOOTH_AUTO_RESUME_DEVICES = "bluetooth_auto_resume_devices"
        const val MINI_PLAYER_CONTROLS = "mini_player_controls"
        const val STARTUP_SCREEN = "startup_screen"
        const val WORD_PODCASTS_ENABLED = "word_podcasts_enabled"
        const val MUSIC_PODCASTS_ENABLED = "music_podcasts_enabled"
        const val WORD_PODCASTS_IN_LATEST = "word_podcasts_in_latest"
        const val MUSIC_PODCASTS_IN_LATEST = "music_podcasts_in_latest"
        const val HIDE_SCHEDULED_FROM_LATEST = "hide_scheduled_from_latest"
        const val REFRESH_ON_COLD_START = "refresh_on_cold_start"
        const val EXIT_AFTER_IDLE = "exit_after_idle"
        const val RESUME_OFFER_ENABLED = "resume_offer_enabled"
        const val MINI_PLAYER_IMPLEMENTATION = "mini_player_implementation"
        const val OVERLAY_SIZE = "overlay_size"
        const val OVERLAY_LAYOUT = "overlay_layout"
        const val AUTO_MINI_PLAYER_ON_BACKGROUND = "auto_mini_player_on_background"
        const val BLUETOOTH_LAUNCH_MODE = "bluetooth_launch_mode"
        const val BLUETOOTH_AUTOPLAY_MODE = "bluetooth_autoplay_mode"
        const val BLUETOOTH_DISPLAY_MODE = "bluetooth_display_mode"
        const val AUTOSTART_OFFER_MODE = "autostart_offer_mode"
        const val AUTOSTART_SHOW_ID = "autostart_show_id"
        const val APP_UPDATE_CHECKS_ENABLED = "app_update_checks_enabled"
        const val NEWPIPE_CHECKS_ENABLED = "newpipe_checks_enabled"

        fun read(context: Context): AppSettingsState {
            val prefs = preferences(context)
            fun quality(key: String, fallback: String): StreamingQuality = runCatching {
                StreamingQuality.valueOf(prefs.getString(key, fallback) ?: fallback)
            }.getOrDefault(StreamingQuality.MEDIUM)
            return AppSettingsState(
                wifiQuality = quality(StreamingPreferenceKeys.WIFI_QUALITY, StreamingPreferenceKeys.DEFAULT_WIFI),
                mobileQuality = quality(StreamingPreferenceKeys.MOBILE_QUALITY, StreamingPreferenceKeys.DEFAULT_MOBILE),
                downloadQuality = quality(StreamingPreferenceKeys.DOWNLOAD_QUALITY, StreamingPreferenceKeys.DEFAULT_DOWNLOAD),
                showOrderMode = enumPreference(prefs, SHOW_ORDER_MODE, ShowOrderMode.CUSTOM),
                bluetoothAutoOpenDevices = prefs.getStringSet(BLUETOOTH_AUTO_OPEN_DEVICES, emptySet()).orEmpty().toSet(),
                bluetoothAutoResumeDevices = prefs.getStringSet(BLUETOOTH_AUTO_RESUME_DEVICES, emptySet()).orEmpty().toSet(),
                miniPlayerControls = enumPreference(prefs, MINI_PLAYER_CONTROLS, MiniPlayerControls.SEEK),
                startupScreen = enumPreference(prefs, STARTUP_SCREEN, StartupScreen.LATEST),
                wordPodcastsEnabled = prefs.getBoolean(WORD_PODCASTS_ENABLED, true),
                musicPodcastsEnabled = prefs.getBoolean(MUSIC_PODCASTS_ENABLED, true),
                wordPodcastsInLatest = prefs.getBoolean(WORD_PODCASTS_IN_LATEST, true),
                musicPodcastsInLatest = prefs.getBoolean(MUSIC_PODCASTS_IN_LATEST, true),
                hideScheduledFromLatest = prefs.getBoolean(HIDE_SCHEDULED_FROM_LATEST, false),
                refreshOnColdStart = prefs.getBoolean(REFRESH_ON_COLD_START, true),
                exitAfterIdle = prefs.getBoolean(EXIT_AFTER_IDLE, true),
                resumeOfferEnabled = prefs.getBoolean(RESUME_OFFER_ENABLED, true),
                miniPlayerImplementation = enumPreference(prefs, MINI_PLAYER_IMPLEMENTATION, MiniPlayerImplementation.SYSTEM_PIP),
                overlaySize = enumPreference(prefs, OVERLAY_SIZE, OverlaySize.SMALL),
                overlayLayout = enumPreference(prefs, OVERLAY_LAYOUT, OverlayLayout.SQUARE_COVER),
                autoMiniPlayerOnBackground = prefs.getBoolean(AUTO_MINI_PLAYER_ON_BACKGROUND, false),
                bluetoothLaunchMode = enumPreference(prefs, BLUETOOTH_LAUNCH_MODE, BluetoothLaunchMode.FULL_APP),
                bluetoothAutoplayMode = enumPreference(prefs, BLUETOOTH_AUTOPLAY_MODE, BluetoothAutoplayMode.ACTIVE_ONLY),
                bluetoothDisplayMode = enumPreference(prefs, BLUETOOTH_DISPLAY_MODE, BluetoothDisplayMode.OFFER),
                autostartOfferMode = enumPreference(prefs, AUTOSTART_OFFER_MODE, AutostartOfferMode.INTERRUPTED_THEN_SELECTED),
                autostartShowId = prefs.getString(AUTOSTART_SHOW_ID, null)?.takeIf { it.isNotBlank() },
                appUpdateChecksEnabled = prefs.getBoolean(APP_UPDATE_CHECKS_ENABLED, true),
                newPipeChecksEnabled = prefs.getBoolean(NEWPIPE_CHECKS_ENABLED, false),
            )
        }

        fun bluetoothAutoOpenDevices(context: Context): Set<String> = read(context).bluetoothAutoOpenDevices
        fun bluetoothAutoResumeDevices(context: Context): Set<String> = read(context).bluetoothAutoResumeDevices

        fun refreshCategories(context: Context): Set<PodcastCategory> = buildSet {
            val value = read(context)
            if (value.wordPodcastsEnabled) add(PodcastCategory.WORD)
            if (value.musicPodcastsEnabled) add(PodcastCategory.MUSIC)
        }

        private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
            StreamingPreferenceKeys.FILE,
            Context.MODE_PRIVATE,
        )

        private inline fun <reified T : Enum<T>> enumPreference(
            prefs: android.content.SharedPreferences,
            key: String,
            fallback: T,
        ): T = runCatching {
            enumValueOf<T>(prefs.getString(key, fallback.name) ?: fallback.name)
        }.getOrDefault(fallback)

        private fun Set<String>.updated(value: String, enabled: Boolean): Set<String> = toMutableSet().apply {
            if (enabled) add(value) else remove(value)
        }.toSet()
    }
}
