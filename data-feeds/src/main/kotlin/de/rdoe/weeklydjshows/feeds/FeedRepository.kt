package de.rdoe.weeklydjshows.feeds

import de.rdoe.weeklydjshows.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RefreshSummary(val succeeded: Int, val failed: Int, val newOrUpdatedEpisodes: Int)

data class FeedPreviewEpisode(
    val title: String,
    val publishedAtEpochMs: Long?,
    val durationMs: Long?,
)

data class FeedPreview(
    val title: String?,
    val description: String,
    val artworkUrl: String?,
    val episodeCount: Int,
    val episodes: List<FeedPreviewEpisode>,
)

data class FeedCleanupResult(
    val removedEpisodes: Int,
    val feedEpisodeCount: Int,
    val cleanupPerformed: Boolean,
)

private data class DetailedRefreshResult(
    val storedEpisodes: Int,
    val removedEpisodes: Int,
    val feedEpisodeCount: Int,
    val cleanupPerformed: Boolean,
)

class FeedRepository(
    private val database: WeeklyDjDatabase,
    client: OkHttpClient? = null,
    private val parser: FeedParser = FeedParser(),
) {
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Parse the selected discovery feed without inserting a show or episodes into Room. */
    suspend fun preview(
        feedUrl: String,
        titleHint: String,
        maxEpisodes: Int = 20,
    ): FeedPreview = withContext(Dispatchers.IO) {
        require(maxEpisodes > 0) { "maxEpisodes muss positiv sein" }
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "WeeklyDJShows/1.3.1 Android")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Leere Feed-Antwort")
            val previewShow = ShowEntity(
                id = "discovery-preview",
                title = titleHint.ifBlank { "Feed-Vorschau" },
                feedUrl = feedUrl,
                subscribed = false,
            )
            val parsed = body.byteStream().use { parser.parse(previewShow, it, maxEpisodes) }
            FeedPreview(
                title = parsed.title,
                description = parsed.description,
                artworkUrl = parsed.artworkUrl,
                episodeCount = parsed.allEpisodeIds.size,
                episodes = parsed.episodes.map { episode ->
                    FeedPreviewEpisode(
                        title = episode.title,
                        publishedAtEpochMs = episode.publishedAtEpochMs,
                        durationMs = episode.durationMs,
                    )
                },
            )
        }
    }

    suspend fun refresh(show: ShowEntity): Int = refreshDetailed(
        show = show,
        cleanupMissing = show.autoPruneMissingEpisodes,
    ).storedEpisodes

    /**
     * Fetch once, update the podcast, then remove cache rows proven absent from that successful
     * RSS response. An empty feed is treated conservatively and never triggers a mass deletion.
     */
    suspend fun cleanupMissingEpisodes(show: ShowEntity): FeedCleanupResult {
        require(show.sourceType == de.rdoe.weeklydjshows.model.ShowSourceType.RSS && show.feedUrl != null) {
            "Bereinigen ist nur für RSS-Podcasts verfügbar"
        }
        val result = refreshDetailed(show, cleanupMissing = true)
        return FeedCleanupResult(
            removedEpisodes = result.removedEpisodes,
            feedEpisodeCount = result.feedEpisodeCount,
            cleanupPerformed = result.cleanupPerformed,
        )
    }

    private suspend fun refreshDetailed(
        show: ShowEntity,
        cleanupMissing: Boolean,
    ): DetailedRefreshResult = withContext(Dispatchers.IO) {
        val feedUrl = show.feedUrl ?: throw IllegalArgumentException("Für ${show.title} ist kein Feed hinterlegt")
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "WeeklyDJShows/1.3.1 Android")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (show.origin == ShowOrigin.BUNDLED && response.code in DEFINITELY_GONE_HTTP_CODES) {
                        database.showDao().setSubscribed(show.id, false)
                    }
                    error("HTTP ${response.code}")
                }
                val body = response.body ?: error("Leere Feed-Antwort")
                val parsed = body.byteStream().use {
                    parser.parse(show, it, maxEpisodes = RSS_STORED_EPISODE_LIMIT)
                }
                if (show.origin == ShowOrigin.BUNDLED && parsed.allEpisodeIds.isEmpty()) {
                    database.showDao().setSubscribed(show.id, false)
                    database.showDao().recordRefreshError(show.id, now, "Feed enthält keine Folgen")
                    return@withContext DetailedRefreshResult(0, 0, 0, false)
                }
                val ids = parsed.episodes.map { it.id }
                val existing = if (ids.isEmpty()) emptyMap() else database.episodeDao().get(ids).associateBy { it.id }
                val merged = parsed.episodes.map { incoming ->
                    existing[incoming.id]?.let { old ->
                        incoming.copy(
                            liked = old.liked,
                            downloadStatus = old.downloadStatus,
                            localFilePath = old.localFilePath,
                            localArtworkPath = old.localArtworkPath,
                            downloadedBytes = old.downloadedBytes,
                            downloadTotalBytes = old.downloadTotalBytes,
                            positionMs = old.positionMs,
                            playbackDurationMs = old.playbackDurationMs,
                            lastPlayedAtEpochMs = old.lastPlayedAtEpochMs,
                            completedAtEpochMs = old.completedAtEpochMs,
                            discoveredAtEpochMs = old.discoveredAtEpochMs,
                            publishedAtEpochMs = incoming.publishedAtEpochMs ?: old.publishedAtEpochMs,
                            publishedText = incoming.publishedText.ifBlank { old.publishedText },
                        )
                    } ?: incoming
                }
                database.episodeDao().upsertAll(merged)
                database.showDao().recordRefresh(
                    show.id,
                    parsed.title.orEmpty(),
                    parsed.artworkUrl,
                    parsed.description,
                    now,
                )
                // Compare against every ID parsed from the feed, not only the 1,000 rows retained
                // locally. That guarantees an episode still present in a very long RSS feed is
                // never misclassified as stale. Protect explicit user state from auto-cleanup.
                val cleanupPerformed = cleanupMissing && parsed.allEpisodeIds.isNotEmpty()
                var removed = 0
                if (cleanupPerformed) {
                    val staleIds = database.episodeDao().getForShow(show.id)
                        .asSequence()
                        .map { it.id }
                        .filterNot(parsed.allEpisodeIds::contains)
                        .toList()
                    for (ids in staleIds.chunked(CLEANUP_DELETE_BATCH)) {
                        removed += database.episodeDao().deleteUnprotected(ids)
                    }
                }
                // A cleanup request has a stricter contract than the generic 1,000-row cache
                // cap: anything still advertised by the feed must remain. Therefore the normal
                // cap only runs for non-cleanup refreshes; cleanup itself removes proven-stale
                // rows above and leaves current feed rows untouched.
                if (!cleanupMissing) {
                    database.episodeDao().pruneShow(show.id, keep = RSS_STORED_EPISODE_LIMIT)
                }
                DetailedRefreshResult(
                    storedEpisodes = merged.size,
                    removedEpisodes = removed,
                    feedEpisodeCount = parsed.allEpisodeIds.size,
                    cleanupPerformed = cleanupPerformed,
                )
            }
        } catch (error: Throwable) {
            database.showDao().recordRefreshError(
                show.id,
                now,
                (error.message ?: error.javaClass.simpleName).take(240),
            )
            throw error
        }
    }

    suspend fun refreshAll(
        categories: Set<PodcastCategory> = PodcastCategory.entries.toSet(),
    ): RefreshSummary = coroutineScope {
        val shows = database.showDao().getSubscribed().filter {
            it.feedUrl != null && it.category in categories
        }
        val semaphore = Semaphore(6)
        val results = shows.map { show ->
            async(Dispatchers.IO) {
                semaphore.withPermit { runCatching { refresh(show) } }
            }
        }.awaitAll()
        RefreshSummary(
            succeeded = results.count { it.isSuccess },
            failed = results.count { it.isFailure },
            newOrUpdatedEpisodes = results.sumOf { it.getOrDefault(0) },
        )
    }

    /** Clears only conditional HTTP/feed response cache; episode state and downloads are untouched. */
    fun clearHttpCache() {
        runCatching { http.cache?.evictAll() }
    }

    private companion object {
        // Stay well below SQLite's historical bind-variable ceiling on older Android versions.
        const val CLEANUP_DELETE_BATCH = 400
        val DEFINITELY_GONE_HTTP_CODES = setOf(404, 410)
    }
}
