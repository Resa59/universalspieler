package de.rdoe.weeklydjshows.feeds

import android.content.Context
import androidx.work.*
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import java.util.concurrent.TimeUnit

class FeedSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val summary = FeedRepository(WeeklyDjDatabase.get(applicationContext)).refreshAll()
        return if (summary.succeeded > 0 || summary.failed == 0) Result.success() else Result.retry()
    }
}

object FeedSyncScheduler {
    private const val PERIODIC = "weekly-dj-shows-feed-sync"
    private const val INITIAL = "weekly-dj-shows-initial-sync"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<FeedSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    fun initialSync(context: Context) {
        val oneTime = OneTimeWorkRequestBuilder<FeedSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INITIAL,
            ExistingWorkPolicy.KEEP,
            oneTime,
        )
    }
}
