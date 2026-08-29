package de.rdoe.weeklydjshows

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.core.content.ContextCompat
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.discovery.DiscoveryGenres
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.playback.PlayerUiState
import de.rdoe.weeklydjshows.uicomponents.*
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.ResolverErrorType
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.model.StreamingQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ROUTE_SHOWS = "shows"
private const val ROUTE_LATEST = "latest"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_PLAYER = "player"
private const val ROUTE_QUEUE = "queue"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SHOW_ORDER = "show-order"
private const val ROUTE_BLUETOOTH = "bluetooth-automation"
private const val ROUTE_MINI_PLAYER = "mini-player-settings"
private const val ROUTE_TRACKLISTS = "1001tracklists"
private const val ROUTE_DISCOVERY_PREVIEW = "discovery-preview"
private const val ROUTE_SHOW = "show/{showId}"
private const val ROUTE_EPISODE = "episode/{episodeId}"
private val TOP_LEVEL_ROUTES = setOf(ROUTE_SHOWS, ROUTE_LATEST, ROUTE_SEARCH, ROUTE_LIBRARY)

private fun concreteRoute(entry: NavBackStackEntry?): String? = when (entry?.destination?.route) {
    ROUTE_SHOW -> entry.arguments?.getString("showId")?.let { "show/$it" }
    ROUTE_EPISODE -> entry.arguments?.getString("episodeId")?.let { "episode/$it" }
    else -> entry?.destination?.route
}

private data class ReturnScrollPosition(val index: Int, val offset: Int, val itemKey: String?)

private fun LazyGridState.captureReturnPosition(): ReturnScrollPosition {
    val key = layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == firstVisibleItemIndex }
        ?.key as? String
    return ReturnScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset, key)
}

private fun LazyListState.captureReturnPosition(): ReturnScrollPosition {
    val key = layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == firstVisibleItemIndex }
        ?.key as? String
    return ReturnScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset, key)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyDjShowsUi(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val player by vm.player.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val currentPlayerEpisodeFlow = remember(player.mediaId) { vm.episode(player.mediaId.orEmpty()) }
    val currentPlayerEpisode by currentPlayerEpisodeFlow.collectAsStateWithLifecycle(initialValue = null)
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val backgroundSyncing by AppSyncStatus.running.collectAsStateWithLifecycle()
    val failure by vm.playbackFailure.collectAsStateWithLifecycle()
    val categoryChoice by vm.categoryChoice.collectAsStateWithLifecycle()
    val startupOffer by vm.startupOffer.collectAsStateWithLifecycle()
    val maintenanceNotice by vm.maintenanceNotice.collectAsStateWithLifecycle()
    val catalogUpdate by vm.catalogUpdate.collectAsStateWithLifecycle()
    val sharedImportNavigation by vm.sharedImportNavigation.collectAsStateWithLifecycle()
    var tracklistsWebView by remember { mutableStateOf<WebView?>(null) }
    var tracklistsCanGoBack by remember { mutableStateOf(false) }
    var tracklistsCanGoForward by remember { mutableStateOf(false) }
    var showCastOptions by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    // Keep top-level list states outside the NavHost destinations. Navigating to a detail route
    // can dispose that destination's composition, but these states remain alive for Back.
    val showsScrollState = rememberLazyGridState()
    val latestScrollState = rememberLazyListState()
    val searchLocalScrollState = rememberLazyListState()
    val searchDiscoveryScrollState = rememberLazyListState()
    val likedScrollState = rememberLazyListState()
    val downloadsScrollState = rememberLazyListState()
    val historyScrollState = rememberLazyListState()
    val queueScrollState = rememberLazyListState()
    val settingsScrollState = rememberLazyListState()
    val showOrderScrollState = rememberLazyListState()
    val bluetoothScrollState = rememberLazyListState()
    val miniPlayerSettingsScrollState = rememberLazyListState()
    val showDetailScrollStates = remember { mutableMapOf<String, LazyListState>() }
    val showDetailRestoreRequests = remember { mutableStateMapOf<String, ReturnScrollPosition>() }
    val episodeDetailScrollStates = remember { mutableMapOf<String, LazyListState>() }
    var previousRoute by remember { mutableStateOf<String?>(null) }
    var showsSavedPosition by remember { mutableStateOf<ReturnScrollPosition?>(null) }
    var latestSavedPosition by remember { mutableStateOf<ReturnScrollPosition?>(null) }
    var showsRestoreRequest by remember { mutableStateOf<ReturnScrollPosition?>(null) }
    var latestRestoreRequest by remember { mutableStateOf<ReturnScrollPosition?>(null) }
    val navigationTrail = remember { mutableStateListOf<String>() }
    var startupScreenApplied by rememberSaveable { mutableStateOf(false) }

    if (mainActivity?.isInMiniPlayerMode == true) {
        val pipWidthDp = LocalConfiguration.current.screenWidthDp
        LaunchedEffect(
            player.isPlaying,
            player.hasPrevious,
            player.hasNext,
            appSettings.miniPlayerControls,
            pipWidthDp,
        ) {
            mainActivity.updateMiniPlayerActions(
                controls = appSettings.miniPlayerControls,
                isPlaying = player.isPlaying,
                hasPrevious = player.hasPrevious,
                hasNext = player.hasNext,
                windowWidthDp = pipWidthDp,
            )
        }
        PipPlayerContent(
            player = player,
            currentEpisode = currentPlayerEpisode,
            syncing = syncing || backgroundSyncing,
        )
        return
    }

    fun navigateAcyclic(destination: String) {
        val existingIndex = navigationTrail.indexOf(destination)
        if (existingIndex < 0) {
            nav.navigate(destination) { launchSingleTop = true }
            return
        }
        // A destination already on our actual path is revealed instead of pushed again. This
        // preserves Episode -> Show -> Back -> Episode while preventing A -> B -> A -> B loops.
        repeat((navigationTrail.lastIndex - existingIndex).coerceAtLeast(0)) {
            if (nav.popBackStack() && navigationTrail.isNotEmpty()) {
                navigationTrail.removeAt(navigationTrail.lastIndex)
            }
        }
    }

    fun navigateToShow(showId: String) {
        if (showId == BundledShowLayoutV128.TRACKLISTS_ID) {
            navigateAcyclic(ROUTE_TRACKLISTS)
        } else {
            navigateAcyclic("show/$showId")
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(startupScreenApplied) {
        if (!startupScreenApplied) {
            startupScreenApplied = true
            if (appSettings.startupScreen == StartupScreen.LATEST &&
                nav.currentDestination?.route == ROUTE_SHOWS
            ) {
                nav.navigate(ROUTE_LATEST) { launchSingleTop = true }
            }
        }
    }

    // Bluetooth may choose what is played independently from what the foreground should show.
    // Never open an empty player: the request remains pending until autoplay/restoration has
    // produced a real media item, or is consumed immediately for the start-screen/offer modes.
    LaunchedEffect(mainActivity?.bluetoothDisplayRequest, player.mediaId) {
        when (mainActivity?.bluetoothDisplayRequest) {
            BluetoothDisplayMode.START_SCREEN,
            BluetoothDisplayMode.OFFER -> mainActivity.consumeBluetoothDisplayRequest()
            BluetoothDisplayMode.PLAYER_IF_AVAILABLE -> if (player.mediaId != null) {
                navigateAcyclic(ROUTE_PLAYER)
                mainActivity.consumeBluetoothDisplayRequest()
            }
            null -> Unit
        }
    }

    LaunchedEffect(backStack) {
        val current = concreteRoute(backStack) ?: return@LaunchedEffect
        if (route in TOP_LEVEL_ROUTES) {
            // Bottom navigation deliberately is not a Back history. Its actual graph always
            // starts at Shows and has at most the selected top-level destination above it.
            navigationTrail.clear()
            navigationTrail += ROUTE_SHOWS
            if (current != ROUTE_SHOWS) navigationTrail += current
            return@LaunchedEffect
        }
        val earlier = navigationTrail.indexOf(current)
        when {
            earlier >= 0 -> while (navigationTrail.lastIndex > earlier) {
                navigationTrail.removeAt(navigationTrail.lastIndex)
            }
            navigationTrail.lastOrNull() != current -> navigationTrail += current
        }
    }

    // Capture both index/offset and the stable show/episode key while leaving. On return the screen
    // resolves that key against its current filtered data before scrolling, so newly inserted
    // episodes cannot shift the user to a different item.
    LaunchedEffect(route) {
        val previous = previousRoute
        if (previous == ROUTE_SHOWS) {
            showsSavedPosition = showsScrollState.captureReturnPosition()
        }
        if (previous == ROUTE_LATEST) {
            latestSavedPosition = latestScrollState.captureReturnPosition()
        }
        previousRoute = route
        if (route == ROUTE_SHOWS && previous != ROUTE_SHOWS) {
            showsRestoreRequest = showsSavedPosition
        }
        if (route == ROUTE_LATEST && previous != ROUTE_LATEST) {
            latestRestoreRequest = latestSavedPosition
        }
    }

    LaunchedEffect(sharedImportNavigation) {
        if (sharedImportNavigation > 0L) {
            navigateAcyclic(ROUTE_DISCOVERY_PREVIEW)
            vm.consumeSharedImportNavigation(sharedImportNavigation)
        }
    }

    if (failure != null) {
        AlertDialog(
            onDismissRequest = vm::clearPlaybackFailure,
            icon = { Icon(Icons.Default.ErrorOutline, null) },
            title = { Text("Wiedergabe nicht möglich") },
            text = { Text(failure!!.error.message) },
            confirmButton = {
                if (failure!!.error.type == ResolverErrorType.NOT_YET_AVAILABLE) {
                    TextButton(onClick = vm::clearPlaybackFailure) { Text("Verstanden") }
                } else {
                    TextButton(onClick = { vm.openCurrentFailureExternally(context) }) {
                        Text(
                            if (failure!!.error.originalUrl?.contains("open.spotify.com", ignoreCase = true) == true) {
                                "In Spotify öffnen"
                            } else if (failure!!.error.originalUrl?.contains("mixcloud.com", ignoreCase = true) == true) {
                                "In Mixcloud öffnen"
                            } else {
                                "In NewPipe öffnen"
                            },
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = vm::clearPlaybackFailure) { Text("Schließen") } },
        )
    }

    if (catalogUpdate != null && failure == null) {
        val update = catalogUpdate!!
        val preview = update.preview
        if (update.acceptedStandardOrder == null) {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Reorder, null) },
                title = { Text("Show-Liste ${preview.version} übernehmen?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Neue und geänderte Standardblöcke können übernommen werden. Eigene Verschiebungen bleiben zwischen ihren bisherigen Nachbarabschnitten.")
                        preview.addedTitles.take(8).forEach { Text("+ $it", color = BrandGreen) }
                        preview.reorderedTitles.take(8).forEach { Text("↕ $it", color = MaterialTheme.colorScheme.secondary) }
                        if (preview.addedTitles.size + preview.reorderedTitles.size > 16) {
                            Text("Weitere Änderungen sind in der neuen Standardliste enthalten.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.chooseCatalogOrder(true) }) { Text("Neue Reihenfolge") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.chooseCatalogOrder(false) }) { Text("Alte behalten") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Outlined.DeleteSweep, null) },
                title = { Text("Entfallene Shows entfernen?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Diese Shows gehören nicht mehr zur App-Standardliste:")
                        preview.retiredTitles.take(12).forEach { Text("− $it") }
                        Text("Beim Behalten werden sie als selbst hinzugefügte Shows weitergeführt.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.finishCatalogUpdate(true) }) { Text("Entfernen") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.finishCatalogUpdate(false) }) { Text("Als eigene behalten") }
                },
            )
        }
    }

    if (startupOffer != null && failure == null && catalogUpdate == null) {
        val offer = startupOffer!!
        val media = offer.media
        Dialog(
            onDismissRequest = vm::dismissStartupOffer,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                        Text(
                            if (offer.kind == StartupOfferKind.INTERRUPTED) "Folge fortsetzen?" else "Neueste Folge abspielen?",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(16.dp))
                        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                EpisodeArtwork(media, Modifier.size(82.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(media.episode.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        media.show.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val duration = media.episode.playbackDurationMs ?: media.episode.durationMs
                                    if (duration != null && duration > 0L) {
                                        Spacer(Modifier.height(7.dp))
                                        LinearProgressIndicator(
                                            progress = { (media.episode.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                                            Modifier.fillMaxWidth().height(3.dp),
                                            color = BrandPink,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = vm::dismissStartupOffer) { Text("Später") }
                            TextButton(onClick = vm::playStartupOffer) {
                                Text(if (offer.kind == StartupOfferKind.INTERRUPTED) "Fortsetzen" else "Abspielen")
                            }
                        }
                    }
                }
            }
        }
    }

    if (maintenanceNotice != null && failure == null && startupOffer == null && catalogUpdate == null) {
        AlertDialog(
            onDismissRequest = vm::dismissMaintenanceNotice,
            icon = {
                Icon(
                    if (maintenanceNotice!!.kind == MaintenanceKind.APP_UPDATE) Icons.Outlined.SystemUpdate
                    else Icons.Outlined.Construction,
                    null,
                )
            },
            title = { Text(maintenanceNotice!!.title) },
            text = { Text(maintenanceNotice!!.message) },
            confirmButton = {
                TextButton(onClick = { vm.performMaintenanceAction(context) }) {
                    Text(maintenanceNotice!!.primaryLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissMaintenanceNotice) { Text("Später") }
            },
        )
    }

    if (categoryChoice != null) {
        AlertDialog(
            onDismissRequest = vm::dismissSubscriptionCategory,
            icon = { Icon(Icons.Outlined.Podcasts, null) },
            title = { Text("Podcast-Kategorie wählen") },
            text = {
                Text("„${categoryChoice!!.result.title}“ wurde nicht eindeutig als Musik-Podcast erkannt.")
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmSubscriptionCategory(PodcastCategory.WORD) }) {
                    Text("Wort-Podcast")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.confirmSubscriptionCategory(PodcastCategory.MUSIC) }) {
                        Text("Musik-Podcast")
                    }
                    TextButton(onClick = vm::dismissSubscriptionCategory) { Text("Abbrechen") }
                }
            },
        )
    }

    if (showCastOptions) {
        CastOptionsDialog(
            isRemote = player.isRemote,
            onDismiss = { showCastOptions = false },
            onOpenMiracast = {
                showCastOptions = false
                if (!openSystemCastSettings(context)) {
                    Toast.makeText(
                        context,
                        "Bitte Smart View / Bildschirm übertragen in den Schnelleinstellungen öffnen.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        when (route) {
                            ROUTE_LATEST -> "Neuerscheinungen"
                            ROUTE_SEARCH -> "Suchen & Entdecken"
                            ROUTE_LIBRARY -> "Bibliothek"
                            ROUTE_PLAYER -> "Player"
                            ROUTE_QUEUE -> "Warteschlange"
                            ROUTE_SETTINGS -> "Einstellungen"
                            ROUTE_SHOW_ORDER -> "Show-Reihenfolge"
                            ROUTE_BLUETOOTH -> "Bluetooth-Automatik"
                            ROUTE_MINI_PLAYER -> "Mini-Player"
                            ROUTE_TRACKLISTS -> "1001Tracklists"
                            ROUTE_DISCOVERY_PREVIEW -> "Show-Vorschau"
                            ROUTE_EPISODE -> "Folge"
                            ROUTE_SHOW -> "Show"
                            else -> "Weekly DJ Shows"
                        },
                    )
                },
                navigationIcon = {
                    if (route == ROUTE_TRACKLISTS) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Default.Close, "Webseite schließen")
                        }
                    } else if (route in setOf(ROUTE_SHOW, ROUTE_EPISODE, ROUTE_PLAYER, ROUTE_QUEUE, ROUTE_SETTINGS, ROUTE_SHOW_ORDER, ROUTE_BLUETOOTH, ROUTE_MINI_PLAYER, ROUTE_DISCOVERY_PREVIEW)) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Zurück") }
                    }
                },
                actions = {
                    if (route == ROUTE_PLAYER) {
                        IconButton(onClick = { showCastOptions = true }) {
                            Icon(
                                imageVector = if (player.isRemote) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
                                contentDescription = if (player.isRemote) {
                                    "Cast-Verbindung verwalten"
                                } else {
                                    "Auf Fernseher abspielen"
                                },
                            )
                        }
                    }
                    if (route == ROUTE_TRACKLISTS) {
                        IconButton(
                            onClick = { tracklistsWebView?.goBack() },
                            enabled = tracklistsCanGoBack,
                        ) { Icon(Icons.Default.ArrowBack, "Webseite zurück") }
                        IconButton(
                            onClick = { tracklistsWebView?.goForward() },
                            enabled = tracklistsCanGoForward,
                        ) { Icon(Icons.Default.ArrowForward, "Webseite vor") }
                    }
                    if (route == ROUTE_SHOWS || route == ROUTE_LATEST) {
                        if (syncing) CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        ) else IconButton(onClick = vm::refreshAll) {
                            Icon(Icons.Default.Refresh, "Alle Shows aktualisieren")
                        }
                    }
                    if (route in setOf(ROUTE_SHOWS, ROUTE_LATEST, ROUTE_SEARCH, ROUTE_LIBRARY)) {
                        IconButton(onClick = { navigateAcyclic(ROUTE_SETTINGS) }) {
                            Icon(Icons.Outlined.Settings, "Einstellungen")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (player.mediaId != null && route != ROUTE_PLAYER) {
                    MiniPlayer(
                        title = player.title,
                        artist = player.artist,
                        artworkUrl = player.artworkUrl,
                        isPlaying = player.isPlaying,
                        positionMs = player.positionMs,
                        durationMs = player.durationMs,
                        liked = currentPlayerEpisode?.episode?.liked == true,
                        hasPrevious = player.hasPrevious,
                        hasNext = player.hasNext,
                        onToggle = vm::togglePlayer,
                        onLike = { player.mediaId?.let(vm::toggleLike) },
                        onPrevious = vm::playerPrevious,
                        onNext = vm::playerNext,
                        onOpen = { navigateAcyclic(ROUTE_PLAYER) },
                    )
                }
                if (route in setOf(ROUTE_SHOWS, ROUTE_LATEST, ROUTE_SEARCH, ROUTE_LIBRARY)) {
                    AppNavigationBar(route) { destination ->
                        nav.navigate(destination) {
                            // Top-level list scroll state is hoisted above NavHost, so restoring a
                            // saved nested graph is unnecessary. In 1.2.8 restoreState could
                            // restore the startup "Neu" destination when tapping "Shows".
                            popUpTo(ROUTE_SHOWS) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_SHOWS,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_SHOWS) {
                ShowsScreen(
                    vm = vm,
                    state = showsScrollState,
                    restorePosition = showsRestoreRequest,
                    onRestored = { showsRestoreRequest = null },
                    onShow = ::navigateToShow,
                )
            }
            composable(ROUTE_LATEST) {
                LatestScreen(
                    vm,
                    state = latestScrollState,
                    restorePosition = latestRestoreRequest,
                    onRestored = { latestRestoreRequest = null },
                    onEpisode = { navigateAcyclic("episode/$it") },
                    onShow = ::navigateToShow,
                )
            }
            composable(ROUTE_SEARCH) {
                SearchScreen(
                    vm,
                    localListState = searchLocalScrollState,
                    discoveryListState = searchDiscoveryScrollState,
                    onShow = ::navigateToShow,
                    onEpisode = { navigateAcyclic("episode/$it") },
                    onDiscoveryPreview = { navigateAcyclic(ROUTE_DISCOVERY_PREVIEW) },
                )
            }
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    vm,
                    likedListState = likedScrollState,
                    downloadsListState = downloadsScrollState,
                    historyListState = historyScrollState,
                    onEpisode = { navigateAcyclic("episode/$it") },
                    onShow = ::navigateToShow,
                )
            }
            composable(ROUTE_PLAYER) {
                PlayerScreen(
                    player = player,
                    vm = vm,
                    onCollapse = { nav.popBackStack() },
                    onQueue = { navigateAcyclic(ROUTE_QUEUE) },
                    onMiniPlayer = when (appSettings.miniPlayerImplementation) {
                        MiniPlayerImplementation.CUSTOM_OVERLAY -> {
                            {
                                if (OverlayMiniPlayerService.show(context)) {
                                    mainActivity?.moveTaskToBack(true)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                                Unit
                            }
                        }
                        MiniPlayerImplementation.SYSTEM_PIP -> if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                        ) {
                            {
                                mainActivity?.enterMiniPlayer(
                                    controls = appSettings.miniPlayerControls,
                                    isPlaying = player.isPlaying,
                                    hasPrevious = player.hasPrevious,
                                    hasNext = player.hasNext,
                                )
                                Unit
                            }
                        } else null
                    },
                    onEpisode = { navigateAcyclic("episode/$it") },
                    onShow = ::navigateToShow,
                )
            }
            composable(ROUTE_QUEUE) {
                QueueScreen(
                    vm,
                    state = queueScrollState,
                    onEpisode = { navigateAcyclic("episode/$it") },
                    onShow = ::navigateToShow,
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    state = settingsScrollState,
                    onShowOrder = { navigateAcyclic(ROUTE_SHOW_ORDER) },
                    onBluetooth = { navigateAcyclic(ROUTE_BLUETOOTH) },
                    onMiniPlayer = { navigateAcyclic(ROUTE_MINI_PLAYER) },
                )
            }
            composable(ROUTE_SHOW_ORDER) { ShowOrderScreen(vm, showOrderScrollState) }
            composable(ROUTE_BLUETOOTH) { BluetoothAutomationScreen(vm, bluetoothScrollState) }
            composable(ROUTE_MINI_PLAYER) { MiniPlayerSettingsScreen(vm, miniPlayerSettingsScrollState) }
            composable(ROUTE_TRACKLISTS) {
                IntegratedTracklistsScreen { view, canGoBack, canGoForward ->
                    tracklistsWebView = view
                    tracklistsCanGoBack = canGoBack
                    tracklistsCanGoForward = canGoForward
                }
            }
            composable(ROUTE_DISCOVERY_PREVIEW) { DiscoveryPreviewScreen(vm) }
            composable(
                ROUTE_SHOW,
                arguments = listOf(navArgument("showId") { type = NavType.StringType }),
            ) { entry ->
                val showId = entry.arguments?.getString("showId").orEmpty()
                val listState = remember(showId) {
                    showDetailScrollStates.getOrPut(showId) { LazyListState() }
                }
                ShowDetailScreen(
                    vm,
                    showId,
                    state = listState,
                    restorePosition = showDetailRestoreRequests[showId],
                    onRestored = { showDetailRestoreRequests.remove(showId) },
                    onEpisode = { episodeId ->
                        showDetailRestoreRequests[showId] = listState.captureReturnPosition()
                        navigateAcyclic("episode/$episodeId")
                    },
                    onRemoved = { nav.popBackStack() },
                )
            }
            composable(
                ROUTE_EPISODE,
                arguments = listOf(navArgument("episodeId") { type = NavType.StringType }),
            ) { entry ->
                val episodeId = entry.arguments?.getString("episodeId").orEmpty()
                val listState = remember(episodeId) {
                    episodeDetailScrollStates.getOrPut(episodeId) { LazyListState() }
                }
                EpisodeDetailScreen(
                    vm,
                    episodeId,
                    state = listState,
                    onShow = ::navigateToShow,
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun CastOptionsDialog(
    isRemote: Boolean,
    onDismiss: () -> Unit,
    onOpenMiracast: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isRemote) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
                contentDescription = null,
            )
        },
        title = { Text(if (isRemote) "Cast verbunden" else "Auf Fernseher abspielen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Bei Google Cast werden Musik und Cover getrennt übertragen. " +
                        "Der Fernseher kann dadurch Cover, Titel und Interpret anzeigen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Chromecast / Google Cast", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isRemote) "Gerät verbunden – rechts verwalten" else "Gerät auswählen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MediaRouteButton(modifier = Modifier.size(48.dp))
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenMiracast),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.ScreenShare, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fire TV / Smart TV", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Miracast / Smart View – spiegelt Bild und Ton",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Fire TV: zuerst „Display-Spiegelung“ am Stick aktivieren. " +
                        "Auf Samsung-Geräten heißt die Funktion meist Smart View.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

private fun openSystemCastSettings(context: Context): Boolean = runCatching {
    context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
    true
}.getOrDefault(false)

@Composable
private fun AppNavigationBar(current: String?, navigate: (String) -> Unit) {
    val items = listOf(
        Triple(ROUTE_SHOWS, "Shows", Icons.Outlined.Headphones),
        Triple(ROUTE_LATEST, "Neu", Icons.Outlined.NewReleases),
        Triple(ROUTE_LIBRARY, "Bibliothek", Icons.Outlined.LibraryMusic),
        Triple(ROUTE_SEARCH, "Suchen", Icons.Outlined.Search),
    )
    NavigationBar {
        items.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { navigate(route) },
                icon = { Icon(icon, null) },
                label = { Text(label) },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IntegratedTracklistsScreen(
    onNavigationChanged: (WebView?, Boolean, Boolean) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    fun publishNavigationState(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        onNavigationChanged(view, canGoBack, view?.canGoForward() == true)
    }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
        publishNavigationState(webView)
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            onNavigationChanged(null, false, false)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        publishNavigationState(view)
                    }
                }
                loadUrl(BundledShowLayoutV128.TRACKLISTS_URL)
                webView = this
                publishNavigationState(this)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowsScreen(
    vm: MainViewModel,
    state: LazyGridState,
    restorePosition: ReturnScrollPosition?,
    onRestored: () -> Unit,
    onShow: (String) -> Unit,
) {
    val shows by vm.shows.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = remember(shows, settings.showOrderMode, settings.wordPodcastsEnabled, settings.musicPodcastsEnabled) {
        val podcastShows = PodcastCategory.entries.flatMap { category ->
            if ((category == PodcastCategory.WORD && !settings.wordPodcastsEnabled) ||
                (category == PodcastCategory.MUSIC && !settings.musicPodcastsEnabled)
            ) {
                emptyList()
            } else {
                val inCategory = shows.filter { it.category == category }
                val sorted = when (settings.showOrderMode) {
                    ShowOrderMode.CUSTOM -> inCategory
                    ShowOrderMode.ALPHABETICAL -> inCategory.sortedBy { it.title.lowercase(Locale.getDefault()) }
                }
                if (category == PodcastCategory.MUSIC) {
                    sorted.filter { it.id == BundledShowLayoutV128.TRACKLISTS_ID } +
                        sorted.filterNot { it.id == BundledShowLayoutV128.TRACKLISTS_ID }
                } else {
                    sorted
                }
            }
        }
        podcastShows
    }
    val filtered = remember(ordered, query) {
        if (query.isBlank()) ordered else ordered.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) ||
                showSourceLabel(it).contains(query, ignoreCase = true)
        }
    }
    LaunchedEffect(restorePosition, filtered) {
        val restore = restorePosition ?: return@LaunchedEffect
        if (restore.index > 0 && filtered.isEmpty()) return@LaunchedEffect
        val keyedIndex = restore.itemKey?.let { key -> filtered.indexOfFirst { it.id == key } }
            ?.takeIf { it >= 0 }
            ?.plus(1) // filter field occupies item 0
        val count = filtered.size + 1
        val target = (keyedIndex ?: restore.index).coerceIn(0, count - 1)
        state.scrollToItem(target, if (target == keyedIndex || keyedIndex == null && target == restore.index) restore.offset else 0)
        onRestored()
    }
    var contextShowId by remember { mutableStateOf<String?>(null) }
    val warmBucket by remember(state) {
        derivedStateOf { ((state.firstVisibleItemIndex - 1).coerceAtLeast(0)) / 12 }
    }
    LaunchedEffect(filtered, warmBucket) {
        val start = warmBucket * 12
        vm.warmShowArtwork(filtered.drop(start).take(36).mapNotNull { it.artworkUrl })
    }
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = vm::refreshAll,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(80.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CompactFilterField(query, { query = it }, "Shows durchsuchen")
            }
            if (shows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint("Shows werden vorbereitet …") }
            if (shows.isNotEmpty() && filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint("Keine passende Show.") }
            }
            PodcastCategory.entries.forEach { category ->
                val categoryShows = filtered.filter { it.category == category }
                if (categoryShows.isNotEmpty()) {
                    item(key = "category-${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (category == PodcastCategory.WORD) "Wort-Podcasts" else "Musik-Podcasts",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            HorizontalDivider(
                                Modifier.weight(1f),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            )
                        }
                    }
                    gridItems(categoryShows, key = { it.id }) { show ->
                        Box {
                            ShowGridItem(
                                show,
                                onClick = { onShow(show.id) },
                                onLongClick = if (show.id == BundledShowLayoutV128.TRACKLISTS_ID) null else {
                                    { contextShowId = show.id }
                                },
                                artworkOverride = if (show.id == BundledShowLayoutV128.TRACKLISTS_ID) {
                                    { artworkModifier -> TracklistsArtwork(artworkModifier) }
                                } else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = contextShowId == show.id,
                                onDismissRequest = { contextShowId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ganz nach oben verschieben") },
                                    leadingIcon = { Icon(Icons.Default.VerticalAlignTop, null) },
                                    onClick = {
                                        contextShowId = null
                                        vm.moveShowToTop(show.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ganz nach unten verschieben") },
                                    leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, null) },
                                    onClick = {
                                        contextShowId = null
                                        vm.moveShowToBottom(show.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (show.category == PodcastCategory.WORD) {
                                                "Zu Musik-Podcasts"
                                            } else {
                                                "Zu Wort-Podcasts"
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                                    onClick = {
                                        contextShowId = null
                                        vm.switchShowCategory(show.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TracklistsArtwork(modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_1001tracklists_brand),
            contentDescription = "1001Tracklists",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(15.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LatestScreen(
    vm: MainViewModel,
    state: LazyListState,
    restorePosition: ReturnScrollPosition?,
    onRestored: () -> Unit,
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
) {
    val items by vm.latest.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = vm::refreshAll,
        modifier = Modifier.fillMaxSize(),
    ) {
        EpisodeLazyList(
            items,
            vm,
            emptyText = "Noch keine Folgen geladen. Ziehe nach unten, um zu aktualisieren.",
            searchPlaceholder = "Neuerscheinungen filtern",
            state = state,
            restorePosition = restorePosition,
            onRestored = onRestored,
            onEpisode = onEpisode,
            onShow = onShow,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowDetailScreen(
    vm: MainViewModel,
    showId: String,
    state: LazyListState,
    restorePosition: ReturnScrollPosition?,
    onRestored: () -> Unit,
    onEpisode: (String) -> Unit,
    onRemoved: () -> Unit,
) {
    val show by vm.show(showId).collectAsStateWithLifecycle(initialValue = null)
    val episodes by vm.episodes(showId).collectAsStateWithLifecycle(initialValue = emptyList())
    val player by vm.player.collectAsStateWithLifecycle()
    val refreshingShowIds by vm.refreshingShowIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val externalShortcut = show?.sourceType in setOf(ShowSourceType.SPOTIFY_PLAYLIST, ShowSourceType.PLATFORM_LINK)
    var query by rememberSaveable(showId) { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by rememberSaveable(showId) { mutableStateOf("") }
    val filtered = remember(episodes, query) {
        if (query.isBlank()) episodes else episodes.filter {
            it.episode.title.contains(query, true) ||
                it.episode.description.contains(query, true) ||
                sourceLabel(it.episode.sourceType).contains(query, true)
        }
    }

    LaunchedEffect(restorePosition, show, filtered, episodes) {
        val restore = restorePosition ?: return@LaunchedEffect
        val currentShow = show ?: return@LaunchedEffect
        // Flow collection starts with an empty list. Wait for the DB value before consuming a
        // keyed restore request so a brief empty frame cannot reset a deep scroll position.
        if (restore.itemKey != null && episodes.isEmpty()) return@LaunchedEffect
        val prefixItems = 1 + // show header
            (if (currentShow.description.isNotBlank()) 1 else 0) +
            (if (externalShortcut) 0 else 1) // episode filter
        val keyedIndex = restore.itemKey
            ?.let { key -> filtered.indexOfFirst { it.episode.id == key } }
            ?.takeIf { it >= 0 }
            ?.plus(prefixItems)
        val resultItems = when {
            externalShortcut -> 0
            episodes.isEmpty() || filtered.isEmpty() -> 1
            else -> filtered.size
        }
        val count = (prefixItems + resultItems).coerceAtLeast(1)
        val target = (keyedIndex ?: restore.index).coerceIn(0, count - 1)
        state.scrollToItem(
            target,
            if (keyedIndex != null && target == keyedIndex || keyedIndex == null && target == restore.index) {
                restore.offset
            } else {
                0
            },
        )
        onRestored()
    }

    if (confirmRemove && show != null) {
        val item = show!!
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (item.legacyModuleId != null) "Show ausblenden?" else "Show löschen?") },
            text = {
                Text(
                    if (item.legacyModuleId != null) {
                        "${item.title} verschwindet aus deinen Shows und kann in den Einstellungen wieder eingeblendet werden."
                    } else {
                        "${item.title} und seine lokal gespeicherten Feed-Daten werden entfernt."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeShow(item)
                    onRemoved()
                }) { Text(if (item.legacyModuleId != null) "Ausblenden" else "Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Abbrechen") } },
        )
    }

    if (renameDialog && show != null) {
        val item = show!!
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Show umbenennen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Anzeigename") },
                    )
                    Text(
                        "Nur der Name in Weekly DJ Shows ändert sich. Quelle und Plattform-Link bleiben unverändert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameShow(item.id, renameText)
                        renameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Abbrechen") } },
        )
    }

    PullToRefreshBox(
        isRefreshing = showId in refreshingShowIds,
        onRefresh = { if (!externalShortcut) vm.refreshShow(showId) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            show?.let { itemShow ->
                item {
                    Row(verticalAlignment = Alignment.Top) {
                    Artwork(itemShow.artworkUrl, itemShow.title, Modifier.size(100.dp), diskCache = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(itemShow.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(showSourceLabel(itemShow), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (itemShow.sourceType in setOf(ShowSourceType.SPOTIFY_PLAYLIST, ShowSourceType.PLATFORM_LINK)) {
                                        vm.openShowSourceExternally(context, itemShow.id)
                                    } else {
                                        vm.refreshShow(itemShow.id)
                                    }
                                },
                            ) {
                                Icon(
                                    if (itemShow.sourceType in setOf(ShowSourceType.SPOTIFY_PLAYLIST, ShowSourceType.PLATFORM_LINK)) Icons.Default.OpenInNew else Icons.Default.Refresh,
                                    null,
                                    Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    when (itemShow.sourceType) {
                                        ShowSourceType.SPOTIFY_PLAYLIST -> "In Spotify öffnen"
                                        ShowSourceType.PLATFORM_LINK -> "Webseite öffnen"
                                        else -> "Aktualisieren"
                                    },
                                )
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Show verwalten") }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Show umbenennen") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = {
                                            showMenu = false
                                            renameText = itemShow.title
                                            renameDialog = true
                                        },
                                    )
                                    if (itemShow.feedUrl != null || itemShow.platformUrl != null) {
                                        DropdownMenuItem(
                                            text = { Text("Quell-Link kopieren") },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                            onClick = {
                                                showMenu = false
                                                vm.copyShowSourceLink(itemShow.id)
                                            },
                                        )
                                    }
                                    if (itemShow.sourceType !in setOf(ShowSourceType.SPOTIFY_PLAYLIST, ShowSourceType.PLATFORM_LINK)) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Neu: Alle Folgen") },
                                            trailingIcon = {
                                                if (itemShow.latestMode == LatestMode.ALL) Icon(Icons.Default.Check, null)
                                            },
                                            onClick = {
                                                showMenu = false
                                                vm.setLatestMode(itemShow.id, LatestMode.ALL)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Neu: Nur neueste Folge") },
                                            trailingIcon = {
                                                if (itemShow.latestMode == LatestMode.LATEST_ONLY) Icon(Icons.Default.Check, null)
                                            },
                                            onClick = {
                                                showMenu = false
                                                vm.setLatestMode(itemShow.id, LatestMode.LATEST_ONLY)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Neu: Nicht anzeigen") },
                                            trailingIcon = {
                                                if (itemShow.latestMode == LatestMode.NONE) Icon(Icons.Default.Check, null)
                                            },
                                            onClick = {
                                                showMenu = false
                                                vm.setLatestMode(itemShow.id, LatestMode.NONE)
                                            },
                                        )
                                    }
                                    if (itemShow.sourceType == ShowSourceType.RSS && itemShow.feedUrl != null) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Beim Aktualisieren bereinigen") },
                                            leadingIcon = { Icon(Icons.Default.AutoDelete, null) },
                                            trailingIcon = {
                                                if (itemShow.autoPruneMissingEpisodes) Icon(Icons.Default.Check, null)
                                            },
                                            onClick = {
                                                showMenu = false
                                                vm.setAutoPruneMissingEpisodes(itemShow.id, !itemShow.autoPruneMissingEpisodes)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Jetzt veraltete Folgen entfernen") },
                                            leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                                            onClick = {
                                                showMenu = false
                                                vm.cleanupMissingEpisodes(itemShow.id)
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (itemShow.legacyModuleId != null) "Show ausblenden" else "Show löschen") },
                                        leadingIcon = { Icon(if (itemShow.legacyModuleId != null) Icons.Default.VisibilityOff else Icons.Default.Delete, null) },
                                        onClick = {
                                            showMenu = false
                                            confirmRemove = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
                if (itemShow.description.isNotBlank()) {
                    item {
                        ExpandableDescription(itemShow.description, collapsedLines = 3)
                    }
                }
                if (!externalShortcut) {
                    item { CompactFilterField(query, { query = it }, "Folgen dieser Show filtern") }
                }
            }
            if (!externalShortcut) {
                if (episodes.isEmpty()) item { EmptyHint("Noch keine Folgen lokal gespeichert.") }
                if (episodes.isNotEmpty() && filtered.isEmpty()) item { EmptyHint("Keine passende Folge.") }
                items(filtered, key = { it.episode.id }) {
                    EpisodeRow(it, vm, player, onEpisode)
                }
            }
        }
    }
}

@Composable
private fun EpisodeLazyList(
    items: List<EpisodeWithShow>,
    vm: MainViewModel,
    emptyText: String,
    searchPlaceholder: String = "Folgen filtern",
    state: LazyListState = rememberLazyListState(),
    restorePosition: ReturnScrollPosition? = null,
    onRestored: () -> Unit = {},
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player by vm.player.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isBlank()) items else items.filter {
            it.episode.title.contains(query, true) ||
                it.episode.description.contains(query, true) ||
                it.show.title.contains(query, true) ||
                sourceLabel(it.episode.sourceType).contains(query, true)
        }
    }
    LaunchedEffect(restorePosition, filtered) {
        val restore = restorePosition ?: return@LaunchedEffect
        if (restore.index > 0 && filtered.isEmpty()) return@LaunchedEffect
        val keyedIndex = restore.itemKey?.let { key -> filtered.indexOfFirst { it.episode.id == key } }
            ?.takeIf { it >= 0 }
            ?.plus(1) // filter field occupies item 0
        val count = filtered.size + 1
        val target = (keyedIndex ?: restore.index).coerceIn(0, count - 1)
        state.scrollToItem(target, if (target == keyedIndex || keyedIndex == null && target == restore.index) restore.offset else 0)
        onRestored()
    }
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { CompactFilterField(query, { query = it }, searchPlaceholder) }
        if (items.isEmpty()) item { EmptyHint(emptyText) }
        if (items.isNotEmpty() && filtered.isEmpty()) item { EmptyHint("Keine passende Folge.") }
        items(filtered, key = { it.episode.id }) { EpisodeRow(it, vm, player, onEpisode, onShow) }
    }
}

@Composable
private fun EpisodeRow(
    item: EpisodeWithShow,
    vm: MainViewModel,
    player: PlayerUiState,
    onEpisode: (String) -> Unit,
    onShow: ((String) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val queuedEpisodeIds by vm.queuedEpisodeIds.collectAsStateWithLifecycle()
    val isQueued = item.episode.id in queuedEpisodeIds
    EpisodeCard(
        item = item,
        onOpen = { onEpisode(item.episode.id) },
        onLongClick = onLongClick,
        onShow = onShow?.let { openShow -> { openShow(item.show.id) } },
        onPlay = {
            if (item.episode.sourceType == EpisodeSourceType.MIXCLOUD) {
                vm.openEpisodeExternally(context, item.episode.id)
            } else {
                vm.playOrToggle(item.episode.id)
            }
        },
        onLike = { vm.toggleLike(item.episode.id) },
        onDownload = { vm.toggleDownload(item) },
        onQueue = {
            if (isQueued) vm.removeFromQueue(item.episode.id) else vm.addToQueue(item.episode.id)
        },
        isQueued = isQueued,
        isCurrent = player.mediaId == item.episode.id,
        isPlaying = player.isPlaying,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    vm: MainViewModel,
    localListState: LazyListState,
    discoveryListState: LazyListState,
    onShow: (String) -> Unit,
    onEpisode: (String) -> Unit,
    onDiscoveryPreview: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val localShows by vm.localShowResults.collectAsStateWithLifecycle(initialValue = emptyList())
    val localEpisodes by vm.localEpisodeResults.collectAsStateWithLifecycle(initialValue = emptyList())
    val discovery by vm.discovery.collectAsStateWithLifecycle()
    val tabSwipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(tab) {
        if (tab == 1 && discovery.results.isEmpty() && !discovery.loading) vm.browseDiscovery()
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Meine Inhalte") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Neue Shows") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (tab == 0) vm.localQuery.value = it
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            label = { Text(if (tab == 0) "Shows und Folgen durchsuchen" else "Neue DJ-Show suchen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (tab == 1) ({
                IconButton(onClick = { vm.searchDiscovery(query) }) { Icon(Icons.Default.ArrowForward, "Suchen") }
            }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (tab == 0) vm.localQuery.value = query else vm.searchDiscovery(query)
            }),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(tab) {
                var draggedX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { draggedX = 0f },
                    onHorizontalDrag = { _, amount -> draggedX += amount },
                    onDragCancel = { draggedX = 0f },
                    onDragEnd = {
                        when {
                            draggedX <= -tabSwipeThresholdPx && tab < 1 -> tab += 1
                            draggedX >= tabSwipeThresholdPx && tab > 0 -> tab -= 1
                        }
                        draggedX = 0f
                    },
                )
            },
        ) {
            if (tab == 0) {
                LocalSearchResults(query, localShows, localEpisodes, vm, localListState, onShow, onEpisode)
            } else {
                DiscoveryResults(discovery, vm, discoveryListState) { result ->
                    vm.openDiscoveryPreview(result)
                    onDiscoveryPreview()
                }
            }
        }
    }
}

@Composable
private fun LocalSearchResults(
    query: String,
    shows: List<ShowEntity>,
    episodes: List<EpisodeWithShow>,
    vm: MainViewModel,
    state: LazyListState,
    onShow: (String) -> Unit,
    onEpisode: (String) -> Unit,
) {
    val player by vm.player.collectAsStateWithLifecycle()
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (query.isBlank()) item { EmptyHint("Suche gleichzeitig in deinen Shows und bereits geladenen Folgen.") }
        if (shows.isNotEmpty()) item { SectionTitle("Shows") }
        items(shows, key = { "show-${it.id}" }) { ShowCard(it, { onShow(it.id) }) }
        if (episodes.isNotEmpty()) item { SectionTitle("Folgen") }
        items(episodes, key = { "episode-${it.episode.id}" }) { EpisodeRow(it, vm, player, onEpisode, onShow) }
        if (query.isNotBlank() && shows.isEmpty() && episodes.isEmpty()) item { EmptyHint("Keine Treffer in deinen Inhalten.") }
    }
}

@Composable
private fun DiscoveryResults(
    state: DiscoveryUiState,
    vm: MainViewModel,
    listState: LazyListState,
    onOpen: (DiscoveryResult) -> Unit,
) {
    val showResults = remember(state.results) { state.results.filter { discoverySubscriptionTargets(it).isNotEmpty() } }
    val isTextSearch = state.title.startsWith("Suche:")
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.title.startsWith("Beliebte"), onClick = { vm.browseDiscovery() }, label = { Text("Beliebt") })
                FilterChip(selected = state.title == "Neue Shows", onClick = { vm.browseDiscovery(BrowseMode.NEW) }, label = { Text("Neu") })
                FilterChip(selected = state.title == "Trending", onClick = { vm.browseDiscovery(BrowseMode.TRENDING) }, label = { Text("Trending") })
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(DiscoveryGenres.canonical) { genre ->
                    SuggestionChip(onClick = { vm.browseDiscovery(BrowseMode.GENRE, genre) }, label = { Text(genre) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (state.providerStatuses.isNotEmpty()) {
            item { ProviderStatusStrip(state.providerStatuses) }
        }

        if (state.loading) {
            if (showResults.isNotEmpty()) item { SectionTitle("Zwischenergebnisse") }
            items(showResults, key = { "discovery-loading-${it.internalId}" }) { result ->
                DiscoveryCard(result, vm, showMusicLabel = isTextSearch, onOpen = { onOpen(result) })
            }
        } else if (isTextSearch) {
            if (showResults.isNotEmpty()) item { SectionTitle("Beste Treffer") }
            items(showResults, key = { "discovery-${it.internalId}" }) { result ->
                DiscoveryCard(result, vm, showMusicLabel = true, onOpen = { onOpen(result) })
            }
        } else {
            val groups = listOf(ResultGroup.DECLARED_MUSIC, ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW, ResultGroup.OTHER)
            groups.forEach { group ->
                val results = showResults.filter { it.music.group == group }
                if (results.isNotEmpty()) {
                    item { SectionTitle(groupTitle(group)) }
                    items(results, key = { "discovery-${it.internalId}" }) { result ->
                        DiscoveryCard(result, vm, onOpen = { onOpen(result) })
                    }
                }
            }
        }
        if (!state.loading && showResults.isEmpty() && state.error == null) item { EmptyHint("Keine passenden Shows gefunden.") }
    }
}

@Composable
private fun DiscoveryCard(
    result: DiscoveryResult,
    vm: MainViewModel,
    showMusicLabel: Boolean = false,
    onOpen: () -> Unit,
) {
    val subscriptionTargets = discoverySubscriptionTargets(result)
    val target = subscriptionTargets.firstOrNull() ?: discoveryTarget(result)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(result.artworkUrl, result.title, Modifier.size(76.dp), diskCache = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                result.publisher?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (subscriptionTargets.size > 1) "${subscriptionTargets.size} Quellen auswählbar" else targetLabel(target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (result.feedVerification != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Verified, "Feed geprüft", Modifier.size(15.dp), tint = BrandGreen)
                    }
                }
                result.music.genres.take(3).takeIf { it.isNotEmpty() }?.let { genres ->
                    Text(genres.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
                if (showMusicLabel) {
                    Text(
                        when (result.music.group) {
                            ResultGroup.DECLARED_MUSIC -> "Als Musik geführt"
                            ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW -> "Wahrscheinlich DJ-/Musikshow"
                            ResultGroup.OTHER -> "Nicht eindeutig als Musik eingestuft"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.sources.isNotEmpty()) {
                    Text(
                        "Gefunden: " + result.sources.sortedBy { it.name }.joinToString(" · ") { providerName(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = {
                    if (subscriptionTargets.size > 1) onOpen()
                    else subscriptionTargets.firstOrNull()?.let { vm.subscribe(result, it) }
                },
                enabled = subscriptionTargets.isNotEmpty(),
            ) {
                Icon(Icons.Default.Add, if (subscriptionTargets.size > 1) "Quelle auswählen" else "Abonnieren")
            }
        }
    }
}

@Composable
private fun DiscoveryPreviewScreen(vm: MainViewModel) {
    val state by vm.discoveryPreview.collectAsStateWithLifecycle()
    val result = state.result
    val target = state.selectedTarget ?: result?.let(::discoveryTarget)
    val listingEpisodes = state.listing?.episodes.orEmpty()
    val parsedFeedEpisodes = state.feedPreview?.episodes.orEmpty()
    val feedEpisodes = state.feedVerification?.episodeTitles.orEmpty()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (result == null) {
            item {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(if (state.sharedImport) "Geteilten Link prüfen …" else "Showdetails laden …")
                    }
                } else {
                    EmptyHint(state.error ?: "Keine Showdetails verfügbar.")
                }
            }
            return@LazyColumn
        }

        item {
            Row(verticalAlignment = Alignment.Top) {
                Artwork(result.artworkUrl, result.title, Modifier.size(116.dp), diskCache = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    result.publisher?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(targetLabel(target), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (state.automaticallyAdded) {
                        Spacer(Modifier.height(7.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Zu meinen Shows hinzugefügt") },
                            leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(17.dp)) },
                        )
                    }
                }
            }
        }

        if (state.availableTargets.size > 1) {
            item { SectionTitle("Quelle auswählen") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(
                        state.availableTargets,
                        key = { source -> "${source.kind}:${source.url}:${source.feedUrl.orEmpty()}" },
                    ) { source ->
                        FilterChip(
                            selected = source == state.selectedTarget,
                            onClick = { vm.selectDiscoveryPreviewTarget(source) },
                            label = { Text(targetLabel(source)) },
                        )
                    }
                }
            }
            item {
                Text(
                    "Beschreibung und Folgenvorschau gehören immer zur ausgewählten Quelle. Hinzugefügt wird nur diese Variante.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { message ->
            item { Text("Vorschau unvollständig: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        val description = result.description.orEmpty().trim()
        item { SectionTitle("Über diese Show") }
        item {
            ExpandableDescription(
                text = description,
                emptyText = "Keine Beschreibung hinterlegt.",
            )
        }

        state.feedVerification?.let { verification ->
            item {
                val details = buildList {
                    if (verification.episodeCount > 0) add("${verification.episodeCount} Folgen im Feed")
                    verification.lastPublishedEpochMillis?.let { add("Zuletzt ${shortDate(it)}") }
                    activityLabel(verification.activityStatus)?.let(::add)
                }
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.feedVerification == null) {
            state.feedPreview?.takeIf { it.episodeCount > 0 }?.let { preview ->
                item {
                    Text(
                        "${preview.episodeCount} Folgen im Feed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (listingEpisodes.isNotEmpty() || parsedFeedEpisodes.isNotEmpty() || feedEpisodes.isNotEmpty()) {
            item { SectionTitle("Jüngste Folgen") }
            if (listingEpisodes.isNotEmpty()) {
                items(listingEpisodes.take(20), key = { "preview-${it.stableId}" }) { episode ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(episode.title, fontWeight = FontWeight.Medium)
                        val details = listOfNotNull(
                            episode.publishedAtEpochMs?.let(::shortDate),
                            episode.durationMs?.takeIf { it > 0L }?.let(::episodeDurationLabel),
                        )
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else if (parsedFeedEpisodes.isNotEmpty()) {
                items(parsedFeedEpisodes.take(20), key = { "rss-preview-${it.title}-${it.publishedAtEpochMs}" }) { episode ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(episode.title, fontWeight = FontWeight.Medium)
                        val details = listOfNotNull(
                            episode.publishedAtEpochMs?.let(::shortDate),
                            episode.durationMs?.takeIf { it > 0L }?.let(::episodeDurationLabel),
                        )
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else {
                items(feedEpisodes.take(20)) { title ->
                    Text(title, Modifier.fillMaxWidth().padding(vertical = 5.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else if (!state.loading) {
            item { EmptyHint("Für diesen Treffer konnte noch keine Folgenvorschau geladen werden.") }
        }

        item {
            Button(
                onClick = { target?.let { vm.subscribe(result, it) } },
                enabled = !state.automaticallyAdded && target?.let(::isSubscribableUiTarget) == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (state.automaticallyAdded) Icons.Default.Check else Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.automaticallyAdded) "Bereits hinzugefügt" else "Zu meinen Shows hinzufügen")
            }
        }

        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun LibraryScreen(
    vm: MainViewModel,
    likedListState: LazyListState,
    downloadsListState: LazyListState,
    historyListState: LazyListState,
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val liked by vm.liked.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloads by vm.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val tabSwipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Verlauf (${history.size})") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Gefällt mir (${liked.size})") })
            Tab(tab == 2, onClick = { tab = 2 }, text = { Text("Downloads (${downloads.size})") })
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(tab) {
                var draggedX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { draggedX = 0f },
                    onHorizontalDrag = { _, amount -> draggedX += amount },
                    onDragCancel = { draggedX = 0f },
                    onDragEnd = {
                        when {
                            draggedX <= -tabSwipeThresholdPx && tab < 2 -> tab += 1
                            draggedX >= tabSwipeThresholdPx && tab > 0 -> tab -= 1
                        }
                        draggedX = 0f
                    },
                )
            },
        ) {
            when (tab) {
                0 -> HistoryList(history, vm, historyListState, onEpisode, onShow)
                1 -> EpisodeLazyList(
                    liked,
                    vm,
                    "Noch keine Folgen mit Gefällt mir markiert.",
                    searchPlaceholder = "Gefällt mir filtern",
                    state = likedListState,
                    onEpisode = onEpisode,
                    onShow = onShow,
                )
                else -> EpisodeLazyList(
                    downloads,
                    vm,
                    "Noch keine Downloads.",
                    searchPlaceholder = "Downloads filtern",
                    state = downloadsListState,
                    onEpisode = onEpisode,
                    onShow = onShow,
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    player: PlayerUiState,
    vm: MainViewModel,
    onCollapse: () -> Unit,
    onQueue: () -> Unit,
    onMiniPlayer: (() -> Unit)?,
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
) {
    val currentEpisodeFlow = remember(player.mediaId) { vm.episode(player.mediaId.orEmpty()) }
    val currentEpisode by currentEpisodeFlow.collectAsStateWithLifecycle(initialValue = null)
    FullPlayer(
        title = player.title,
        artist = player.artist,
        artworkUrl = player.artworkUrl,
        isPlaying = player.isPlaying,
        positionMs = player.positionMs,
        durationMs = player.durationMs,
        speed = player.playbackSpeed,
        liked = currentEpisode?.episode?.liked == true,
        hasPrevious = player.hasPrevious,
        hasNext = player.hasNext,
        onToggle = vm::togglePlayer,
        onSeek = vm::seekPlayerTo,
        onSeekBy = vm::seekPlayerBy,
        onSpeed = vm::setPlayerSpeed,
        onLike = { player.mediaId?.let(vm::toggleLike) },
        onPrevious = vm::playerPrevious,
        onNext = vm::playerNext,
        onQueue = onQueue,
        onMiniPlayer = onMiniPlayer,
        onCollapse = onCollapse,
        onEpisodeInfo = player.mediaId?.let { mediaId -> { onEpisode(mediaId) } },
        onShowInfo = currentEpisode?.show?.id?.let { showId -> { onShow(showId) } },
    )
}

@Composable
private fun PipPlayerContent(
    player: PlayerUiState,
    currentEpisode: EpisodeWithShow?,
    syncing: Boolean,
) {
    val progress = if (player.durationMs > 0L) {
        (player.positionMs.toFloat() / player.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val context = LocalContext.current
    val artworkUrl = player.artworkUrl
        ?: currentEpisode?.episode?.artworkUrl
        ?: currentEpisode?.show?.artworkUrl
    var progressColor by remember(player.mediaId, artworkUrl) { mutableStateOf(Color.White) }

    Box(
        Modifier
            .fillMaxSize()
            .background(BrandNavy),
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .diskCacheKey(artworkUrl)
                    .memoryCacheKey(artworkUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    // Edge-colour detection needs readable pixels; hardware bitmaps reject
                    // Bitmap.getPixel(). This affects only the tiny PiP artwork request.
                    .allowHardware(false)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!player.isPlaying) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = "Wiedergabe pausiert",
                    tint = Color.White,
                    modifier = Modifier.size(58.dp),
                )
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(74.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(progressColor),
            )
        }
    }
}

private fun Bitmap.pipProgressColor(): Color {
    if (width <= 0 || height <= 0) return Color.White
    val hsv = FloatArray(3)
    var opaqueEdgePixels = 0
    var almostWhiteEdgePixels = 0
    var hueX = 0.0
    var hueY = 0.0
    var hueWeight = 0.0
    // The progress line sits on the literal lower edge of the PiP artwork. Inspecting a thicker
    // band is subtly wrong: a one-pixel white frame can then be outvoted by the dark photo just
    // above it. Sampling the final raster row mirrors what is actually behind the progress line.
    val edgeY = height - 1
    for (x in 0 until width) {
        val pixel = getPixel(x, edgeY)
        if (android.graphics.Color.alpha(pixel) < 128) continue
        opaqueEdgePixels++
        android.graphics.Color.colorToHSV(pixel, hsv)
        // Off-white and very light grey borders count too. Bright saturated colours do not: on
        // those covers the normal white progress line remains clearly visible.
        if (hsv[1] <= 0.22f && pixelRelativeLuminance(pixel) >= 0.72) {
            almostWhiteEdgePixels++
        }
    }

    // White is intentionally the normal progress colour. Artwork-derived substitution is a
    // narrowly-scoped exception: at least 60% of the actual bottom row must be white/almost
    // white. This also tolerates rounded corners and a little compression/noise in the cover.
    if (opaqueEdgePixels == 0 || almostWhiteEdgePixels * 5 < opaqueEdgePixels * 3) {
        return Color.White
    }

    // The real bottom edge above must stay full-resolution. The cover-wide hue calculation can
    // be sampled, however, which keeps this cheap even if Coil retained a large source bitmap.
    val sampleStep = (maxOf(width, height) / 192).coerceAtLeast(1)
    for (y in 0 until height step sampleStep) {
        for (x in 0 until width step sampleStep) {
            val pixel = getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) < 128) continue
            android.graphics.Color.colorToHSV(pixel, hsv)
            val weight = hsv[1].toDouble() * (0.35 + 0.65 * hsv[2].toDouble())
            if (weight > 0.08) {
                val radians = Math.toRadians(hsv[0].toDouble())
                hueX += kotlin.math.cos(radians) * weight
                hueY += kotlin.math.sin(radians) * weight
                hueWeight += weight
            }
        }
    }

    // Pixel count is naturally part of this circular mean: a colour occurring across a large
    // artwork area contributes more often. Saturation and brightness weight the contribution so
    // a useful accent wins over the white/grey canvas. This follows the same general idea as
    // Android's artwork palettes without adding another dependency just for the PiP edge.
    val hue = if (hueWeight > 0.0) {
        ((Math.toDegrees(kotlin.math.atan2(hueY, hueX)) + 360.0) % 360.0).toFloat()
    } else {
        null
    }
    val accent = hue?.let {
        android.graphics.Color.HSVToColor(floatArrayOf(it, 0.72f, 0.55f))
    } ?: android.graphics.Color.rgb(6, 54, 83)
    return Color(accent)
}

private fun pixelRelativeLuminance(pixel: Int): Double {
    fun channel(value: Int): Double {
        val srgb = value / 255.0
        return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
    }
    val red = channel(android.graphics.Color.red(pixel))
    val green = channel(android.graphics.Color.green(pixel))
    val blue = channel(android.graphics.Color.blue(pixel))
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

@Composable
private fun HistoryList(
    history: List<PlaybackHistoryWithEpisode>,
    vm: MainViewModel,
    state: LazyListState,
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
) {
    val player by vm.player.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var contextEpisodeId by remember { mutableStateOf<String?>(null) }
    val filtered = remember(history, query) {
        if (query.isBlank()) history else history.filter {
            it.episode.episode.title.contains(query, true) ||
                it.episode.show.title.contains(query, true)
        }
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { CompactFilterField(query, { query = it }, "Hörverlauf filtern") }
        if (history.isEmpty()) item { EmptyHint("Noch keine gehörten Folgen.") }
        var previousMonth: String? = null
        filtered.forEach { event ->
            val month = monthLabel(event.history.playedAtEpochMs)
            if (month != previousMonth) {
                item(key = "month-$month-${event.history.historyId}") { MonthDivider(month) }
                previousMonth = month
            }
            item(key = "history-${event.history.historyId}") {
                Box {
                    EpisodeRow(
                        event.episode,
                        vm,
                        player,
                        onEpisode,
                        onShow,
                        onLongClick = { contextEpisodeId = event.episode.episode.id },
                    )
                    DropdownMenu(
                        expanded = contextEpisodeId == event.episode.episode.id,
                        onDismissRequest = { contextEpisodeId = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Aus Verlauf löschen") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                contextEpisodeId = null
                                vm.deleteFromHistory(event.episode.episode.id)
                            },
                        )
                    }
                }
            }
        }
        if (history.isNotEmpty() && filtered.isEmpty()) item { EmptyHint("Keine passende Folge im Verlauf.") }
    }
}

@Composable
private fun QueueScreen(
    vm: MainViewModel,
    state: LazyListState,
    onEpisode: (String) -> Unit,
    onShow: (String) -> Unit,
) {
    val queue by vm.queue.collectAsStateWithLifecycle(initialValue = emptyList())
    var display by remember { mutableStateOf<List<QueueEntryWithEpisode>>(emptyList()) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(queue, draggingId) {
        if (draggingId == null) display = queue
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Als Nächstes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::clearQueue, enabled = display.isNotEmpty()) { Text("Leeren") }
        }
        if (display.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                EmptyHint("Die Warteschlange ist leer. Füge Folgen über das Listen-Symbol hinzu.")
            }
        } else {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(display, key = { it.queue.episodeId }) { entry ->
                    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
                    var dragDistance by remember(entry.queue.episodeId) { mutableFloatStateOf(0f) }
                    QueueListItem(
                        entry = entry,
                        onOpen = { onEpisode(entry.queue.episodeId) },
                        onShow = { onShow(entry.episode.show.id) },
                        onRestart = { vm.restartQueuedEpisode(entry.queue.episodeId) },
                        onDelete = { vm.removeFromQueue(entry.queue.episodeId) },
                        modifier = Modifier.pointerInput(entry.queue.episodeId) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = entry.queue.episodeId
                                    dragDistance = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragDistance = 0f
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragDistance = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDistance += amount.y
                                    if (kotlin.math.abs(dragDistance) >= threshold) {
                                        val from = display.indexOfFirst { it.queue.episodeId == entry.queue.episodeId }
                                        val direction = if (dragDistance > 0) 1 else -1
                                        val to = (from + direction).coerceIn(0, display.lastIndex)
                                        if (from >= 0 && to != from) {
                                            display = display.toMutableList().also { list ->
                                                val moved = list.removeAt(from)
                                                list.add(to, moved)
                                            }
                                            vm.reorderQueue(display.map { it.queue.episodeId })
                                        }
                                        dragDistance = 0f
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueListItem(
    entry: QueueEntryWithEpisode,
    onOpen: () -> Unit,
    onShow: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = entry.episode.episode
    val canRestart = episode.positionMs > 0L &&
        (episode.completedAtEpochMs == null || episode.isReplayAfterCompletion())
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeArtwork(
                entry.episode,
                Modifier.size(62.dp).alpha(if (episode.availability == EpisodeAvailability.SCHEDULED) 0.48f else 1f),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.episode.show.title,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onShow)
                        .padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.availability == EpisodeAvailability.SCHEDULED) {
                    Text(
                        "Geplant · wird erst an dieser Position einmal geprüft",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canRestart) {
                IconButton(onClick = onRestart) {
                    Icon(Icons.Default.Replay, "In der Warteschlange von vorn abspielen")
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Aus Warteschlange entfernen") }
            Icon(Icons.Default.DragHandle, "Gedrückt halten und verschieben", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpisodeDetailScreen(
    vm: MainViewModel,
    episodeId: String,
    state: LazyListState,
    onShow: (String) -> Unit,
) {
    val context = LocalContext.current
    val item by vm.episode(episodeId).collectAsStateWithLifecycle(initialValue = null)
    val player by vm.player.collectAsStateWithLifecycle()
    val queuedEpisodeIds by vm.queuedEpisodeIds.collectAsStateWithLifecycle()
    val playbackDates by remember(episodeId) { vm.playbackDates(episodeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val row = item
    if (row == null) {
        Box(Modifier.fillMaxSize().padding(12.dp)) { EmptyHint("Folge wird geladen …") }
        return
    }
    val episode = row.episode
    val isQueued = episode.id in queuedEpisodeIds
    val duration = episode.playbackDurationMs ?: episode.durationMs
    val progress = episode.displayPlaybackProgress()
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                EpisodeArtwork(
                    row,
                    Modifier.size(132.dp).alpha(if (episode.availability == EpisodeAvailability.SCHEDULED) 0.48f else 1f),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        row.show.title,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onShow(row.show.id) }
                            .padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        listOfNotNull(
                            episode.publishedAtEpochMs?.let(::shortDate),
                            duration?.takeIf { it > 0L }?.let(::episodeDurationLabel),
                            "Offline".takeIf { episode.downloadStatus == DownloadStatus.COMPLETE },
                            "Geplant".takeIf { episode.availability == EpisodeAvailability.SCHEDULED },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (progress != null && (episode.positionMs > 0 || episode.completedAtEpochMs != null)) {
            item {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = BrandPink,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledIconButton(
                    onClick = {
                        if (episode.sourceType == EpisodeSourceType.MIXCLOUD) {
                            vm.openEpisodeExternally(context, episode.id)
                        } else {
                            vm.playOrToggle(episode.id)
                        }
                    },
                    modifier = Modifier.size(54.dp),
                ) {
                    Icon(
                        if (episode.sourceType == EpisodeSourceType.MIXCLOUD) {
                            Icons.Default.OpenInNew
                        } else if (episode.availability == EpisodeAvailability.SCHEDULED) {
                            Icons.Outlined.Schedule
                        } else if (player.mediaId == episode.id && player.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        if (episode.sourceType == EpisodeSourceType.MIXCLOUD) {
                            "In Mixcloud öffnen"
                        } else if (episode.availability == EpisodeAvailability.SCHEDULED) {
                            "Verfügbarkeit prüfen"
                        } else if (player.mediaId == episode.id && player.isPlaying) {
                            "Pause"
                        } else {
                            "Abspielen"
                        },
                    )
                }
                IconButton(onClick = { vm.toggleLike(episode.id) }) {
                    Icon(
                        if (episode.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "Gefällt mir",
                        tint = if (episode.liked) BrandPink else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (episode.sourceType != EpisodeSourceType.MIXCLOUD) {
                    if (episode.availability != EpisodeAvailability.SCHEDULED) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            DownloadAction(episode, onClick = { vm.toggleDownload(row) })
                        }
                    }
                    IconButton(onClick = {
                        if (isQueued) vm.removeFromQueue(episode.id) else vm.addToQueue(episode.id)
                    }) {
                        Icon(
                            if (isQueued) Icons.Filled.PlaylistAddCheck else Icons.Filled.PlaylistAdd,
                            if (isQueued) "Aus Warteschlange entfernen" else "Zur Warteschlange",
                            tint = if (isQueued) BrandGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (episode.availability == EpisodeAvailability.SCHEDULED) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            episode.scheduledForEpochMs?.let {
                                "Diese Folge ist für ${heardDateTime(it)} geplant und noch nicht verfügbar."
                            } ?: "Diese Folge ist geplant und noch nicht verfügbar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { SectionTitle("Beschreibung") }
        item {
            val compact = remember(episode.description) { compactDescription(episode.description) }
            SelectionContainer {
                Text(
                    text = compact.ifBlank { "Für diese Folge ist keine Beschreibung hinterlegt." },
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (compact.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (playbackDates.size > 1) {
            item { SectionTitle("Mehrfach wiedergegeben") }
            items(playbackDates, key = { "played-$it" }) { playedAt ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.History,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(heardDateTime(playedAt), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    vm: MainViewModel,
    state: LazyListState,
    onShowOrder: () -> Unit,
    onBluetooth: () -> Unit,
    onMiniPlayer: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val viewExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportShowView) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importTransfer) }
    var includeDownloadsInBackup by rememberSaveable { mutableStateOf(false) }
    var showFullBackupDialog by remember { mutableStateOf(false) }
    val fullBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { vm.exportFullBackup(it, includeDownloadsInBackup) } }
    var confirmation by remember { mutableStateOf<String?>(null) }

    if (showFullBackupDialog) {
        AlertDialog(
            onDismissRequest = { showFullBackupDialog = false },
            icon = { Icon(Icons.Outlined.Inventory2, null) },
            title = { Text("Vollständige Sicherung") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Enthält Shows, Reihenfolge, Einstellungen, Abspielstände, Verlauf, Likes und Warteschlange.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeDownloadsInBackup,
                            onCheckedChange = { includeDownloadsInBackup = it },
                        )
                        Text("Heruntergeladene Audiodateien einbinden")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFullBackupDialog = false
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
                    fullBackupLauncher.launch("WeeklyDJShows-Sicherung-$timestamp.zip")
                }) { Text("Datei wählen") }
            },
            dismissButton = { TextButton(onClick = { showFullBackupDialog = false }) { Text("Abbrechen") } },
        )
    }

    if (confirmation != null) {
        val action = confirmation!!
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == "history") "Hörverlauf löschen?" else "Abspielstände löschen?") },
            text = {
                Text(
                    if (action == "history") {
                        "Die Liste der Hörereignisse wird gelöscht. Downloads, Likes und Abspielstände bleiben erhalten."
                    } else {
                        "Fortschritt und Gehört-Markierungen aller Folgen werden gelöscht. Downloads, Likes und Hörverlauf bleiben erhalten."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (action == "history") vm.clearHistory() else vm.clearPlaybackState()
                    confirmation = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Abbrechen") } },
        )
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item { SectionTitle("Streamingqualität") }
        item {
            QualitySetting(
                "WLAN",
                "Zielbitrate; gewählt wird die nächstpassende verfügbare Audiospur.",
                settings.wifiQuality,
                vm::setWifiQuality,
            )
        }
        item {
            QualitySetting(
                "Mobile Daten",
                "Zielbitrate; niedrigere Werte sparen Datenvolumen.",
                settings.mobileQuality,
                vm::setMobileQuality,
            )
        }
        item {
            QualitySetting(
                "Downloads",
                "Eigene Qualität für Plattformquellen. RSS-Feeds mit nur einer Audiodatei werden unverändert gespeichert.",
                settings.downloadQuality,
                vm::setDownloadQuality,
            )
        }

        item { SectionTitle("Startseite") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Beim App-Start", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.startupScreen == StartupScreen.LATEST,
                        onClick = { vm.setStartupScreen(StartupScreen.LATEST) },
                        leadingIcon = { Icon(Icons.Outlined.NewReleases, null, Modifier.size(18.dp)) },
                        label = { Text("Neu") },
                    )
                    FilterChip(
                        selected = settings.startupScreen == StartupScreen.SHOWS,
                        onClick = { vm.setStartupScreen(StartupScreen.SHOWS) },
                        leadingIcon = { Icon(Icons.Outlined.Headphones, null, Modifier.size(18.dp)) },
                        label = { Text("Shows") },
                    )
                }
                Text(
                    "Gilt ab dem nächsten vollständigen App-Start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsAction(
                icon = Icons.Default.Reorder,
                title = "Reihenfolge der Shows",
                subtitle = "Eigene Reihenfolge per Drag & Drop, A–Z und ausgeblendete Shows.",
                onClick = onShowOrder,
            )
        }

        item { SectionTitle("Podcast-Kategorien") }
        item {
            SettingsSwitch(
                title = "Wort-Podcasts anzeigen",
                subtitle = "Ausgeschaltete Kategorien werden weder angezeigt noch automatisch aktualisiert.",
                checked = settings.wordPodcastsEnabled,
                onChecked = vm::setWordPodcastsEnabled,
            )
        }
        item {
            SettingsSwitch(
                title = "Wort-Podcasts unter ‚Neu‘",
                checked = settings.wordPodcastsInLatest,
                enabled = settings.wordPodcastsEnabled,
                onChecked = vm::setWordPodcastsInLatest,
            )
        }
        item {
            SettingsSwitch(
                title = "Musik-Podcasts anzeigen",
                subtitle = "Ausgeschaltete Kategorien werden weder angezeigt noch automatisch aktualisiert.",
                checked = settings.musicPodcastsEnabled,
                onChecked = vm::setMusicPodcastsEnabled,
            )
        }
        item {
            SettingsSwitch(
                title = "Musik-Podcasts unter ‚Neu‘",
                checked = settings.musicPodcastsInLatest,
                enabled = settings.musicPodcastsEnabled,
                onChecked = vm::setMusicPodcastsInLatest,
            )
        }
        item {
            SettingsSwitch(
                title = "Geplante YouTube-Folgen unter ‚Neu‘ ausblenden",
                subtitle = "Sie bleiben in den Show-Ansichten sichtbar und können weiterhin vorgemerkt werden.",
                checked = settings.hideScheduledFromLatest,
                onChecked = vm::setHideScheduledFromLatest,
            )
        }

        item { SectionTitle("Player") }
        item {
            SettingsAction(
                icon = Icons.Default.PictureInPictureAlt,
                title = "Mini-Player",
                subtitle = "Android Mini-Player oder eigenes Overlay als Cover bzw. Querformatkarte.",
                onClick = onMiniPlayer,
            )
        }

        item { SectionTitle("Automatik") }
        item {
            SettingsSwitch(
                title = "Beim vollständigen App-Start aktualisieren",
                subtitle = "Beim bloßen Zurückkehren aus dem Hintergrund wird keine große Aktualisierung gestartet.",
                checked = settings.refreshOnColdStart,
                onChecked = vm::setRefreshOnColdStart,
            )
        }
        item {
            SettingsSwitch(
                title = "Nach 10 Minuten Inaktivität beenden",
                subtitle = "Nur im Hintergrund und nur ohne laufende Wiedergabe.",
                checked = settings.exitAfterIdle,
                onChecked = vm::setExitAfterIdle,
            )
        }
        item {
            SettingsSwitch(
                title = "Unterbrochene Folge beim Start anbieten",
                subtitle = "Zeigt eine Folgenkarte zum Fortsetzen; die Wiedergabe startet nicht ungefragt.",
                checked = settings.resumeOfferEnabled,
                onChecked = vm::setResumeOfferEnabled,
            )
        }
        item {
            SettingsAction(
                icon = Icons.Default.Bluetooth,
                title = "Bluetooth-Automatik",
                subtitle = "App öffnen oder pausierte Wiedergabe fortsetzen – je Gerät getrennt.",
                onClick = onBluetooth,
            )
        }

        item { SectionTitle("Sichern & übertragen") }
        item {
            SettingsAction(
                icon = Icons.Default.Share,
                title = "Show-Ansicht exportieren",
                subtitle = "Speichert Shows, Quellenvarianten, eigene Namen, ‚Neu‘-/Bereinigungs-Einstellungen und die exakte Reihenfolge als JSON.",
                onClick = {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
                    viewExportLauncher.launch("WeeklyDJShows-Ansicht-$timestamp.json")
                },
            )
        }
        item {
            SettingsAction(
                icon = Icons.Outlined.Inventory2,
                title = "Alles sichern",
                subtitle = "Sichert Verlauf, Likes, Abspielstände, Warteschlange und sämtliche Einstellungen; Downloads optional.",
                onClick = { showFullBackupDialog = true },
            )
        }
        item {
            SettingsAction(
                icon = Icons.Default.Restore,
                title = "Ansicht oder Sicherung importieren",
                subtitle = "Erkennt JSON und vollständige ZIP-Sicherungen automatisch und ergänzt vorhandene Daten.",
                onClick = { importLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain")) },
            )
        }

        item { SectionTitle("Updates & Hilfe") }
        item {
            SettingsSwitch(
                title = "App-Updates prüfen",
                subtitle = "Prüft nur eine kleine Versionsdatei; Installationen benötigen immer deine Android-Bestätigung.",
                checked = settings.appUpdateChecksEnabled,
                onChecked = vm::setAppUpdateChecksEnabled,
            )
        }
        item {
            SettingsAction(
                icon = Icons.Outlined.Refresh,
                title = "Jetzt nach App-Update suchen",
                subtitle = "Zeigt eine verfügbare Version an und kann die APK im Hintergrund laden.",
                onClick = vm::checkAppUpdate,
            )
        }
        item {
            SettingsSwitch(
                title = "NewPipe-Kompatibilität prüfen",
                subtitle = "Warnt nutzerverständlich, wenn die interne Wiedergabe häufiger ausfallen könnte.",
                checked = settings.newPipeChecksEnabled,
                onChecked = vm::setNewPipeChecksEnabled,
            )
        }
        item {
            SettingsAction(
                icon = Icons.Outlined.OpenInNew,
                title = "NewPipe prüfen oder installieren",
                subtitle = "Prüft die Wiedergabekomponente; Installation und Updates erfolgen über die offizielle Quelle.",
                onClick = vm::checkNewPipeCompatibility,
            )
        }
        item {
            SettingsAction(
                icon = Icons.Outlined.Download,
                title = "NewPipe-Installationsseite öffnen",
                subtitle = "Nutzt bewusst die offizielle Anleitung, damit spätere Updates dieselbe Signaturquelle verwenden.",
                onClick = { vm.openNewPipeInstallPage(context) },
            )
        }
        item {
            SettingsAction(
                icon = Icons.Outlined.BugReport,
                title = "Fehler melden oder Anregung senden",
                subtitle = "Erstellt einen teilbaren Bericht mit App-Version, Gerät und kompaktem Diagnoseprotokoll.",
                onClick = { vm.shareFeedback(context) },
            )
        }

        item { SectionTitle("Speicher & Verlauf") }
        item {
            SettingsAction(
                icon = Icons.Default.CleaningServices,
                title = "Zwischengespeicherte Daten löschen",
                subtitle = "Downloads, Likes, Abspielstände und Hörverlauf bleiben erhalten.",
                onClick = vm::clearTemporaryCache,
            )
        }
        item {
            SettingsAction(
                icon = Icons.Default.Restore,
                title = "Abspielstände & Gehört-Status löschen",
                subtitle = "Setzt Fortschrittsstreifen und Gehört-Markierungen zurück.",
                onClick = { confirmation = "playback" },
            )
        }
        item {
            SettingsAction(
                icon = Icons.Default.History,
                title = "Hörverlauf löschen",
                subtitle = "Entfernt nur die chronologische Liste der Hörereignisse.",
                onClick = { confirmation = "history" },
            )
        }
    }
}

@Composable
private fun MiniPlayerSettingsScreen(vm: MainViewModel, state: LazyListState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    var overlayPermissionRevision by remember { mutableIntStateOf(0) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayPermissionRevision += 1 }
    val overlayAllowed = remember(overlayPermissionRevision) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Du kannst Androids Mini-Player oder ein besonders kleines App-Overlay verwenden. " +
                    "Beide zeigen Cover, Pausenstatus und Fortschritt.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SectionTitle("Darstellung") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(
                    selected = settings.miniPlayerImplementation == MiniPlayerImplementation.SYSTEM_PIP,
                    onClick = { vm.setMiniPlayerImplementation(MiniPlayerImplementation.SYSTEM_PIP) },
                    leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, null, Modifier.size(18.dp)) },
                    label = { Text("Android Mini-Player") },
                )
                FilterChip(
                    selected = settings.miniPlayerImplementation == MiniPlayerImplementation.CUSTOM_OVERLAY,
                    onClick = { vm.setMiniPlayerImplementation(MiniPlayerImplementation.CUSTOM_OVERLAY) },
                    leadingIcon = { Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)) },
                    label = { Text("Kleines App-Overlay") },
                )
            }
        }
        if (settings.miniPlayerImplementation == MiniPlayerImplementation.SYSTEM_PIP && !supported) {
            item {
                Text(
                    "Picture-in-Picture wird von diesem Gerät nicht unterstützt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (settings.miniPlayerImplementation == MiniPlayerImplementation.CUSTOM_OVERLAY) {
            if (!overlayAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                item {
                    SettingsAction(
                        icon = Icons.Outlined.Layers,
                        title = "Einblendung über anderen Apps erlauben",
                        subtitle = "Android benötigt diese Freigabe nur für das optionale eigene Overlay.",
                        onClick = {
                            overlayPermissionLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                            )
                        },
                    )
                }
            }
            item {
                Text("Overlay-Ansicht", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = settings.overlayLayout == OverlayLayout.SQUARE_COVER,
                        onClick = { vm.setOverlayLayout(OverlayLayout.SQUARE_COVER) },
                        leadingIcon = { Icon(Icons.Outlined.CropSquare, null, Modifier.size(18.dp)) },
                        label = { Text("Cover") },
                    )
                    FilterChip(
                        selected = settings.overlayLayout == OverlayLayout.WIDE_CARD,
                        onClick = { vm.setOverlayLayout(OverlayLayout.WIDE_CARD) },
                        leadingIcon = { Icon(Icons.Outlined.ViewAgenda, null, Modifier.size(18.dp)) },
                        label = { Text("Karte") },
                    )
                }
                Text(
                    if (settings.overlayLayout == OverlayLayout.WIDE_CARD) {
                        "Querformat mit Cover, Folge, Show sowie Folgen- und Spultasten."
                    } else {
                        "Kompakte quadratische Ansicht mit dem Cover im Mittelpunkt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("Overlay-Größe", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlaySize.entries.forEach { size ->
                        FilterChip(
                            selected = settings.overlaySize == size,
                            onClick = { vm.setOverlaySize(size) },
                            label = { Text(when (size) {
                                OverlaySize.TINY -> "Winzig"
                                OverlaySize.SMALL -> "Klein"
                                OverlaySize.MEDIUM -> "Mittel"
                            }) },
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    "Android bestimmt die absolute Fenstergröße; auf unterstützten Geräten kannst du sie mit zwei Fingern ändern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSwitch(
                title = "Bei laufender Wiedergabe im Hintergrund öffnen",
                checked = settings.autoMiniPlayerOnBackground,
                onChecked = vm::setAutoMiniPlayerOnBackground,
            )
        }

        if (settings.miniPlayerImplementation == MiniPlayerImplementation.SYSTEM_PIP) {
            item { SectionTitle("Bedientasten") }
            item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(
                    selected = settings.miniPlayerControls == MiniPlayerControls.SEEK,
                    onClick = { vm.setMiniPlayerControls(MiniPlayerControls.SEEK) },
                    leadingIcon = { Icon(Icons.Default.Replay10, null, Modifier.size(18.dp)) },
                    label = { Text("10 s zurück / 30 s vor") },
                )
                FilterChip(
                    selected = settings.miniPlayerControls == MiniPlayerControls.EPISODES,
                    onClick = { vm.setMiniPlayerControls(MiniPlayerControls.EPISODES) },
                    leadingIcon = { Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)) },
                    label = { Text("Vorherige / nächste Folge") },
                )
            }
            }
            item {
            Text(
                "Nach einem Tipp zeigt Android die verfügbaren Player-Aktionen. Wenn ein Gerät mindestens fünf " +
                    "native PiP-Aktionen erlaubt, kombiniert eine ausreichend große Mini-App automatisch Folgen- und Spultasten. " +
                    "Auf Geräten mit nur drei Aktionsplätzen bleibt die oben gewählte Belegung aktiv.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}

@Composable
private fun ShowOrderScreen(vm: MainViewModel, state: LazyListState) {
    val shows by vm.shows.collectAsStateWithLifecycle(initialValue = emptyList())
    val hidden by vm.hiddenLegacyShows.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()
    var display by remember { mutableStateOf<List<ShowEntity>>(emptyList()) }
    var draggingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shows, settings.showOrderMode) {
        if (draggingId == null) {
            display = when (settings.showOrderMode) {
                ShowOrderMode.CUSTOM -> shows
                ShowOrderMode.ALPHABETICAL -> shows.sortedBy { it.title.lowercase(Locale.getDefault()) }
            }
        }
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.showOrderMode == ShowOrderMode.CUSTOM,
                        onClick = { vm.setShowOrderMode(ShowOrderMode.CUSTOM) },
                        label = { Text("Eigene Reihenfolge") },
                    )
                    FilterChip(
                        selected = settings.showOrderMode == ShowOrderMode.ALPHABETICAL,
                        onClick = { vm.setShowOrderMode(ShowOrderMode.ALPHABETICAL) },
                        label = { Text("A–Z") },
                    )
                }
                Text(
                    if (settings.showOrderMode == ShowOrderMode.CUSTOM) {
                        "Eine Show gedrückt halten und nach oben oder unten ziehen."
                    } else {
                        "A–Z ändert nur die Ansicht; deine eigene Reihenfolge bleibt gespeichert."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(display, key = { "order-${it.id}" }) { show ->
            val threshold = with(LocalDensity.current) { 56.dp.toPx() }
            var dragDistance by remember(show.id) { mutableFloatStateOf(0f) }
            val dragModifier = if (settings.showOrderMode == ShowOrderMode.CUSTOM) {
                Modifier.pointerInput(show.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingId = show.id
                            dragDistance = 0f
                        },
                        onDragCancel = {
                            display = shows
                            draggingId = null
                            dragDistance = 0f
                        },
                        onDragEnd = {
                            vm.reorderShows(display.map { it.id })
                            draggingId = null
                            dragDistance = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragDistance += amount.y
                            if (kotlin.math.abs(dragDistance) >= threshold) {
                                val from = display.indexOfFirst { it.id == show.id }
                                val direction = if (dragDistance > 0) 1 else -1
                                val to = (from + direction).coerceIn(0, display.lastIndex)
                                if (from >= 0 && to != from) {
                                    display = display.toMutableList().also { list ->
                                        val moved = list.removeAt(from)
                                        list.add(to, moved)
                                    }
                                }
                                dragDistance = 0f
                            }
                        },
                    )
                }
            } else {
                Modifier
            }
            Surface(
                modifier = Modifier.fillMaxWidth().then(dragModifier),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(show.artworkUrl, show.title, Modifier.size(44.dp), diskCache = true)
                    Spacer(Modifier.width(9.dp))
                    Text(show.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (settings.showOrderMode == ShowOrderMode.CUSTOM) {
                        Icon(
                            Icons.Default.DragHandle,
                            "Gedrückt halten und verschieben",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (hidden.isNotEmpty()) {
            item { SectionTitle("Ausgeblendete Shows") }
            items(hidden, key = { "hidden-${it.id}" }) { show ->
                ListItem(
                    headlineContent = { Text(show.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Artwork(show.artworkUrl, show.title, Modifier.size(42.dp), diskCache = true) },
                    trailingContent = {
                        TextButton(onClick = { vm.restoreShow(show.id) }) { Text("Einblenden") }
                    },
                )
            }
        }
    }
}

private data class PairedBluetoothDeviceUi(
    val address: String,
    val name: String,
    val lastConnectedAtEpochMs: Long = 0L,
)

private data class PairedBluetoothSnapshot(
    val devices: List<PairedBluetoothDeviceUi>,
    val bluetoothEnabled: Boolean,
)

@Composable
private fun BluetoothAutomationScreen(vm: MainViewModel, state: LazyListState) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shows by vm.shows.collectAsStateWithLifecycle(initialValue = emptyList())
    val eligibleShows = remember(shows) { shows.filter { it.isAutostartEpisodeSource() } }
    val context = LocalContext.current
    var showSelectionExpanded by remember { mutableStateOf(false) }
    var bluetoothPermissionRevision by remember { mutableIntStateOf(0) }
    var notificationPermissionRevision by remember { mutableIntStateOf(0) }
    var backgroundOpenPermissionRevision by remember { mutableIntStateOf(0) }
    var batteryPermissionRevision by remember { mutableIntStateOf(0) }
    var deviceRevision by remember { mutableIntStateOf(0) }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { bluetoothPermissionRevision += 1 }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationPermissionRevision += 1 }
    val backgroundOpenPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { backgroundOpenPermissionRevision += 1 }
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryPermissionRevision += 1 }
    val bluetoothPermissionGranted = remember(bluetoothPermissionRevision) {
        hasBluetoothConnectPermission(context)
    }
    val notificationPermissionGranted = remember(notificationPermissionRevision) {
        hasNotificationPermission(context)
    }
    val backgroundOpenPermissionGranted = remember(backgroundOpenPermissionRevision) {
        hasBluetoothBackgroundOpenPermission(context)
    }
    val batteryOptimizationDisabled = remember(batteryPermissionRevision) {
        isBatteryOptimizationDisabled(context)
    }
    val autostartDiagnostic = remember(deviceRevision, backgroundOpenPermissionRevision) {
        BluetoothAutostartDiagnostics.read(context)
    }
    val selectedAddresses = settings.bluetoothAutoOpenDevices + settings.bluetoothAutoResumeDevices
    val snapshot = remember(bluetoothPermissionGranted, deviceRevision, selectedAddresses) {
        if (bluetoothPermissionGranted) {
            pairedBluetoothDevices(context, selectedAddresses)
        } else {
            PairedBluetoothSnapshot(emptyList(), bluetoothEnabled = false)
        }
    }
    val devices = snapshot.devices

    DisposableEffect(context, bluetoothPermissionGranted) {
        if (!bluetoothPermissionGranted) {
            onDispose { }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ContextCompat.checkSelfPermission(
                                receiverContext,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ) == PackageManager.PERMISSION_GRANTED)
                    ) {
                        bluetoothDeviceFromIntent(intent)?.let { device ->
                            val address = runCatching { device.address }.getOrNull().orEmpty()
                            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unbenanntes Gerät" }
                            BluetoothDeviceHistory.recordConnection(receiverContext, address, name)
                        }
                    }
                    deviceRevision += 1
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Für jedes bereits gekoppelte Gerät kannst du beide Aktionen unabhängig voneinander einschalten.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SectionTitle("Startdarstellung") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("App automatisch öffnen als", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(
                        selected = settings.bluetoothLaunchMode == BluetoothLaunchMode.FULL_APP,
                        onClick = { vm.setBluetoothLaunchMode(BluetoothLaunchMode.FULL_APP) },
                        label = { Text("Normale App") },
                    )
                    FilterChip(
                        selected = settings.bluetoothLaunchMode == BluetoothLaunchMode.MINI_PLAYER,
                        onClick = { vm.setBluetoothLaunchMode(BluetoothLaunchMode.MINI_PLAYER) },
                        label = { Text("Mini-App") },
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Angebot beim Start", fontWeight = FontWeight.SemiBold)
                AutostartOfferMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.autostartOfferMode == mode,
                        onClick = { vm.setAutostartOfferMode(mode) },
                        label = { Text(when (mode) {
                            AutostartOfferMode.INTERRUPTED_ONLY -> "Nur unterbrochene Folge"
                            AutostartOfferMode.INTERRUPTED_THEN_SELECTED -> "Unterbrochen, sonst neueste gewählte"
                            AutostartOfferMode.SELECTED_LATEST -> "Immer neueste gewählte Folge"
                        }) },
                    )
                }
            }
        }
        if (settings.bluetoothLaunchMode == BluetoothLaunchMode.FULL_APP) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Beim Öffnen anzeigen", fontWeight = FontWeight.SemiBold)
                    BluetoothDisplayMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.bluetoothDisplayMode == mode,
                            onClick = { vm.setBluetoothDisplayMode(mode) },
                            label = { Text(when (mode) {
                                BluetoothDisplayMode.START_SCREEN -> "Gewählte Startseite"
                                BluetoothDisplayMode.OFFER -> "Folgenangebot als Karte"
                                BluetoothDisplayMode.PLAYER_IF_AVAILABLE -> "Player, sobald eine Folge bereitsteht"
                            }) },
                        )
                    }
                    Text(
                        "Der Player wird nie leer geöffnet. Das Folgenangebot hängt zusätzlich von der allgemeinen Startkarten-Einstellung ab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Show für neueste Folge", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { showSelectionExpanded = true }) {
                        Text(
                            eligibleShows.firstOrNull { it.id == settings.autostartShowId }?.title
                                ?: eligibleShows.firstOrNull()?.title
                                ?: "Noch keine Show verfügbar",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = showSelectionExpanded,
                        onDismissRequest = { showSelectionExpanded = false },
                    ) {
                        eligibleShows.forEach { show ->
                            DropdownMenuItem(
                                text = { Text(show.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    vm.setAutostartShowId(show.id)
                                    showSelectionExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Ohne Auswahl gilt die erste eingeblendete Show der Übersicht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionTitle("Automatische Wiedergabe") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BluetoothAutoplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.bluetoothAutoplayMode == mode,
                        onClick = { vm.setBluetoothAutoplayMode(mode) },
                        label = { Text(when (mode) {
                            BluetoothAutoplayMode.OFF -> "Nicht automatisch abspielen"
                            BluetoothAutoplayMode.ACTIVE_ONLY -> "Nur aktive pausierte Sitzung"
                            BluetoothAutoplayMode.RESTORE_INTERRUPTED -> "Auch nach beendetem App-Prozess fortsetzen"
                            BluetoothAutoplayMode.QUEUE_THEN_SELECTED -> "Warteschlange, sonst gewählte Show"
                            BluetoothAutoplayMode.SELECTED_LATEST -> "Immer neueste Folge der gewählten Show"
                        }) },
                    )
                }
                Text(
                    "Diese Auswahl gilt für Geräte, bei denen unten die automatische Wiedergabe aktiviert ist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item {
                Text(
                    "Für einen automatischen Vordergrund-Start aus anderen Apps oder dem Standby verlangt Android eine besondere Freigabe. Ohne sie bleibt die antippbare Benachrichtigung als Rückfall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            settings.bluetoothAutoOpenDevices.isNotEmpty() && !backgroundOpenPermissionGranted
        ) {
            item {
                SettingsAction(
                    icon = Icons.Default.OpenInNew,
                    title = "Automatisches Öffnen erlauben",
                    subtitle = "Erlaube „Über anderen Apps einblenden“. Weekly DJ Shows nutzt die Freigabe nur, um die App bei den von dir ausgewählten Bluetooth-Geräten in den Vordergrund zu holen – auch aus dem Standby.",
                    onClick = {
                        backgroundOpenPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
        }

        if (settings.bluetoothAutoOpenDevices.isNotEmpty() && !batteryOptimizationDisabled) {
            item {
                SettingsAction(
                    icon = Icons.Default.BatterySaver,
                    title = "Hintergrundbetrieb prüfen",
                    subtitle = "Für zuverlässigen Autostart im Standby empfehlen auch Blitzer.de und Tasker, die Akku-Optimierung aufzuheben. Auf Samsung: App-Info → Akku → Uneingeschränkt.",
                    onClick = {
                        batterySettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
        }

        if (settings.bluetoothAutoOpenDevices.isNotEmpty() && autostartDiagnostic != null) {
            item {
                val diagnostic = autostartDiagnostic
                val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault())
                    .format(Date(diagnostic.attemptedAtEpochMs))
                val result = when {
                    diagnostic.reachedForeground -> "Vordergrund erreicht"
                    diagnostic.dispatched -> "Android-Aufruf gesendet, Vordergrund noch nicht erreicht"
                    else -> "Android-Aufruf konnte nicht gesendet werden"
                }
                Text(
                    "Letzter Autostart-Versuch $time · $result",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (diagnostic.reachedForeground) BrandGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!bluetoothPermissionGranted) {
            item {
                SettingsAction(
                    icon = Icons.Default.Bluetooth,
                    title = "Bluetooth-Zugriff erlauben",
                    subtitle = "Wird nur benötigt, um gekoppelte Geräte zu erkennen und deine Auswahl zuzuordnen.",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Gekoppelte Geräte")
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { deviceRevision += 1 }) {
                        Icon(Icons.Default.Refresh, "Geräteliste aktualisieren")
                    }
                }
            }
            if (!snapshot.bluetoothEnabled) {
                item {
                    Text(
                        "Bluetooth ist ausgeschaltet. Bereits eingelesene gekoppelte Geräte bleiben sichtbar; beim Einschalten aktualisiert sich die Liste automatisch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (devices.isEmpty()) {
                item {
                    EmptyHint(
                        if (snapshot.bluetoothEnabled) {
                            "Keine gekoppelten Bluetooth-Geräte gefunden."
                        } else {
                            "Noch keine Geräteliste gespeichert. Bluetooth einmal einschalten; danach bleiben die gekoppelten Geräte auch im ausgeschalteten Zustand sichtbar."
                        },
                    )
                }
            }
            items(devices, key = { "bluetooth-${it.address}" }) { device ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = device.address in settings.bluetoothAutoOpenDevices,
                                onCheckedChange = { vm.setBluetoothAutoOpenDevice(device.address, it) },
                            )
                            Text("App bei Verbindung öffnen", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = device.address in settings.bluetoothAutoResumeDevices,
                                onCheckedChange = { vm.setBluetoothAutoResumeDevice(device.address, it) },
                            )
                            Text("Automatische Wiedergabe", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.bluetoothAutoOpenDevices.isNotEmpty() && !notificationPermissionGranted
        ) {
            item {
                SettingsAction(
                    icon = Icons.Default.Notifications,
                    title = "Fallback-Benachrichtigung erlauben",
                    subtitle = "Falls Android den automatischen Vordergrund-Start blockiert, kannst du die App damit mit einem Tipp öffnen.",
                    onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }
    }
}

private fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasBluetoothBackgroundOpenPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

private fun isBatteryOptimizationDisabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true
    } else {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

@SuppressLint("MissingPermission")
private fun pairedBluetoothDevices(
    context: Context,
    selectedAddresses: Set<String>,
): PairedBluetoothSnapshot {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: return PairedBluetoothSnapshot(emptyList(), bluetoothEnabled = false)
    val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
    val remembered = BluetoothDeviceHistory.remembered(context)
    if (!enabled) {
        val byAddress = remembered.associateBy { it.address }.toMutableMap()
        selectedAddresses.forEach { address ->
            byAddress.putIfAbsent(address, RememberedBluetoothDevice(address, address, 0L))
        }
        return PairedBluetoothSnapshot(
            devices = sortBluetoothDevices(byAddress.values.map {
                PairedBluetoothDeviceUi(it.address, it.name, it.lastConnectedAtEpochMs)
            }),
            bluetoothEnabled = false,
        )
    }

    val bonded = adapter.bondedDevices
        .mapNotNull { device ->
            val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unbenanntes Gerät" }
            BluetoothDeviceHistory.recordKnown(context, address, name)
            PairedBluetoothDeviceUi(address, name)
        }
    val recency = BluetoothDeviceHistory.remembered(context).associateBy { it.address }
    return PairedBluetoothSnapshot(
        devices = sortBluetoothDevices(bonded.map { device ->
            device.copy(lastConnectedAtEpochMs = recency[device.address]?.lastConnectedAtEpochMs ?: 0L)
        }),
        bluetoothEnabled = true,
    )
}

private fun sortBluetoothDevices(devices: Collection<PairedBluetoothDeviceUi>): List<PairedBluetoothDeviceUi> =
    devices.sortedWith(Comparator { left, right ->
        val byRecent = right.lastConnectedAtEpochMs.compareTo(left.lastConnectedAtEpochMs)
        if (byRecent != 0) {
            byRecent
        } else {
            val byName = left.name.compareTo(right.name, ignoreCase = true)
            if (byName != 0) byName else left.address.compareTo(right.address)
        }
    })

@Suppress("DEPRECATION")
private fun bluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

@Composable
private fun QualitySetting(
    title: String,
    subtitle: String,
    value: StreamingQuality,
    onValue: (StreamingQuality) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(qualityLabel(value)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    StreamingQuality.entries.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(qualityLabel(quality)) },
                            onClick = {
                                onValue(quality)
                                expanded = false
                            },
                            leadingIcon = {
                                if (quality == value) Icon(Icons.Default.Check, null)
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
    )
}

@Composable
private fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { text ->
            { Text(text, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        },
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
    )
}

@Composable
private fun CompactFilterField(value: String, onValue: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({
            IconButton(onClick = { onValue("") }) { Icon(Icons.Default.Close, "Suche löschen") }
        }) else null,
    )
}

@Composable
private fun MonthDivider(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            Modifier.padding(horizontal = 9.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp, bottom = 2.dp))
}

@Composable
private fun ExpandableDescription(
    text: String,
    emptyText: String = "Keine Beschreibung hinterlegt.",
    collapsedLines: Int = 4,
) {
    val compact = remember(text) { compactDescription(text) }
    var expanded by rememberSaveable(compact) { mutableStateOf(false) }
    var hasOverflow by remember(compact) { mutableStateOf(false) }
    val shownText = compact.ifBlank { emptyText }
    Column {
        SelectionContainer {
            Text(
                text = shownText,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) hasOverflow = result.hasVisualOverflow
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = if (compact.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (hasOverflow || expanded) {
            Text(
                text = if (expanded) "Weniger anzeigen" else "Mehr anzeigen",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(top = 5.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun groupTitle(group: ResultGroup): String = when (group) {
    ResultGroup.DECLARED_MUSIC -> "Als Musik geführt"
    ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW -> "Wahrscheinlich DJ- oder Musikshow"
    ResultGroup.OTHER -> "Weitere Ergebnisse"
}

private fun activityLabel(status: ActivityStatus): String? = when (status) {
    ActivityStatus.ACTIVE_RECENT -> "Aktiv"
    ActivityStatus.ACTIVE_REGULAR -> "Regelmäßig aktiv"
    ActivityStatus.ACTIVE_IRREGULAR -> "Unregelmäßig aktiv"
    ActivityStatus.INACTIVE_RECENTLY -> "Seit einiger Zeit inaktiv"
    ActivityStatus.INACTIVE_LONG -> "Länger inaktiv"
    ActivityStatus.LIKELY_DISCONTINUED -> "Wahrscheinlich eingestellt"
    ActivityStatus.UNKNOWN -> null
}

private fun discoveryTarget(result: DiscoveryResult): IntegrationTarget? =
    (listOfNotNull(result.preferredTarget) + result.targets)
        .distinctBy { "${it.kind}:${it.url}" }
        .firstOrNull { it.kind in SHOW_TARGET_KINDS }

private fun discoverySubscriptionTargets(result: DiscoveryResult): List<IntegrationTarget> =
    (listOfNotNull(result.preferredTarget) + result.targets)
        .distinctBy { "${it.kind}:${it.url}:${it.feedUrl.orEmpty()}" }
        .filter(::isSubscribableUiTarget)

private fun isSubscribableUiTarget(target: IntegrationTarget): Boolean =
    target.kind in SUBSCRIBABLE_UI_TARGET_KINDS ||
        (target.kind == TargetKind.APPLE_PODCAST && !target.feedUrl.isNullOrBlank())

private fun targetLabel(target: IntegrationTarget?): String = when (target?.kind) {
    TargetKind.RSS_AUDIO -> "RSS · Audio"
    TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED -> "Feed"
    TargetKind.YOUTUBE_CHANNEL -> when (youtubeChannelTab(target.url)) {
        "streams" -> "YouTube · Livestreams"
        "videos" -> "YouTube · Videos"
        "shorts" -> "YouTube · Shorts"
        else -> "YouTube · Kanal"
    }
    TargetKind.YOUTUBE_PLAYLIST -> "YouTube · Playlist"
    TargetKind.SPOTIFY_SHOW -> "Spotify · Show"
    TargetKind.SPOTIFY_PLAYLIST -> "Spotify · Playlist"
    TargetKind.SPOTIFY_ARTIST -> "Spotify · Künstler"
    TargetKind.SOUNDCLOUD_PROFILE -> "SoundCloud · Profil"
    TargetKind.SOUNDCLOUD_PLAYLIST -> "SoundCloud · Playlist"
    TargetKind.MIXCLOUD_PROFILE -> "Mixcloud · Profil"
    TargetKind.APPLE_PODCAST -> "Apple Podcasts"
    TargetKind.PODCAST_INDEX_PAGE -> "Podcast Index"
    null -> "Keine Quelle"
    else -> when (target.requirement) {
        IntegrationRequirement.DIRECT_RSS_AUDIO -> "RSS · direkt abspielbar"
        IntegrationRequirement.FEED_AND_PLATFORM_PLAYER -> "Feed + Plattform-Player"
        IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED -> "Plattformquelle"
        IntegrationRequirement.RESOLUTION_REQUIRED -> "Quelle wird aufgelöst"
        IntegrationRequirement.EXTERNAL_ONLY -> "Externe Quelle"
    }
}

private fun youtubeChannelTab(raw: String): String? = runCatching {
    val uri = java.net.URI(raw)
    val host = uri.host?.lowercase() ?: return@runCatching null
    if (host != "youtube.com" && !host.endsWith(".youtube.com")) return@runCatching null
    uri.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase()
        .takeIf { it in setOf("streams", "videos", "shorts") }
}.getOrNull()

@Composable
private fun ProviderStatusStrip(statuses: List<ProviderStatus>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(statuses, key = { it.provider }) { status ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    "${providerName(status.provider)} · ${providerStatusText(status)}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun providerName(provider: ProviderId): String = when (provider) {
    ProviderId.APPLE_PODCASTS -> "Apple"
    ProviderId.PODCAST_INDEX -> "Podcast Index"
    ProviderId.GPODDER -> "gPodder"
    ProviderId.FEEDLY -> "Feedly"
    ProviderId.MIXCLOUD -> "Mixcloud"
    ProviderId.YOUTUBE -> "YouTube"
    ProviderId.SPOTIFY -> "Spotify"
    ProviderId.SOUNDCLOUD -> "SoundCloud"
    ProviderId.WEBSITE -> "Web"
}

private fun providerStatusText(status: ProviderStatus): String = when (status.state) {
    ProviderState.SEARCHING -> "sucht …"
    ProviderState.SUCCESS -> "${status.resultCount} Treffer"
    ProviderState.NO_RESULTS -> "0 Treffer"
    ProviderState.CREDENTIALS_MISSING -> "Zugang fehlt"
    ProviderState.RATE_LIMITED -> "begrenzt"
    ProviderState.TIMEOUT -> "Zeitlimit"
    ProviderState.UNAVAILABLE -> "nicht erreichbar"
    ProviderState.INVALID_RESPONSE -> "Antwortfehler"
    ProviderState.FAILED -> "Fehler"
    ProviderState.DISABLED -> "nicht aktiv"
}

private fun compactDescription(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .lineSequence()
    .map { it.trimEnd() }
    .toList()
    .joinToString("\n")
    .replace(Regex("\n[ \\t]*\n+"), "\n")
    .trim()

private fun monthLabel(epochMs: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(epochMs))

private fun heardDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun qualityLabel(quality: StreamingQuality): String = when (quality) {
    StreamingQuality.DATA_SAVER -> "Datensparend = 64 kbit/s"
    StreamingQuality.MEDIUM -> "Mittel = 128 kbit/s"
    StreamingQuality.HIGH -> "Hoch = 160 kbit/s"
    StreamingQuality.VERY_HIGH -> "Sehr hoch = 256 kbit/s"
    StreamingQuality.MAXIMUM -> "Maximal = beste verfügbare"
}

private val SHOW_TARGET_KINDS = setOf(
    TargetKind.RSS_AUDIO,
    TargetKind.RSS_VIDEO,
    TargetKind.ATOM_FEED,
    TargetKind.APPLE_PODCAST,
    TargetKind.PODCAST_INDEX_PAGE,
    TargetKind.YOUTUBE_CHANNEL,
    TargetKind.YOUTUBE_PLAYLIST,
    TargetKind.SPOTIFY_SHOW,
    TargetKind.SPOTIFY_PLAYLIST,
    TargetKind.SPOTIFY_ARTIST,
    TargetKind.MIXCLOUD_PROFILE,
    TargetKind.SOUNDCLOUD_PROFILE,
    TargetKind.SOUNDCLOUD_PLAYLIST,
)

private val SUBSCRIBABLE_UI_TARGET_KINDS = setOf(
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
