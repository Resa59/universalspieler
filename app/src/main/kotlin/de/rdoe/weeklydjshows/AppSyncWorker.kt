package de.rdoe.weeklydjshows

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** One low-frequency sync covers native feeds and the small set of feed-less platform sources. */
class AppSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppSyncStatus.setRunning(true)
        return try {
            val categories = AppSettings.refreshCategories(applicationContext)
            val feeds = runCatching { AppGraph.feeds.refreshAll(categories) }
            val platforms = runCatching { AppGraph.platformRefresh.refreshAll(categories) }
            // Feed refreshes can also change artwork URLs. Cache newly discovered covers while an
            // unmetered connection is available; cover failures never turn a successful feed sync
            // into a failed worker.
            runCatching { ShowArtworkCache.prefetchSubscribed(applicationContext) }
            when {
                feeds.isFailure && platforms.isFailure -> Result.retry()
                else -> Result.success()
            }
        } finally {
            AppSyncStatus.setRunning(false)
        }
    }
}

object AppSyncStatus {
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    internal fun setRunning(value: Boolean) { _running.value = value }
}

object AppSyncScheduler {
    // Reuse the 1.0/1.1 unique names so WorkManager replaces the old feed-only worker in-place.
    private const val PERIODIC = "weekly-dj-shows-feed-sync"
    private const val INITIAL = "weekly-dj-shows-initial-sync"
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AppSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun initialSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<AppSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INITIAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(INITIAL)
    }
}
