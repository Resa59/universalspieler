package de.rdoe.weeklydjshows

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.discovery.DiscoveryTask
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.feeds.FeedPreview
import de.rdoe.weeklydjshows.feeds.RefreshSummary
import de.rdoe.weeklydjshows.model.PlatformListing
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.ResolverError
import de.rdoe.weeklydjshows.model.ResolveResult
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.model.StreamingQuality
import de.rdoe.weeklydjshows.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

data class DiscoveryUiState(
    val loading: Boolean = false,
    val title: String = "Beliebte DJ- & Musikshows",
    val results: List<DiscoveryResult> = emptyList(),
    val providerStatuses: List<ProviderStatus> = emptyList(),
    val error: String? = null,
)

data class DiscoveryPreviewUiState(
    val loading: Boolean = false,
    val result: DiscoveryResult? = null,
    val availableTargets: List<IntegrationTarget> = emptyList(),
    val selectedTarget: IntegrationTarget? = null,
    val listing: PlatformListing? = null,
    val feedVerification: FeedVerification? = null,
    val feedPreview: FeedPreview? = null,
    val error: String? = null,
    val sharedImport: Boolean = false,
    val automaticallyAdded: Boolean = false,
)

data class PlaybackFailureUi(val episodeId: String, val error: ResolverError)
data class CategoryChoiceUi(val result: DiscoveryResult, val target: IntegrationTarget?)
enum class StartupOfferKind { INTERRUPTED, SELECTED_LATEST }
data class StartupOfferUi(val media: EpisodeWithShow, val kind: StartupOfferKind)
data class CatalogUpdateUi(
    val preview: BundledShowLayoutV128.CatalogUpdatePreview,
    val acceptedStandardOrder: Boolean? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppGraph.database
    private val showDao = db.showDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()
    private val historyDao = db.playbackHistoryDao()
    private val playerConnection = PlaybackConnection(application)
    private val appSettings = AppSettings(application)
    val settings = appSettings.state

    val shows = showDao.observeSubscribed()
    val hiddenLegacyShows = showDao.observeHiddenLegacy()
    val latest = settings.flatMapLatest { value ->
        episodeDao.observeLatest(
            includeWord = value.wordPodcastsInLatest && value.wordPodcastsEnabled,
            includeMusic = value.musicPodcastsInLatest && value.musicPodcastsEnabled,
            hideScheduled = value.hideScheduledFromLatest,
        )
    }
    val liked = episodeDao.observeLiked()
    val downloads = episodeDao.observeDownloads()
    val history = historyDao.observe()
    val queue = queueDao.observeDetailed()
    val queuedEpisodeIds = queueDao.observe()
        .map { entries -> entries.mapTo(linkedSetOf()) { it.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val player = playerConnection.state

    val localQuery = MutableStateFlow("")
    val localShowResults = localQuery
        .debounce(180)
        .flatMapLatest { query -> if (query.isBlank()) flowOf(emptyList()) else showDao.search(query.trim()) }
    val localEpisodeResults = localQuery
        .debounce(180)
        .flatMapLatest { query -> if (query.isBlank()) flowOf(emptyList()) else episodeDao.search(query.trim()) }

    private val _discovery = MutableStateFlow(DiscoveryUiState())
    val discovery: StateFlow<DiscoveryUiState> = _discovery.asStateFlow()
    private var discoveryJob: Job? = null
    private var activeDiscoveryTask: DiscoveryTask<DiscoveryResponse>? = null
    private val discoveryGeneration = AtomicLong(0L)
    private val _discoveryPreview = MutableStateFlow(DiscoveryPreviewUiState())
    val discoveryPreview: StateFlow<DiscoveryPreviewUiState> = _discoveryPreview.asStateFlow()
    private var discoveryPreviewBaseResult: DiscoveryResult? = null
    private var discoveryPreviewJob: Job? = null
    private val discoveryPreviewGeneration = AtomicLong(0L)
    private val sharedImportGeneration = AtomicLong(0L)
    private val _sharedImportNavigation = MutableStateFlow(0L)
    val sharedImportNavigation: StateFlow<Long> = _sharedImportNavigation.asStateFlow()
    private var artworkWarmJob: Job? = null

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _refreshingShowIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingShowIds: StateFlow<Set<String>> = _refreshingShowIds.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _playbackFailure = MutableStateFlow<PlaybackFailureUi?>(null)
    val playbackFailure: StateFlow<PlaybackFailureUi?> = _playbackFailure.asStateFlow()
    private val _categoryChoice = MutableStateFlow<CategoryChoiceUi?>(null)
    val categoryChoice: StateFlow<CategoryChoiceUi?> = _categoryChoice.asStateFlow()
    private val _startupOffer = MutableStateFlow<StartupOfferUi?>(null)
    val startupOffer: StateFlow<StartupOfferUi?> = _startupOffer.asStateFlow()
    private var startupOfferJob: Job? = null
    private val _maintenanceNotice = MutableStateFlow<MaintenanceNotice?>(null)
    val maintenanceNotice: StateFlow<MaintenanceNotice?> = _maintenanceNotice.asStateFlow()
    private val _catalogUpdate = MutableStateFlow<CatalogUpdateUi?>(null)
    val catalogUpdate: StateFlow<CatalogUpdateUi?> = _catalogUpdate.asStateFlow()

    init {
        if (appSettings.state.value.resumeOfferEnabled) {
            startupOfferJob = viewModelScope.launch(Dispatchers.IO) {
                val value = appSettings.state.value
                val initial = selectStartupOffer(value)
                _startupOffer.value = initial
                if (initial?.kind == StartupOfferKind.INTERRUPTED) return@launch
                if (value.autostartOfferMode == AutostartOfferMode.INTERRUPTED_ONLY) return@launch
                val showId = selectedAutostartShowId(value) ?: return@launch
                episodeDao.observeForShow(showId).collect { episodes ->
                    episodes.firstOrNull()?.let {
                        _startupOffer.value = StartupOfferUi(it, StartupOfferKind.SELECTED_LATEST)
                    }
                }
            }
        }
        checkMaintenance(manual = false)
        viewModelScope.launch(Dispatchers.IO) {
            BundledShowLayoutV128.previewUpdate(getApplication(), db)?.let { preview ->
                _catalogUpdate.value = CatalogUpdateUi(preview)
            }
        }
    }

    fun episodes(showId: String): Flow<List<EpisodeWithShow>> = episodeDao.observeForShow(showId)
    fun show(showId: String): Flow<ShowEntity?> = showDao.observe(showId)
    fun episode(episodeId: String): Flow<EpisodeWithShow?> = episodeDao.observeWithShow(episodeId)

    /** Manual refresh: toolbar button and pull-to-refresh use this path. */
    fun refreshAll() = refreshAllInternal(announce = true)

    private fun refreshAllInternal(announce: Boolean) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val summary = runCatching {
                val categories = AppSettings.refreshCategories(getApplication())
                val feeds = AppGraph.feeds.refreshAll(categories)
                val platforms = AppGraph.platformRefresh.refreshAll(categories)
                feeds to platforms
            }
            _syncing.value = false
            summary.onSuccess { (feeds, platforms) ->
                if (announce) announceRefresh(feeds, platforms)
            }.onFailure {
                if (announce) {
                    _messages.emit("Aktualisierung fehlgeschlagen: ${it.message ?: "Netzwerkfehler"}")
                }
            }
        }
    }

    fun refreshShow(showId: String) {
        if (showId in _refreshingShowIds.value) return
        viewModelScope.launch {
            _refreshingShowIds.value = _refreshingShowIds.value + showId
            try {
                val show = showDao.get(showId) ?: return@launch
                if (show.sourceType == ShowSourceType.SPOTIFY_PLAYLIST) {
                    _messages.emit("Spotify-Playlist ist als externe Verknüpfung gespeichert.")
                    return@launch
                }
                if (show.feedUrl == null) {
                    AppGraph.platformRefresh.refresh(show)
                        .onSuccess { _messages.emit("${show.title} aktualisiert") }
                        .onFailure { _messages.emit(it.message ?: "Plattformquelle konnte nicht aktualisiert werden") }
                    return@launch
                }
                val feedResult = runCatching { AppGraph.feeds.refresh(show) }
                if (feedResult.isSuccess) {
                    _messages.emit("${show.title} aktualisiert")
                } else if (show.platformUrl != null && AppGraph.platformRefresh.refresh(show).isSuccess) {
                    _messages.emit("${show.title} über Plattform-Fallback aktualisiert")
                } else {
                    _messages.emit("Feed konnte nicht aktualisiert werden: ${feedResult.exceptionOrNull()?.message}")
                }
            } finally {
                _refreshingShowIds.value = _refreshingShowIds.value - showId
            }
        }
    }

    fun toggleLike(episodeId: String) {
        // Keep the MediaSession in charge for the current item. This makes the heart in Android's
        // media controls update together with the in-app buttons. Non-current episodes do not
        // need to wake the playback service.
        if (player.value.mediaId == episodeId && playerConnection.toggleCurrentLike()) return
        viewModelScope.launch { episodeDao.toggleLiked(episodeId) }
    }

    fun toggleDownload(item: EpisodeWithShow) = viewModelScope.launch {
        when (item.episode.downloadStatus) {
            DownloadStatus.COMPLETE -> {
                EpisodeDownloads.delete(getApplication(), item.episode.id)
                _messages.emit("Download gelöscht")
            }
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                EpisodeDownloads.cancel(getApplication(), item.episode.id)
                _messages.emit("Download abgebrochen")
            }
            else -> {
                EpisodeDownloads.enqueue(getApplication(), item.episode.id)
                _messages.emit("Download gestartet")
            }
        }
    }

    fun play(episodeId: String) = viewModelScope.launch {
        when (val result = AppGraph.playback.play(episodeId)) {
            PlaybackStartResult.Started -> _playbackFailure.value = null
            is PlaybackStartResult.Failed -> _playbackFailure.value = PlaybackFailureUi(episodeId, result.error)
        }
    }

    fun playOrToggle(episodeId: String) {
        if (player.value.mediaId == episodeId) togglePlayer() else play(episodeId)
    }

    fun openEpisodeExternally(context: Context, episodeId: String) = viewModelScope.launch {
        val episode = episodeDao.get(episodeId) ?: return@launch
        if (episode.availability == EpisodeAvailability.SCHEDULED) {
            val verification = AppGraph.playback.resolveEpisode(episodeId, forceRefresh = true)?.second
            if (verification is ResolveResult.Failure) {
                _messages.emit(verification.error.message)
                return@launch
            }
        }
        val url = episode.pageUrl
        if (url.isNullOrBlank()) {
            _messages.emit("Für diese Folge ist kein Plattformlink vorhanden.")
            return@launch
        }
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val preferredPackage = when (episode.sourceType) {
            EpisodeSourceType.MIXCLOUD -> "com.mixcloud.player"
            EpisodeSourceType.SPOTIFY -> "com.spotify.music"
            else -> null
        }
        val preferred = preferredPackage?.let { Intent(generic).setPackage(it) }
        val intent = preferred?.takeIf { context.packageManager.resolveActivity(it, 0) != null } ?: generic
        if (context.packageManager.resolveActivity(intent, 0) == null) {
            _messages.emit("Für diesen Plattformlink wurde keine passende App gefunden.")
            return@launch
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
    }

    fun addToQueue(episodeId: String) = viewModelScope.launch {
        when (val result = AppGraph.playback.addToQueue(episodeId)) {
            PlaybackStartResult.Started -> _messages.emit("Zur Warteschlange hinzugefügt")
            is PlaybackStartResult.Failed -> _messages.emit(result.error.message)
        }
    }

    fun removeFromQueue(episodeId: String) = viewModelScope.launch {
        AppGraph.playback.removeFromQueue(episodeId)
        _messages.emit("Aus der Warteschlange entfernt")
    }

    fun clearQueue() = viewModelScope.launch {
        AppGraph.playback.clearQueue()
        _messages.emit("Warteschlange geleert")
    }

    fun reorderQueue(episodeIds: List<String>) = viewModelScope.launch {
        AppGraph.playback.reorderQueue(episodeIds)
    }

    fun restartQueuedEpisode(episodeId: String) = viewModelScope.launch {
        AppGraph.playback.restartQueuedEpisode(episodeId)
        _messages.emit("Wird in der Warteschlange von vorn abgespielt")
    }

    fun removeShow(show: ShowEntity) = viewModelScope.launch {
        if (show.origin == ShowOrigin.BUNDLED || show.legacyModuleId != null) {
            showDao.setSubscribed(show.id, false)
            _messages.emit("${show.title} ausgeblendet")
        } else {
            episodeDao.getForShow(show.id).forEach { episode ->
                episode.localFilePath?.let(::File)?.takeIf { it.isFile }?.delete()
                episode.localArtworkPath?.let(::File)?.takeIf { it.isFile }?.delete()
            }
            showDao.delete(show.id)
            _messages.emit("${show.title} gelöscht")
        }
    }

    fun restoreShow(showId: String) = viewModelScope.launch {
        val show = showDao.get(showId) ?: return@launch
        showDao.setSubscribed(showId, true)
        insertByStandardOrderOrTop(show.copy(subscribed = true), userCategoryChange = false)
        ShowArtworkCache.prefetchShow(getApplication(), showId)
        _messages.emit("Show wieder eingeblendet")
    }

    fun setLatestMode(showId: String, mode: LatestMode) = viewModelScope.launch {
        showDao.setLatestMode(showId, mode)
        _messages.emit(
            when (mode) {
                LatestMode.ALL -> "Alle Folgen erscheinen unter ‚Neu‘"
                LatestMode.LATEST_ONLY -> "Nur die neueste Folge erscheint unter ‚Neu‘"
                LatestMode.NONE -> "Aus ‚Neu‘ ausgeblendet"
            },
        )
    }

    fun setAutoPruneMissingEpisodes(showId: String, enabled: Boolean) = viewModelScope.launch {
        val show = showDao.get(showId) ?: return@launch
        if (show.sourceType != ShowSourceType.RSS || show.feedUrl == null) {
            _messages.emit("Automatisches Bereinigen ist nur für RSS-Podcasts verfügbar.")
            return@launch
        }
        showDao.setAutoPruneMissingEpisodes(showId, enabled)
        _messages.emit(if (enabled) "Automatisches Bereinigen aktiviert" else "Automatisches Bereinigen deaktiviert")
    }

    fun cleanupMissingEpisodes(showId: String) = viewModelScope.launch {
        val show = showDao.get(showId) ?: return@launch
        if (show.sourceType != ShowSourceType.RSS || show.feedUrl == null) {
            _messages.emit("Bereinigen ist nur für RSS-Podcasts verfügbar.")
            return@launch
        }
        runCatching { AppGraph.feeds.cleanupMissingEpisodes(show) }
            .onSuccess { result ->
                if (!result.cleanupPerformed) {
                    _messages.emit("Der Feed enthält aktuell keine Folgen; aus Sicherheitsgründen wurde nichts gelöscht.")
                } else {
                    _messages.emit(
                        when (result.removedEpisodes) {
                            0 -> "Keine veralteten Folgen gefunden"
                            1 -> "1 veraltete Folge entfernt"
                            else -> "${result.removedEpisodes} veraltete Folgen entfernt"
                        },
                    )
                }
            }
            .onFailure { error ->
                _messages.emit("Bereinigen fehlgeschlagen: ${error.message ?: "Netzwerkfehler"}")
            }
    }

    fun renameShow(showId: String, title: String) = viewModelScope.launch {
        val clean = title.trim()
        if (clean.isBlank()) {
            _messages.emit("Der Showname darf nicht leer sein.")
            return@launch
        }
        val show = showDao.get(showId) ?: return@launch
        if (show.title == clean) return@launch
        showDao.setTitle(showId, clean)
        _messages.emit("Show in „$clean“ umbenannt")
    }

    fun copyShowSourceLink(showId: String) = viewModelScope.launch {
        val show = showDao.get(showId) ?: return@launch
        val sourceUrl = when (show.sourceType) {
            ShowSourceType.RSS -> show.feedUrl ?: show.platformUrl
            else -> show.platformUrl ?: show.feedUrl
        }
        if (sourceUrl.isNullOrBlank()) {
            _messages.emit("Für diese Show ist kein Quell-Link hinterlegt")
            return@launch
        }
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Quell-Link", sourceUrl))
        _messages.emit("Quell-Link kopiert")
    }

    fun moveShow(showId: String, direction: Int) = viewModelScope.launch {
        val show = showDao.get(showId) ?: return@launch
        val current = showDao.getSubscribed(show.category)
        val index = current.indexOfFirst { it.id == showId }
        if (index < 0) return@launch
        val target = (index + direction).coerceIn(0, current.lastIndex)
        if (target == index) return@launch
        val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
        persistCategoryOrder(reordered, customizedId = showId)
    }

    fun reorderShows(orderedIds: List<String>) {
        appSettings.setShowOrderMode(ShowOrderMode.CUSTOM)
        viewModelScope.launch {
            val current = showDao.getSubscribed()
            PodcastCategory.entries.forEach { category ->
                val categoryShows = current.filter { it.category == category }
                val validIds = categoryShows.mapTo(linkedSetOf()) { it.id }
                val normalizedIds = buildList {
                    orderedIds.forEach { id -> if (id in validIds && id !in this) add(id) }
                    categoryShows.forEach { show -> if (show.id !in this) add(show.id) }
                }
                val oldIndex = categoryShows.mapIndexed { index, show -> show.id to index }.toMap()
                val customizedId = normalizedIds.maxByOrNull { id ->
                    kotlin.math.abs((oldIndex[id] ?: 0) - normalizedIds.indexOf(id))
                }?.takeIf { id -> oldIndex[id] != normalizedIds.indexOf(id) }
                val byId = categoryShows.associateBy { it.id }
                persistCategoryOrder(normalizedIds.mapNotNull(byId::get), customizedId)
            }
        }
    }

    fun moveShowToTop(showId: String) {
        // "Ganz nach oben" is a custom-order action. If A–Z is currently only being viewed,
        // switch back without ever destroying its saved custom ordering.
        appSettings.setShowOrderMode(ShowOrderMode.CUSTOM)
        viewModelScope.launch(Dispatchers.IO) {
            val show = showDao.get(showId) ?: return@launch
            if (!show.subscribed) return@launch
            val items = showDao.getSubscribed(show.category).toMutableList()
            items.removeAll { it.id == showId }
            items.add(0, show)
            persistCategoryOrder(items, customizedId = showId)
            _messages.emit("${show.title} nach ganz oben verschoben")
        }
    }

    fun moveShowToBottom(showId: String) {
        appSettings.setShowOrderMode(ShowOrderMode.CUSTOM)
        viewModelScope.launch(Dispatchers.IO) {
            val show = showDao.get(showId) ?: return@launch
            if (!show.subscribed) return@launch
            val items = showDao.getSubscribed(show.category).toMutableList()
            items.removeAll { it.id == showId }
            items.add(show)
            persistCategoryOrder(items, customizedId = showId)
            _messages.emit("${show.title} nach ganz unten verschoben")
        }
    }

    fun switchShowCategory(showId: String) = viewModelScope.launch(Dispatchers.IO) {
        val show = showDao.get(showId) ?: return@launch
        val target = if (show.category == PodcastCategory.WORD) PodcastCategory.MUSIC else PodcastCategory.WORD
        showDao.setCategory(showId, target, userAssigned = true)
        insertByStandardOrderOrTop(show.copy(category = target, categoryUserAssigned = true), userCategoryChange = true)
        _messages.emit(
            if (target == PodcastCategory.WORD) "Zu Wort-Podcasts verschoben" else "Zu Musik-Podcasts verschoben",
        )
    }

    private suspend fun insertByStandardOrderOrTop(show: ShowEntity, userCategoryChange: Boolean) {
        val items = showDao.getSubscribed(show.category).filterNot { it.id == show.id }.toMutableList()
        val target = show.standardSortOrder?.let { standard ->
            items.indexOfFirst { (it.standardSortOrder ?: Int.MAX_VALUE) > standard }
                .takeIf { it >= 0 }
                ?: items.size
        } ?: 0
        items.add(target, show)
        items.forEachIndexed { index, item -> showDao.setSortOrder(item.id, index) }
        showDao.setOrderPlacement(show.id, target, false, null, null)
        if (userCategoryChange) showDao.setCategory(show.id, show.category, userAssigned = true)
    }

    private suspend fun persistCategoryOrder(items: List<ShowEntity>, customizedId: String?) {
        items.forEachIndexed { index, item -> showDao.setSortOrder(item.id, index) }
        val customIndex = items.indexOfFirst { it.id == customizedId }
        if (customIndex >= 0) {
            showDao.setOrderPlacement(
                id = items[customIndex].id,
                sortOrder = customIndex,
                customized = true,
                beforeId = items.getOrNull(customIndex - 1)?.id,
                afterId = items.getOrNull(customIndex + 1)?.id,
            )
        }
    }

    fun warmShowArtwork(urls: List<String>) {
        artworkWarmJob?.cancel()
        artworkWarmJob = viewModelScope.launch(Dispatchers.IO) {
            ShowArtworkCache.warmMemory(getApplication(), urls.distinct())
        }
    }

    fun setWifiQuality(quality: StreamingQuality) = appSettings.setWifiQuality(quality)
    fun setMobileQuality(quality: StreamingQuality) = appSettings.setMobileQuality(quality)
    fun setDownloadQuality(quality: StreamingQuality) = appSettings.setDownloadQuality(quality)
    fun setShowOrderMode(mode: ShowOrderMode) = appSettings.setShowOrderMode(mode)
    fun setBluetoothAutoOpenDevice(address: String, enabled: Boolean) =
        appSettings.setBluetoothAutoOpenDevice(address, enabled)
    fun setBluetoothAutoResumeDevice(address: String, enabled: Boolean) =
        appSettings.setBluetoothAutoResumeDevice(address, enabled)
    fun setMiniPlayerControls(value: MiniPlayerControls) = appSettings.setMiniPlayerControls(value)
    fun setStartupScreen(value: StartupScreen) = appSettings.setStartupScreen(value)
    fun setWordPodcastsEnabled(value: Boolean) = appSettings.setWordPodcastsEnabled(value)
    fun setMusicPodcastsEnabled(value: Boolean) = appSettings.setMusicPodcastsEnabled(value)
    fun setWordPodcastsInLatest(value: Boolean) = appSettings.setWordPodcastsInLatest(value)
    fun setMusicPodcastsInLatest(value: Boolean) = appSettings.setMusicPodcastsInLatest(value)
    fun setHideScheduledFromLatest(value: Boolean) = appSettings.setHideScheduledFromLatest(value)
    fun setRefreshOnColdStart(value: Boolean) {
        appSettings.setRefreshOnColdStart(value)
        if (value) AppSyncScheduler.schedule(getApplication()) else AppSyncScheduler.cancel(getApplication())
    }
    fun setExitAfterIdle(value: Boolean) = appSettings.setExitAfterIdle(value)
    fun setResumeOfferEnabled(value: Boolean) = appSettings.setResumeOfferEnabled(value)
    fun setMiniPlayerImplementation(value: MiniPlayerImplementation) = appSettings.setMiniPlayerImplementation(value)
    fun setOverlaySize(value: OverlaySize) = appSettings.setOverlaySize(value)
    fun setOverlayLayout(value: OverlayLayout) = appSettings.setOverlayLayout(value)
    fun setAutoMiniPlayerOnBackground(value: Boolean) = appSettings.setAutoMiniPlayerOnBackground(value)
    fun setAppUpdateChecksEnabled(value: Boolean) = appSettings.setAppUpdateChecksEnabled(value)
    fun setNewPipeChecksEnabled(value: Boolean) = appSettings.setNewPipeChecksEnabled(value)
    fun setBluetoothLaunchMode(value: BluetoothLaunchMode) = appSettings.setBluetoothLaunchMode(value)
    fun setBluetoothAutoplayMode(value: BluetoothAutoplayMode) = appSettings.setBluetoothAutoplayMode(value)
    fun setBluetoothDisplayMode(value: BluetoothDisplayMode) = appSettings.setBluetoothDisplayMode(value)
    fun setAutostartOfferMode(value: AutostartOfferMode) = appSettings.setAutostartOfferMode(value)
    fun setAutostartShowId(value: String?) = appSettings.setAutostartShowId(value)

    fun checkAppUpdate() = viewModelScope.launch {
        if (!MaintenanceChecks.updateChannelConfigured()) {
            _messages.emit("Für diese Ausgabe ist noch kein Update-Kanal eingerichtet.")
            return@launch
        }
        runCatching { MaintenanceChecks.appUpdate(getApplication(), includeDismissed = true) }
            .onSuccess { notice ->
                if (notice == null) _messages.emit("Die App ist aktuell.") else _maintenanceNotice.value = notice
            }
            .onFailure {
                AppDiagnostics.record(getApplication(), "Update", "Manuelle Update-Prüfung fehlgeschlagen", it)
                _messages.emit("Update-Prüfung derzeit nicht möglich")
            }
    }

    fun checkNewPipeCompatibility() = viewModelScope.launch {
        runCatching { MaintenanceChecks.newPipe(getApplication(), includeDismissed = true) }
            .onSuccess { notice ->
                if (notice == null) _messages.emit("Interne Wiedergabe und NewPipe sind aktuell.")
                else _maintenanceNotice.value = notice
            }
            .onFailure {
                AppDiagnostics.record(getApplication(), "NewPipe", "Kompatibilitätsprüfung fehlgeschlagen", it)
                _messages.emit("NewPipe-Prüfung derzeit nicht möglich")
            }
    }

    fun dismissMaintenanceNotice() {
        _maintenanceNotice.value?.let { MaintenanceChecks.dismiss(getApplication(), it) }
        _maintenanceNotice.value = null
    }

    fun performMaintenanceAction(context: Context) {
        val notice = _maintenanceNotice.value ?: return
        when {
            notice.primaryUrl == "feedback://weekly-dj-shows" -> {
                shareFeedback(context)
                dismissMaintenanceNotice()
            }
            notice.kind == MaintenanceKind.APP_UPDATE -> runCatching {
                AppUpdateInstaller.enqueue(context, notice)
            }.onSuccess { started ->
                if (started) {
                    _messages.tryEmit("Update wird im Hintergrund geladen")
                    dismissMaintenanceNotice()
                } else {
                    _messages.tryEmit("Erlaube Installationen und tippe danach erneut auf das Update")
                }
            }.onFailure {
                AppDiagnostics.record(getApplication(), "Update", "Download konnte nicht gestartet werden", it)
                _messages.tryEmit("Update-Download konnte nicht gestartet werden")
            }
            else -> {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.primaryUrl)))
                }.onFailure { _messages.tryEmit("Link konnte nicht geöffnet werden") }
                dismissMaintenanceNotice()
            }
        }
    }

    fun openNewPipeInstallPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://newpipe.net/FAQ/tutorials/install-add-fdroid-repo/"),
                ),
            )
        }.onFailure { _messages.tryEmit("NewPipe-Link konnte nicht geöffnet werden") }
    }

    private fun checkMaintenance(manual: Boolean) = viewModelScope.launch {
        val value = appSettings.state.value
        val notice = runCatching {
            when {
                value.appUpdateChecksEnabled -> MaintenanceChecks.appUpdate(getApplication(), includeDismissed = manual)
                else -> null
            } ?: when {
                value.newPipeChecksEnabled -> MaintenanceChecks.newPipe(getApplication(), includeDismissed = manual)
                else -> null
            }
        }.onFailure {
            AppDiagnostics.record(getApplication(), "Wartung", "Automatische Prüfung fehlgeschlagen", it)
        }.getOrNull()
        if (notice != null) _maintenanceNotice.value = notice
    }

    fun shareFeedback(context: Context) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Weekly DJ Shows ${BuildConfig.VERSION_NAME} – Rückmeldung")
            .putExtra(Intent.EXTRA_TEXT, AppDiagnostics.report(context))
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Fehler oder Anregung senden"))
        }.onFailure {
            _messages.tryEmit("Keine App zum Teilen des Berichts gefunden")
        }
    }

    fun exportShowView(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val allShows = showDao.getAll()
            val json = ShowViewTransfer.encode(
                shows = allShows,
                showOrderMode = appSettings.state.value.showOrderMode,
                settings = appSettings.state.value,
                appVersion = BuildConfig.VERSION_NAME,
            )
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(json)
            } ?: error("Zieldatei konnte nicht geöffnet werden")
            allShows.size
        }.onSuccess { count ->
            _messages.emit("Show-Ansicht exportiert · $count Shows")
        }.onFailure { error ->
            _messages.emit("Export fehlgeschlagen: ${error.message ?: "Dateifehler"}")
        }
    }

    fun importShowView(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val snapshot = ShowViewTransfer.decode(readShowViewText(uri))
            val existingShows = showDao.getAll()
            val byId = existingShows.associateBy { it.id }
            val byLegacyId = existingShows.mapNotNull { show -> show.legacyModuleId?.let { it to show } }.toMap()
            val bySource = linkedMapOf<String, ShowEntity>()
            existingShows.forEach { show ->
                showSourceKey(show.sourceType, show.feedUrl, show.platformUrl)?.let { key ->
                    if (key !in bySource) bySource[key] = show
                }
            }

            val resolvedIds = linkedMapOf<String, String>()
            var addedCount = 0
            val imported = snapshot.shows.map { entry ->
                val existing = entry.legacyModuleId?.let(byLegacyId::get)
                    ?: byId[entry.id]
                    ?: showSourceKey(entry.sourceType, entry.feedUrl, entry.platformUrl)?.let(bySource::get)
                val actualId = existing?.id ?: entry.id
                require(actualId !in resolvedIds.values) { "Export enthält dieselbe Showquelle mehrfach" }
                resolvedIds[entry.id] = actualId
                if (existing != null) {
                    val importedLatestMode = if (existing.sourceType == ShowSourceType.SPOTIFY_PLAYLIST) {
                        LatestMode.NONE
                    } else {
                        entry.latestMode
                    }
                    existing.copy(
                        title = entry.title,
                        description = existing.description.ifBlank { entry.description },
                        artworkUrl = existing.artworkUrl ?: entry.artworkUrl,
                        subscribed = entry.subscribed,
                        category = entry.category,
                        categoryUserAssigned = entry.categoryUserAssigned,
                        origin = if (existing.origin == ShowOrigin.BUNDLED) ShowOrigin.BUNDLED else entry.origin,
                        hideFromLatest = importedLatestMode == LatestMode.NONE,
                        latestMode = importedLatestMode,
                        autoPruneMissingEpisodes = entry.autoPruneMissingEpisodes && existing.sourceType == ShowSourceType.RSS,
                        sortOrder = entry.sortOrder,
                        standardSortOrder = entry.standardSortOrder ?: existing.standardSortOrder,
                        orderCustomized = entry.orderCustomized,
                        orderAnchorBeforeId = entry.orderAnchorBeforeId,
                        orderAnchorAfterId = entry.orderAnchorAfterId,
                    )
                } else {
                    addedCount++
                    val importedLatestMode = if (entry.sourceType == ShowSourceType.SPOTIFY_PLAYLIST) {
                        LatestMode.NONE
                    } else {
                        entry.latestMode
                    }
                    ShowEntity(
                        id = entry.id,
                        title = entry.title,
                        feedUrl = entry.feedUrl.takeUnless { entry.sourceType == ShowSourceType.SPOTIFY_PLAYLIST },
                        platformUrl = entry.platformUrl,
                        sourceType = entry.sourceType,
                        description = entry.description,
                        artworkUrl = entry.artworkUrl,
                        subscribed = entry.subscribed,
                        category = entry.category,
                        categoryUserAssigned = entry.categoryUserAssigned,
                        origin = entry.origin,
                        hideFromLatest = importedLatestMode == LatestMode.NONE,
                        latestMode = importedLatestMode,
                        autoPruneMissingEpisodes = entry.autoPruneMissingEpisodes && entry.sourceType == ShowSourceType.RSS,
                        sortOrder = entry.sortOrder,
                        standardSortOrder = entry.standardSortOrder,
                        orderCustomized = entry.orderCustomized,
                        orderAnchorBeforeId = entry.orderAnchorBeforeId,
                        orderAnchorAfterId = entry.orderAnchorAfterId,
                        legacyModuleId = entry.legacyModuleId,
                        addedAtEpochMs = entry.addedAtEpochMs,
                    )
                }
            }
            val importedById = imported.associateBy { it.id }
            val importedOrder = snapshot.startOrderIds
                .mapNotNull(resolvedIds::get)
                .filter { importedById[it]?.subscribed == true }
                .distinct()
            val importedMissingFromOrder = imported
                .filter { it.subscribed && it.id !in importedOrder }
                .sortedWith(compareBy<ShowEntity> { it.sortOrder }.thenBy { it.title.lowercase() })
                .map { it.id }
            val resolvedActualIds = resolvedIds.values.toSet()
            val untouchedExtras = existingShows
                .filter { it.subscribed && it.id !in resolvedActualIds }
                .sortedWith(compareBy<ShowEntity> { it.sortOrder }.thenBy { it.title.lowercase() })
                .map { it.id }
            db.importShowView(imported, importedOrder + importedMissingFromOrder + untouchedExtras)
            val currentSettings = appSettings.state.value
            appSettings.replaceAll(
                currentSettings.copy(
                    showOrderMode = snapshot.showOrderMode,
                    wordPodcastsEnabled = snapshot.viewSettings.wordPodcastsEnabled,
                    musicPodcastsEnabled = snapshot.viewSettings.musicPodcastsEnabled,
                    wordPodcastsInLatest = snapshot.viewSettings.wordPodcastsInLatest,
                    musicPodcastsInLatest = snapshot.viewSettings.musicPodcastsInLatest,
                    hideScheduledFromLatest = snapshot.viewSettings.hideScheduledFromLatest,
                ),
            )
            addedCount to imported.count { it.subscribed }
        }.onSuccess { (addedCount, visibleCount) ->
            _messages.emit(
                if (addedCount > 0) {
                    "Show-Ansicht importiert · $addedCount neue Shows · $visibleCount sichtbar"
                } else {
                    "Show-Ansicht importiert · Reihenfolge übernommen"
                },
            )
            ShowArtworkCache.prefetchSubscribed(getApplication())
            refreshAllInternal(announce = false)
        }.onFailure { error ->
            _messages.emit("Import fehlgeschlagen: ${(error.message ?: "ungültige Datei").take(180)}")
        }
    }

    fun exportFullBackup(uri: Uri, includeDownloads: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            FullBackupTransfer.export(
                context = getApplication(),
                database = db,
                settings = appSettings.state.value,
                uri = uri,
                appVersion = BuildConfig.VERSION_NAME,
                includeDownloads = includeDownloads,
            )
        }.onSuccess { summary ->
            _messages.emit(
                "Vollständige Sicherung erstellt · ${summary.shows} Shows · ${summary.episodes} Folgen" +
                    if (includeDownloads) " · ${summary.downloads} Downloads" else "",
            )
        }.onFailure { error ->
            _messages.emit("Sicherung fehlgeschlagen: ${(error.message ?: "Dateifehler").take(180)}")
        }
    }

    /** One picker accepts both the human-readable view JSON and a complete ZIP backup. */
    fun importTransfer(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val zip = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                input.read() == 'P'.code && input.read() == 'K'.code
            } == true
        }.getOrDefault(false)
        if (!zip) {
            importShowView(uri)
            return@launch
        }
        runCatching { FullBackupTransfer.import(getApplication(), db, uri) }
            .onSuccess { summary ->
                summary.importedSettings?.let(appSettings::replaceAll)
                val enabled = appSettings.state.value.refreshOnColdStart
                if (enabled) AppSyncScheduler.schedule(getApplication()) else AppSyncScheduler.cancel(getApplication())
                _messages.emit(
                    "Sicherung importiert · ${summary.shows} Shows · ${summary.episodes} Folgen" +
                        if (summary.downloads > 0) " · ${summary.downloads} Downloads" else "",
                )
                ShowArtworkCache.prefetchSubscribed(getApplication())
            }
            .onFailure { error ->
                _messages.emit("Import fehlgeschlagen: ${(error.message ?: "ungültige Sicherung").take(180)}")
            }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearTemporaryCache() = viewModelScope.launch(Dispatchers.IO) {
        val loader = getApplication<Application>().imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        AppGraph.feeds.clearHttpCache()
        _messages.emit("Zwischengespeicherte Daten gelöscht")
    }

    fun clearPlaybackState() = viewModelScope.launch(Dispatchers.IO) {
        episodeDao.clearPlaybackState()
        _messages.emit("Abspielstände und Gehört-Markierungen gelöscht")
    }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) {
        historyDao.clear()
        _messages.emit("Hörverlauf gelöscht")
    }

    fun playbackDates(episodeId: String): Flow<List<Long>> = historyDao.observePlaybackDates(episodeId)

    fun dismissStartupOffer() {
        startupOfferJob?.cancel()
        startupOfferJob = null
        _startupOffer.value = null
    }

    fun chooseCatalogOrder(acceptStandardOrder: Boolean) {
        val update = _catalogUpdate.value ?: return
        if (update.preview.retiredTitles.isEmpty()) {
            applyCatalogUpdate(acceptStandardOrder, removeRetired = false)
        } else {
            _catalogUpdate.value = update.copy(acceptedStandardOrder = acceptStandardOrder)
        }
    }

    fun finishCatalogUpdate(removeRetired: Boolean) {
        val update = _catalogUpdate.value ?: return
        val acceptedOrder = update.acceptedStandardOrder ?: return
        applyCatalogUpdate(acceptedOrder, removeRetired)
    }

    private fun applyCatalogUpdate(acceptStandardOrder: Boolean, removeRetired: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                BundledShowLayoutV128.applyCatalogUpdate(
                    getApplication(),
                    db,
                    acceptStandardOrder,
                    removeRetired,
                )
            }.onSuccess {
                _catalogUpdate.value = null
                _messages.emit("Show-Liste wurde zusammengeführt")
                refreshAllInternal(announce = false)
            }.onFailure { error ->
                AppDiagnostics.record(getApplication(), "Show-Katalog", "Zusammenführen fehlgeschlagen", error)
                _messages.emit("Show-Liste konnte nicht zusammengeführt werden")
            }
        }
    }

    fun playStartupOffer() {
        val id = _startupOffer.value?.media?.episode?.id ?: return
        dismissStartupOffer()
        play(id)
    }

    /** Gives a Bluetooth mini-start a paused, fully resolved card even after process death. */
    suspend fun prepareBluetoothAutostartOffer(): Boolean {
        if (player.value.mediaId != null) return true
        val offer = selectStartupOffer(appSettings.state.value) ?: return false
        return when (AppGraph.playback.preparePaused(offer.media.episode.id)) {
            PlaybackStartResult.Started -> true
            is PlaybackStartResult.Failed -> false
        }
    }

    private suspend fun selectStartupOffer(settings: AppSettingsState): StartupOfferUi? {
        val interrupted = episodeDao.getLastResumable()
        val selectedShowId = selectedAutostartShowId(settings)
        val selectedLatest = selectedShowId?.let { episodeDao.getLatestForShow(it) }
        return when (settings.autostartOfferMode) {
            AutostartOfferMode.INTERRUPTED_ONLY -> interrupted?.let {
                StartupOfferUi(it, StartupOfferKind.INTERRUPTED)
            }
            AutostartOfferMode.INTERRUPTED_THEN_SELECTED -> interrupted?.let {
                StartupOfferUi(it, StartupOfferKind.INTERRUPTED)
            } ?: selectedLatest?.let { StartupOfferUi(it, StartupOfferKind.SELECTED_LATEST) }
            AutostartOfferMode.SELECTED_LATEST -> selectedLatest?.let {
                StartupOfferUi(it, StartupOfferKind.SELECTED_LATEST)
            }
        }
    }

    private suspend fun selectedAutostartShowId(settings: AppSettingsState): String? {
        fun ShowEntity.visibleAndEligible(): Boolean = isAutostartEpisodeSource() &&
            ((category == PodcastCategory.WORD && settings.wordPodcastsEnabled) ||
                (category == PodcastCategory.MUSIC && settings.musicPodcastsEnabled))

        val selected = settings.autostartShowId
            ?.let { showDao.get(it) }
            ?.takeIf { it.visibleAndEligible() }
        return selected?.id ?: showDao.getSubscribed().firstOrNull { it.visibleAndEligible() }?.id
    }

    fun deleteFromHistory(episodeId: String) = viewModelScope.launch(Dispatchers.IO) {
        historyDao.deleteEpisode(episodeId)
        _messages.emit("Aus dem Verlauf gelöscht")
    }

    fun clearPlaybackFailure() { _playbackFailure.value = null }

    fun openCurrentFailureExternally(context: Context) {
        val failure = _playbackFailure.value ?: return
        if (failure.error.type == de.rdoe.weeklydjshows.model.ResolverErrorType.NOT_YET_AVAILABLE) {
            _messages.tryEmit("Die Verfügbarkeit wird beim nächsten Abspielversuch erneut geprüft.")
            return
        }
        viewModelScope.launch {
            val url = episodeDao.get(failure.episodeId)?.pageUrl
            if (url.isNullOrBlank()) {
                _messages.emit("Für diese Folge ist kein Plattformlink vorhanden.")
                return@launch
            }
            if (url.contains("open.spotify.com", ignoreCase = true)) {
                val generic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val spotify = Intent(generic).setPackage("com.spotify.music")
                val intent = if (context.packageManager.resolveActivity(spotify, 0) != null) spotify else generic
                if (context.packageManager.resolveActivity(intent, 0) == null) {
                    _messages.emit("Für den Spotify-Link wurde keine passende App gefunden.")
                } else {
                    withContext(Dispatchers.Main) {
                        context.startActivity(intent)
                        _playbackFailure.value = null
                    }
                }
                return@launch
            }
            if (url.contains("mixcloud.com", ignoreCase = true)) {
                val generic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val mixcloud = Intent(generic).setPackage("com.mixcloud.player")
                val intent = if (context.packageManager.resolveActivity(mixcloud, 0) != null) mixcloud else generic
                if (context.packageManager.resolveActivity(intent, 0) == null) {
                    _messages.emit("Für den Mixcloud-Link wurde keine passende App gefunden.")
                } else {
                    withContext(Dispatchers.Main) {
                        context.startActivity(intent)
                        _playbackFailure.value = null
                    }
                }
                return@launch
            }
            val packages = listOf("org.schabi.newpipe", "org.schabi.newpipe.debug")
            val packageName = packages.firstOrNull { pkg ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                context.packageManager.resolveActivity(intent, 0) != null
            }
            if (packageName == null) {
                _messages.emit("NewPipe ist nicht installiert.")
            } else {
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))
                    _playbackFailure.value = null
                }
            }
        }
    }

    fun openShowSourceExternally(context: Context, showId: String) {
        viewModelScope.launch {
            val show = showDao.get(showId) ?: return@launch
            val url = show.platformUrl ?: show.feedUrl
            if (url.isNullOrBlank()) {
                _messages.emit("Für diese Show ist kein externer Link gespeichert.")
                return@launch
            }
            val generic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val preferred = if (show.sourceType == ShowSourceType.SPOTIFY_PLAYLIST) {
                Intent(generic).setPackage("com.spotify.music")
            } else {
                generic
            }
            val intent = if (context.packageManager.resolveActivity(preferred, 0) != null) preferred else generic
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                _messages.emit("Für diesen Link wurde keine passende App gefunden.")
                return@launch
            }
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        }
    }

    fun searchDiscovery(query: String) {
        if (query.isBlank()) return
        val cleaned = query.trim()
        val embeddedUrl = TextTools.firstHttpUrl(cleaned)
        if (embeddedUrl != null) {
            resolveDiscoveryUrl(embeddedUrl)
            return
        }
        if (isDirectSourceInput(cleaned)) {
            resolveDiscoveryUrl(cleaned)
            return
        }
        runDiscovery("Suche: $cleaned") { listener ->
            AppGraph.discovery.search(
                SearchRequest(
                    query = cleaned,
                    countries = listOf("DE", "US"),
                    musicMode = MusicMode.ALL,
                    includePlatformResults = true,
                    verifyTopRssResults = 15,
                ),
                listener,
            )
        }
    }

    private fun resolveDiscoveryUrl(input: String) {
        val generation = discoveryGeneration.incrementAndGet()
        activeDiscoveryTask?.cancel()
        discoveryJob?.cancel()
        val title = "Suche: $input"
        _discovery.value = DiscoveryUiState(loading = true, title = title)
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            fun isCurrent() = discoveryGeneration.get() == generation
            try {
                val resolution = AppGraph.discovery.resolveUrl(input)
                val baseResult = AppGraph.discovery.discoveryResult(resolution)
                // A feed verifier is intentionally not the sole metadata source for platform
                // links. NewPipe/Spotify can provide the canonical playlist/profile title,
                // description and cover even when a lightweight feed is temporarily unavailable.
                val result = baseResult?.let { buildDiscoveryPreview(it).result ?: it }
                if (isCurrent()) {
                    _discovery.value = DiscoveryUiState(
                        loading = false,
                        title = title,
                        results = listOfNotNull(result),
                        error = resolution.error ?: if (result == null) "Keine abonnierbare Quelle in diesem Link gefunden." else null,
                    )
                }
            } catch (error: Throwable) {
                if (isCurrent()) {
                    _discovery.value = DiscoveryUiState(
                        loading = false,
                        title = title,
                        error = error.message ?: "Quelle konnte nicht aufgelöst werden",
                    )
                }
            }
        }
    }

    fun browseDiscovery(mode: BrowseMode = BrowseMode.POPULAR, genre: String? = null) {
        val label = when (mode) {
            BrowseMode.GENRE -> genre ?: "Genre"
            BrowseMode.NEW -> "Neue Shows"
            BrowseMode.TRENDING -> "Trending"
            BrowseMode.RECENTLY_UPDATED -> "Kürzlich aktualisiert"
            else -> "Beliebte DJ- & Musikshows"
        }
        runDiscovery(label) { listener ->
            AppGraph.discovery.browse(
                BrowseRequest(
                    mode = mode,
                    genre = genre,
                    country = "DE",
                    limit = 60,
                    musicMode = MusicMode.DJ_AND_MUSIC,
                    includePlatformResults = true,
                    verifyTopRssResults = 15,
                ),
                listener,
            )
        }
    }

    private fun runDiscovery(
        title: String,
        start: (DiscoveryListener) -> DiscoveryTask<DiscoveryResponse>,
    ) {
        val generation = discoveryGeneration.incrementAndGet()
        activeDiscoveryTask?.cancel()
        discoveryJob?.cancel()
        _discovery.value = DiscoveryUiState(loading = true, title = title)
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            val statuses = linkedMapOf<ProviderId, ProviderStatus>()
            val stableResults = linkedMapOf<String, DiscoveryResult>()
            fun isCurrent() = discoveryGeneration.get() == generation
            val listener = object : DiscoveryListener {
                override fun onProviderStatus(status: ProviderStatus) {
                    if (!isCurrent()) return
                    synchronized(statuses) { statuses[status.provider] = status }
                    _discovery.update {
                        if (!isCurrent()) it else it.copy(
                            providerStatuses = synchronized(statuses) { statuses.values.toList() },
                        )
                    }
                }

                override fun onPartialResults(results: List<DiscoveryResult>) {
                    if (!isCurrent()) return
                    // LinkedHashMap assignment updates an existing card in-place. New cards append;
                    // provider re-ranking therefore cannot make the loading list jump around.
                    val snapshot = synchronized(stableResults) {
                        results.forEach { stableResults[it.internalId] = it }
                        stableResults.values.toList()
                    }
                    _discovery.update { if (!isCurrent()) it else it.copy(results = snapshot) }
                }
            }
            try {
                val task = start(listener)
                if (!isCurrent()) {
                    task.cancel()
                    return@launch
                }
                activeDiscoveryTask = task
                val response = task.get()
                if (isCurrent()) {
                    _discovery.value = DiscoveryUiState(
                        loading = false,
                        title = title,
                        results = response.results,
                        providerStatuses = response.providerStatuses,
                    )
                }
            } catch (_: java.util.concurrent.CancellationException) {
                // A superseded generation is intentionally silent; its callbacks are stale too.
                if (isCurrent()) _discovery.update { it.copy(loading = false) }
            } catch (raw: Throwable) {
                if (isCurrent()) {
                    val error = (raw as? ExecutionException)?.cause ?: raw
                    _discovery.update {
                        it.copy(loading = false, error = error.message ?: "Suche fehlgeschlagen")
                    }
                }
            } finally {
                if (isCurrent()) activeDiscoveryTask = null
            }
        }
    }

    fun openDiscoveryPreview(result: DiscoveryResult) {
        val generation = discoveryPreviewGeneration.incrementAndGet()
        discoveryPreviewJob?.cancel()
        discoveryPreviewBaseResult = result
        val targets = subscriptionTargets(result)
        _discoveryPreview.value = DiscoveryPreviewUiState(
            loading = true,
            result = result,
            availableTargets = targets,
            selectedTarget = targets.firstOrNull(),
        )
        discoveryPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            val preview = buildDiscoveryPreview(result)
            if (discoveryPreviewGeneration.get() == generation) _discoveryPreview.value = preview
        }
    }

    fun selectDiscoveryPreviewTarget(target: IntegrationTarget) {
        val base = discoveryPreviewBaseResult ?: return
        val targets = subscriptionTargets(base)
        val selected = targets.firstOrNull { it == target } ?: return
        val generation = discoveryPreviewGeneration.incrementAndGet()
        discoveryPreviewJob?.cancel()
        val previous = _discoveryPreview.value
        _discoveryPreview.value = previous.copy(
            loading = true,
            availableTargets = targets,
            selectedTarget = selected,
            listing = null,
            feedVerification = null,
            feedPreview = null,
            error = null,
            automaticallyAdded = false,
        )
        discoveryPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            val preview = buildDiscoveryPreview(base, selected).copy(sharedImport = previous.sharedImport)
            if (discoveryPreviewGeneration.get() == generation) _discoveryPreview.value = preview
        }
    }

    /** Entry point for Android's share sheet. Text around the URL is intentionally accepted. */
    fun handleSharedText(text: String?) {
        val url = TextTools.firstHttpUrl(text)
        if (url == null) {
            viewModelScope.launch { _messages.emit("Im geteilten Text wurde kein Web-Link gefunden.") }
            return
        }
        val generation = discoveryPreviewGeneration.incrementAndGet()
        discoveryPreviewJob?.cancel()
        _discoveryPreview.value = DiscoveryPreviewUiState(
            loading = true,
            sharedImport = true,
        )
        _sharedImportNavigation.value = sharedImportGeneration.incrementAndGet()
        discoveryPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                val resolution = AppGraph.discovery.resolveUrl(url)
                val baseResult = AppGraph.discovery.discoveryResult(resolution)
                if (baseResult == null) {
                    DiscoveryPreviewUiState(
                        error = resolution.error ?: "Der geteilte Link enthält keine abonnierbare Show.",
                        sharedImport = true,
                    )
                } else {
                    discoveryPreviewBaseResult = baseResult
                    val preview = buildDiscoveryPreview(baseResult).copy(sharedImport = true)
                    val enriched = preview.result
                    if (enriched == null || subscriptionTarget(enriched) == null) {
                        preview.copy(
                            loading = false,
                            error = "Der Link führt zu einem einzelnen Titel/Video oder einer nicht abonnierbaren Quelle.",
                        )
                    } else {
                        // Selecting this app from the share sheet is an explicit import action.
                        // Only a verified container reaches this point; individual tracks/videos
                        // are deliberately rejected above.
                        val added = if (enriched.music.group == ResultGroup.OTHER) {
                            _categoryChoice.value = CategoryChoiceUi(enriched, null)
                            false
                        } else {
                            subscribeInternal(enriched, category = PodcastCategory.MUSIC) != null
                        }
                        preview.copy(loading = false, automaticallyAdded = added)
                    }
                }
            } catch (error: Throwable) {
                DiscoveryPreviewUiState(
                    error = error.message ?: "Der geteilte Link konnte nicht verarbeitet werden.",
                    sharedImport = true,
                )
            }
            if (discoveryPreviewGeneration.get() == generation) _discoveryPreview.value = next
        }
    }

    fun consumeSharedImportNavigation(value: Long) {
        if (_sharedImportNavigation.value == value) _sharedImportNavigation.value = 0L
    }

    private suspend fun buildDiscoveryPreview(
        result: DiscoveryResult,
        requestedTarget: IntegrationTarget? = null,
    ): DiscoveryPreviewUiState {
        val availableTargets = subscriptionTargets(result)
        val target = requestedTarget?.let { requested -> availableTargets.firstOrNull { it == requested } }
            ?: availableTargets.firstOrNull()
            ?: return DiscoveryPreviewUiState(
                result = result,
                availableTargets = availableTargets,
                error = "Für diesen Treffer gibt es keine abonnierbare Show-/Playlist-Quelle.",
            )
        // YouTube's public Atom feeds intentionally expose only a small recent window. Keep the
        // platform URL as the subscription source and let NewPipe pagination provide the listing.
        val feedUrl = if (target.kind in YOUTUBE_PLATFORM_TARGET_KINDS) {
            null
        } else {
            target.feedUrl ?: target.url.takeIf {
                target.kind in setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED)
            }
        }
        val normalizedFeedUrl = feedUrl?.let(TextTools::normalizeUrl)
        var verification = result.feedVerification?.takeIf { candidate ->
            normalizedFeedUrl != null && listOfNotNull(candidate.requestedUrl, candidate.finalUrl)
                .mapNotNull(TextTools::normalizeUrl)
                .any { it == normalizedFeedUrl }
        }
        if (verification == null && feedUrl != null) {
            verification = runCatching { AppGraph.discovery.resolveUrl(feedUrl) }
                .getOrNull()
                ?.feedVerifications
                ?.sortedByDescending { candidate ->
                    when (candidate.status) {
                        FeedStatus.VALID_AUDIO_FEED -> 3
                        FeedStatus.VALID_VIDEO_FEED -> 2
                        FeedStatus.VALID_FEED_WITHOUT_MEDIA -> 1
                        else -> 0
                    }
                }
                ?.firstOrNull()
        }

        var feedPreview: FeedPreview? = null
        var feedPreviewError: String? = null
        if (feedUrl != null && verification?.episodeTitles.isNullOrEmpty()) {
            runCatching { AppGraph.feeds.preview(feedUrl, result.title, maxEpisodes = 20) }
                .onSuccess { feedPreview = it }
                .onFailure { feedPreviewError = it.message ?: "RSS-Vorschau fehlgeschlagen" }
        }

        val listingResult = if (target.kind in PLATFORM_PREVIEW_TARGET_KINDS) {
            AppGraph.platformRefresh.preview(showType(target), target.url, maxItems = 20)
        } else {
            null
        }
        val listing = listingResult?.getOrNull()
        val enriched = enrichDiscoveryResult(result, listing, verification, feedPreview)
        val previewError = listingResult?.exceptionOrNull()?.message
            ?.takeIf { listing == null && verification == null }
            ?: feedPreviewError?.takeIf {
                feedPreview == null && verification?.episodeTitles.isNullOrEmpty()
            }
        return DiscoveryPreviewUiState(
            loading = false,
            result = enriched,
            availableTargets = availableTargets,
            selectedTarget = target,
            listing = listing,
            feedVerification = verification,
            feedPreview = feedPreview,
            error = previewError,
        )
    }

    private fun enrichDiscoveryResult(
        result: DiscoveryResult,
        listing: PlatformListing?,
        verification: FeedVerification?,
        feedPreview: FeedPreview?,
    ): DiscoveryResult {
        val description = listing?.description?.takeIf { it.isNotBlank() }
            ?: feedPreview?.description?.takeIf { it.isNotBlank() }
            ?: verification?.description?.takeIf { it.isNotBlank() }
            ?: result.description
        return result.copy(
            title = listing?.title?.takeIf { it.isNotBlank() }
                ?: feedPreview?.title?.takeIf { it.isNotBlank() }
                ?: verification?.title?.takeIf { it.isNotBlank() }
                ?: result.title,
            publisher = listing?.publisher?.takeIf { it.isNotBlank() } ?: result.publisher,
            description = description,
            artworkUrl = listing?.artworkUrl ?: feedPreview?.artworkUrl ?: verification?.imageUrl ?: result.artworkUrl,
            feedVerification = verification,
        )
    }

    fun subscribe(result: DiscoveryResult, selectedTarget: IntegrationTarget? = null) {
        if (result.music.group == ResultGroup.OTHER) {
            _categoryChoice.value = CategoryChoiceUi(result, selectedTarget)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            subscribeInternal(result, selectedTarget, PodcastCategory.MUSIC)
        }
    }

    fun confirmSubscriptionCategory(category: PodcastCategory) {
        val choice = _categoryChoice.value ?: return
        _categoryChoice.value = null
        viewModelScope.launch(Dispatchers.IO) {
            subscribeInternal(choice.result, choice.target, category)
        }
    }

    fun dismissSubscriptionCategory() { _categoryChoice.value = null }

    private suspend fun subscribeInternal(
        result: DiscoveryResult,
        selectedTarget: IntegrationTarget? = null,
        category: PodcastCategory,
    ): ShowEntity? {
        // Discovery can also surface individual videos/tracks/cloudcasts. Only containers are
        // allowed to become a ShowEntity; an individual episode must never appear as a podcast.
        val target = selectedTarget?.let { requested -> subscriptionTargets(result).firstOrNull { it == requested } }
            ?: subscriptionTarget(result)
        if (target == null) {
            _messages.emit("Für diese Show wurde keine abonnierbare Quelle gefunden.")
            return null
        }
        val feedUrl = if (target.kind in YOUTUBE_PLATFORM_TARGET_KINDS) {
            null
        } else {
            target.feedUrl ?: target.url.takeIf {
                target.kind in setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED)
            }
        }
        findExistingShow(result, target)?.let { existing ->
            // This also covers hidden preset/legacy shows. Re-enable the existing row instead of
            // creating a second show, so its episodes, playback progress and custom order survive.
            if (!existing.subscribed) showDao.setSubscribed(existing.id, true)
            _messages.emit("${existing.title} ist bereits in deinen Shows.")
            return existing
        }
        val show = ShowEntity(
            id = sha256(target.stableId ?: feedUrl ?: target.url),
            title = result.title,
            feedUrl = feedUrl,
            platformUrl = target.url.takeIf { target.kind !in setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED) },
            sourceType = showType(target),
            description = result.description.orEmpty(),
            artworkUrl = result.artworkUrl,
            subscribed = true,
            category = category,
            categoryUserAssigned = result.music.group == ResultGroup.OTHER,
            origin = ShowOrigin.USER,
            hideFromLatest = target.kind == TargetKind.SPOTIFY_PLAYLIST,
            latestMode = if (target.kind == TargetKind.SPOTIFY_PLAYLIST) LatestMode.NONE else LatestMode.ALL,
            // New subscriptions belong at the top. Negative values are intentional and preserve
            // every existing custom position without rewriting the full list on each add.
            sortOrder = (showDao.minSubscribedSortOrder(category).toLong() - 1L)
                .coerceAtLeast(Int.MIN_VALUE.toLong())
                .toInt(),
        )
        showDao.upsert(show)
        _messages.emit(
            if (target.kind == TargetKind.SPOTIFY_PLAYLIST) {
                "Spotify-Link zu ${result.title} hinzugefügt"
            } else {
                "${result.title} abonniert"
            },
        )
        // Subscription itself is immediate. Metadata/episodes can finish in the background, so a
        // share-sheet import never looks frozen while a platform performs another network call.
        if (target.kind == TargetKind.SPOTIFY_PLAYLIST) {
            viewModelScope.launch(Dispatchers.IO) { ShowArtworkCache.prefetchShow(getApplication(), show.id) }
            return show
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (feedUrl != null) {
                val feedRefresh = runCatching { AppGraph.feeds.refresh(show) }
                if (feedRefresh.isFailure && show.platformUrl != null) AppGraph.platformRefresh.refresh(show)
            } else {
                AppGraph.platformRefresh.refresh(show)
            }
            ShowArtworkCache.prefetchShow(getApplication(), show.id)
        }
        return show
    }

    private fun subscriptionTarget(result: DiscoveryResult): IntegrationTarget? =
        subscriptionTargets(result).firstOrNull()

    private fun subscriptionTargets(result: DiscoveryResult): List<IntegrationTarget> =
        (listOfNotNull(result.preferredTarget) + result.targets)
            .distinctBy { "${it.kind}:${it.url}" }
            .filter { target ->
                target.kind in SUBSCRIBABLE_CONTAINER_KINDS ||
                    (target.kind == TargetKind.APPLE_PODCAST && !target.feedUrl.isNullOrBlank())
            }

    private fun showType(target: IntegrationTarget): ShowSourceType = when (target.kind) {
        TargetKind.YOUTUBE_CHANNEL -> ShowSourceType.YOUTUBE_CHANNEL
        TargetKind.YOUTUBE_PLAYLIST -> ShowSourceType.YOUTUBE_PLAYLIST
        TargetKind.SOUNDCLOUD_PROFILE, TargetKind.SOUNDCLOUD_PLAYLIST -> ShowSourceType.SOUNDCLOUD
        TargetKind.MIXCLOUD_PROFILE -> ShowSourceType.MIXCLOUD
        TargetKind.SPOTIFY_PLAYLIST -> ShowSourceType.SPOTIFY_PLAYLIST
        else -> if (target.feedUrl != null || target.kind in setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED)) {
            ShowSourceType.RSS
        } else ShowSourceType.PLATFORM_LINK
    }

    private suspend fun findExistingShow(result: DiscoveryResult, selectedTarget: IntegrationTarget): ShowEntity? {
        // A merged discovery result may deliberately contain RSS + YouTube + SoundCloud variants.
        // Duplicate detection must compare the source the user actually selected, not every other
        // source that happened to be merged into the same search card.
        val candidateSources = listOfNotNull(selectedTarget.feedUrl, selectedTarget.url)
            .mapNotNull(::canonicalSourceIdentity)
            .toSet()
        val candidateTitle = TextTools.normalizeText(result.title)
        val candidateShowType = showType(selectedTarget)

        return showDao.getAll().firstOrNull { existing ->
            val sourceMatch = listOfNotNull(existing.feedUrl, existing.platformUrl)
                .mapNotNull(::canonicalSourceIdentity)
                .any(candidateSources::contains)
            // For direct platforms, the URL is the subscription identity: two playlists, channel
            // tabs or profiles with the same display title may legitimately expose different
            // catalogues. RSS keeps the conservative title fallback for moved feed URLs.
            val titleMatch = candidateShowType == ShowSourceType.RSS &&
                existing.sourceType == ShowSourceType.RSS &&
                candidateTitle.length >= 4 &&
                TextTools.normalizeText(existing.title) == candidateTitle
            sourceMatch || titleMatch
        }
    }

    /**
     * Feed URLs often change only from http to https (or gain/remove www). Those spellings are
     * the same subscription identity. TextTools also normalizes paths, query ordering and common
     * tracking parameters before the scheme is intentionally ignored here.
     */
    private fun canonicalSourceIdentity(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("spotify:", ignoreCase = true)) return raw.trim().lowercase()
        val normalized = TextTools.normalizeUrl(raw) ?: return null
        return normalized.substringAfter("://").removePrefix("www.")
    }

    private suspend fun announceRefresh(summary: RefreshSummary, platform: Pair<Int, Int>) {
        val ok = summary.succeeded + platform.first
        val failed = summary.failed + platform.second
        _messages.emit("$ok Shows aktualisiert${if (failed > 0) ", $failed fehlgeschlagen" else ""}")
    }

    fun togglePlayer() = playerConnection.togglePlayPause()
    fun seekPlayerBy(ms: Long) = playerConnection.seekBy(ms)
    fun seekPlayerTo(ms: Long) = playerConnection.seekTo(ms)
    fun setPlayerSpeed(speed: Float) = playerConnection.setSpeed(speed)
    fun playerNext() = playerConnection.next()
    fun playerPrevious() = playerConnection.previous()

    override fun onCleared() {
        discoveryGeneration.incrementAndGet()
        discoveryPreviewGeneration.incrementAndGet()
        activeDiscoveryTask?.cancel()
        discoveryJob?.cancel()
        discoveryPreviewJob?.cancel()
        artworkWarmJob?.cancel()
        playerConnection.close()
        super.onCleared()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun readShowViewText(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: error("Importdatei konnte nicht geöffnet werden")
        return input.reader(Charsets.UTF_8).buffered().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length + count > MAX_SHOW_VIEW_IMPORT_CHARS) {
                    throw IllegalArgumentException("Importdatei ist zu groß")
                }
                output.append(buffer, 0, count)
            }
            output.toString()
        }
    }

    private fun showSourceKey(sourceType: ShowSourceType, feedUrl: String?, platformUrl: String?): String? {
        val source = platformUrl ?: feedUrl ?: return null
        val normalized = TextTools.normalizeUrl(source) ?: source.trim().lowercase()
        return "${sourceType.name}:$normalized"
    }

    private fun isDirectSourceInput(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("spotify:", ignoreCase = true)

    private companion object {
        const val MAX_SHOW_VIEW_IMPORT_CHARS = 4_000_000
        val YOUTUBE_PLATFORM_TARGET_KINDS = setOf(
            TargetKind.YOUTUBE_CHANNEL,
            TargetKind.YOUTUBE_PLAYLIST,
        )
        val PLATFORM_PREVIEW_TARGET_KINDS = setOf(
            TargetKind.YOUTUBE_CHANNEL,
            TargetKind.YOUTUBE_PLAYLIST,
            TargetKind.SOUNDCLOUD_PROFILE,
            TargetKind.SOUNDCLOUD_PLAYLIST,
            TargetKind.MIXCLOUD_PROFILE,
            TargetKind.SPOTIFY_PLAYLIST,
        )
        val SUBSCRIBABLE_CONTAINER_KINDS = setOf(
            TargetKind.RSS_AUDIO,
            TargetKind.RSS_VIDEO,
            TargetKind.ATOM_FEED,
            TargetKind.YOUTUBE_CHANNEL,
            TargetKind.YOUTUBE_PLAYLIST,
            TargetKind.SPOTIFY_PLAYLIST,
            TargetKind.SOUNDCLOUD_PROFILE,
            TargetKind.SOUNDCLOUD_PLAYLIST,
            TargetKind.MIXCLOUD_PROFILE,
        )
    }
}
