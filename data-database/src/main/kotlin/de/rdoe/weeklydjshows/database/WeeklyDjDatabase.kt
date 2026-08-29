package de.rdoe.weeklydjshows.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction

@Database(
    entities = [ShowEntity::class, EpisodeEntity::class, QueueEntryEntity::class, PlaybackHistoryEntity::class],
    version = 7,
    exportSchema = false,
)
@TypeConverters(DatabaseConverters::class)
abstract class WeeklyDjDatabase : RoomDatabase() {
    abstract fun showDao(): ShowDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    /** Applies an imported show snapshot atomically so observers never see a half-reordered grid. */
    suspend fun importShowView(shows: List<ShowEntity>, subscribedOrder: List<String>) = withTransaction {
        showDao().upsertAll(shows)
        subscribedOrder.distinct().forEachIndexed { index, id -> showDao().setSortOrder(id, index) }
    }

    /** Persists one completed drag operation atomically so the grid never observes partial moves. */
    suspend fun reorderSubscribed(showIds: List<String>) = withTransaction {
        showIds.distinct().forEachIndexed { index, id -> showDao().setSortOrder(id, index) }
    }

    companion object {
        @Volatile private var instance: WeeklyDjDatabase? = null

        fun get(context: Context): WeeklyDjDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WeeklyDjDatabase::class.java,
                "weekly-dj-shows.db",
            )
                // 1.3.0 deliberately starts from a clean, internally consistent catalogue.
                // This private app has no other installations to migrate and the owner explicitly
                // allowed replacing the pre-1.3 database representation.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
