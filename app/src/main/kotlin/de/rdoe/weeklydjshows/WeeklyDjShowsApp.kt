package de.rdoe.weeklydjshows

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import de.rdoe.weeklydjshows.database.ShowEntity
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import de.rdoe.weeklydjshows.discovery.DiscoveryModule
import de.rdoe.weeklydjshows.feeds.FeedRepository
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.playback.PlaybackRepository
import de.rdoe.weeklydjshows.resolver.newpipe.NewPipeDiscoveryProvider
import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

class WeeklyDjShowsApp : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppDiagnostics.record(this, "Absturz/${thread.name}", "Unbehandelter App-Fehler", error)
            previousCrashHandler?.uncaughtException(thread, error)
        }
        migrateArtworkCacheLayout()
        AppGraph.initialize(this)
        BluetoothConnectionReceiver.syncEnabled(this)
        val curatedLayoutPending = BundledShowLayoutV128.needsApply(this)
        if (curatedLayoutPending) {
            // Set this synchronously so a ViewModel created immediately after Application.onCreate
            // already renders the requested mixed fixed/A-Z custom order.
            AppSettings(this).setShowOrderMode(ShowOrderMode.CUSTOM)
        }
        appScope.launch {
            val freshInstall = seedLegacyShowsIfNeeded()
            if (curatedLayoutPending) BundledShowLayoutV128.applyIfNeeded(this@WeeklyDjShowsApp, AppGraph.database)
            if (AppSettings.read(this@WeeklyDjShowsApp).refreshOnColdStart) {
                AppSyncScheduler.schedule(this@WeeklyDjShowsApp)
            } else {
                AppSyncScheduler.cancel(this@WeeklyDjShowsApp)
            }
            // Returning from the background never triggers a catalogue refresh. WorkManager owns
            // recurring sync; a cold start also no longer bulk-prefetches the whole cover library.
            // The visible grid warms only nearby covers, and the worker may fill the disk cache on
            // an unmetered connection. Only a freshly seeded database needs this one-time sync.
            if (freshInstall && AppSettings.read(this@WeeklyDjShowsApp).refreshOnColdStart) {
                AppSyncScheduler.initialSync(this@WeeklyDjShowsApp)
            }
        }
    }

    private fun migrateArtworkCacheLayout() {
        val prefs = getSharedPreferences("weekly_dj_internal", MODE_PRIVATE)
        if (prefs.getBoolean("show_only_artwork_cache_v1", false)) return
        // 1.0 mixed transient episode art and reusable show covers in this directory. 1.1 keeps
        // episode art memory-only, so discard the old mixed disk cache once.
        runCatching { cacheDir.resolve("episode_artwork").deleteRecursively() }
        prefs.edit().putBoolean("show_only_artwork_cache_v1", true).apply()
    }

    private suspend fun seedLegacyShowsIfNeeded(): Boolean {
        val dao = AppGraph.database.showDao()
        if (dao.count() > 0) return false
        val root = assets.open("shows.json").bufferedReader().use { JSONObject(it.readText()) }
        val array = root.getJSONArray("shows")
        val shows = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val feedUrl = item.getString("feedUrl")
            val youtubePlatformUrl = youtubePlatformUrl(feedUrl)
            ShowEntity(
                id = item.getString("id"),
                title = item.getString("name"),
                feedUrl = feedUrl.takeIf { youtubePlatformUrl == null },
                platformUrl = youtubePlatformUrl,
                sourceType = when {
                    "youtube.com/feeds/" in feedUrl && "playlist_id=" in feedUrl -> ShowSourceType.YOUTUBE_PLAYLIST
                    "youtube.com/feeds/" in feedUrl && "channel_id=" in feedUrl -> ShowSourceType.YOUTUBE_CHANNEL
                    else -> ShowSourceType.RSS
                },
                subscribed = item.optBoolean("enabled", true),
                sortOrder = item.optInt("sortOrder", index),
                legacyModuleId = item.optLong("legacyModuleId").takeIf { item.has("legacyModuleId") },
            )
        }
        dao.upsertAll(shows)
        return true
    }

    /** Fresh-install seed normalization only; existing databases are deliberately not migrated. */
    private fun youtubePlatformUrl(feedUrl: String): String? {
        if ("youtube.com/feeds/videos.xml" !in feedUrl.lowercase()) return null
        Regex("[?&]playlist_id=([^&#]+)", RegexOption.IGNORE_CASE)
            .find(feedUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                return "https://www.youtube.com/playlist?list=$it"
            }
        return Regex("[?&]channel_id=([^&#]+)", RegexOption.IGNORE_CASE)
            .find(feedUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/channel/$it" }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(
            OkHttpClient.Builder()
                // Buzzsprout's storage CDN rejects OkHttp's default `okhttp/x.y` user agent with
                // HTTP 403 for otherwise valid podcast artwork. Use an app identity for image
                // requests so feeds such as The Martin Garrix Show render their real covers.
                .addInterceptor(object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "WeeklyDJShows/${BuildConfig.VERSION_NAME} (Android; artwork)")
                            .build(),
                    )
                })
                .build()
        )
        .memoryCache(
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        )
        .diskCache(
            DiskCache.Builder()
                .directory(cacheDir.resolve("show_artwork"))
                // 48 MiB could evict part of the 138-show catalogue. Keep enough room for the
                // entire reusable cover set plus normal source-size variance.
                .maxSizeBytes(160L * 1024L * 1024L)
                .build()
        )
        // Cached show art should appear immediately when opening the same show detail.
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}

object AppGraph {
    lateinit var database: WeeklyDjDatabase
        private set
    lateinit var feeds: FeedRepository
        private set
    lateinit var playback: PlaybackRepository
        private set
    lateinit var platformRefresh: PlatformRefreshRepository
        private set
    lateinit var discovery: de.rdoe.weeklydjshows.discovery.DiscoveryEngine
        private set

    fun initialize(application: Application) {
        database = WeeklyDjDatabase.get(application)
        val feedHttp = OkHttpClient.Builder()
            // OkHttp automatically turns cached ETag/Last-Modified responses into conditional
            // requests, keeping recurring RSS/Atom refresh traffic small when servers support it.
            .cache(Cache(application.cacheDir.resolve("feed_http"), 12L * 1024L * 1024L))
            .build()
        feeds = FeedRepository(database, feedHttp)
        playback = PlaybackRepository(application, database)
        platformRefresh = PlatformRefreshRepository.create(application, database, feeds)
        val platformDiscovery = listOf(
            NewPipeDiscoveryProvider.youtube(application),
            NewPipeDiscoveryProvider.soundCloud(application),
        )
        discovery = DiscoveryModule.create(
            extraSearchProviders = platformDiscovery,
            extraBrowseProviders = platformDiscovery,
        )
    }
}
