package de.rdoe.weeklydjshows

import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.discovery.network.UrlConnectionHttpClient
import de.rdoe.weeklydjshows.discovery.provider.SpotifyPublicCatalog
import de.rdoe.weeklydjshows.feeds.FeedRepository
import de.rdoe.weeklydjshows.model.PlatformListing
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.resolver.PlatformListingResolver
import de.rdoe.weeklydjshows.resolver.newpipe.NewPipePlatformListingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest

class PlatformRefreshRepository(
    private val database: WeeklyDjDatabase,
    private val feeds: FeedRepository,
    private val resolvers: List<PlatformListingResolver>,
) {
    /** Reads platform metadata without creating or updating a subscription. */
    suspend fun preview(sourceType: ShowSourceType, url: String, maxItems: Int = 20): Result<PlatformListing> {
        val resolver = resolverFor(sourceType, url)
            ?: return Result.failure(UnsupportedOperationException("Für diese Plattformquelle gibt es keinen Listen-Adapter"))
        return resolver.list(sourceType, url, maxItems.coerceIn(1, 100))
    }

    suspend fun refresh(show: ShowEntity): Result<Int> {
        if (show.sourceType == ShowSourceType.SPOTIFY_PLAYLIST) {
            return Result.failure(UnsupportedOperationException("Spotify-Playlists werden nur als externe Verknüpfung gespeichert"))
        }
        val url = show.platformUrl ?: return Result.failure(IllegalArgumentException("Kein Plattformlink gespeichert"))
        val resolver = resolverFor(show)
            ?: return Result.failure(UnsupportedOperationException("Für diese Plattformquelle gibt es noch keinen Listen-Adapter"))
        if (!resolver.supports(show.sourceType, url)) {
            return Result.failure(UnsupportedOperationException("Für diese Plattformquelle gibt es noch keinen Listen-Adapter"))
        }

        // SoundCloud profiles may expose a useful podcast RSS feed. YouTube Atom feeds are not
        // preferred here: they contain only a small recent window and would truncate playlists.
        if (show.feedUrl == null && shouldPreferNativeFeed(show, url)) {
            resolver.discoverFeedUrl(show.sourceType, url)?.takeIf { it.isNotBlank() }?.let { feedUrl ->
                val feedShow = show.copy(feedUrl = feedUrl)
                database.showDao().upsert(feedShow)
                runCatching { feeds.refresh(feedShow) }.onSuccess { return Result.success(it) }
                // A transient feed failure must not strand the show: continue with the platform
                // listing now while keeping the feed for the next cheap retry.
            }
        }
        val now = System.currentTimeMillis()
        val playlistSource = isPlaylistSource(show, url)
        val storedEpisodeCount = database.episodeDao().countForShow(show.id)
        val maxItems = when {
            playlistSource -> MAX_PLAYLIST_ITEMS
            storedEpisodeCount == 0 -> MAX_INITIAL_CHANNEL_ITEMS
            else -> MAX_CHANNEL_REFRESH_ITEMS
        }
        return resolver.list(show.sourceType, url, maxItems = maxItems).mapCatching { listing ->
            val incoming = listing.episodes.map { item ->
                EpisodeEntity(
                    id = sha256("${show.id}|${item.stableId}"),
                    showId = show.id,
                    title = item.title,
                    description = item.description,
                    pageUrl = item.url,
                    sourceType = item.sourceType,
                    publishedAtEpochMs = item.publishedAtEpochMs,
                    publishedText = item.publishedText,
                    artworkUrl = item.artworkUrl,
                    durationMs = item.durationMs,
                    discoveredAtEpochMs = now,
                    availability = when (item.availability) {
                        de.rdoe.weeklydjshows.model.PlatformAvailability.SCHEDULED -> EpisodeAvailability.SCHEDULED
                        de.rdoe.weeklydjshows.model.PlatformAvailability.AVAILABLE -> EpisodeAvailability.AVAILABLE
                        de.rdoe.weeklydjshows.model.PlatformAvailability.UNKNOWN -> EpisodeAvailability.UNKNOWN
                    },
                    scheduledForEpochMs = item.scheduledForEpochMs,
                    // The listing itself is the one initial availability observation used by
                    // "Neu". It does not resolve the stream and is unrelated to queue attempts.
                    availabilityCheckedAtEpochMs = now.takeIf {
                        item.availability != de.rdoe.weeklydjshows.model.PlatformAvailability.UNKNOWN
                    },
                )
            }
            val existing = if (incoming.isEmpty()) emptyMap() else database.episodeDao()
                .get(incoming.map { it.id }).associateBy { it.id }
            database.episodeDao().upsertAll(incoming.map { episode ->
                existing[episode.id]?.let { old ->
                    episode.copy(
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
                        publishedAtEpochMs = episode.publishedAtEpochMs ?: old.publishedAtEpochMs,
                        publishedText = episode.publishedText.ifBlank { old.publishedText },
                        availability = when (episode.availability) {
                            EpisodeAvailability.AVAILABLE -> EpisodeAvailability.AVAILABLE
                            EpisodeAvailability.SCHEDULED -> EpisodeAvailability.SCHEDULED
                            EpisodeAvailability.UNKNOWN -> old.availability
                        },
                        scheduledForEpochMs = when (episode.availability) {
                            EpisodeAvailability.AVAILABLE -> null
                            else -> episode.scheduledForEpochMs ?: old.scheduledForEpochMs
                        },
                        availabilityCheckedAtEpochMs = when {
                            episode.availability == EpisodeAvailability.AVAILABLE -> now
                            episode.availability == EpisodeAvailability.SCHEDULED &&
                                episode.scheduledForEpochMs?.let { it <= now } == true -> null
                            else -> old.availabilityCheckedAtEpochMs
                        },
                    )
                } ?: episode
            })
            // No timer checks upcoming entries. Only an ordinary show refresh that has reached
            // the announced time permits one fresh attempt when that entry later is (or already
            // remains) the actual queue head.
            incoming.asSequence()
                .filter { episode ->
                    episode.availability == EpisodeAvailability.SCHEDULED &&
                        episode.scheduledForEpochMs?.let { it <= now } == true
                }
                .forEach { database.queueDao().clearAvailabilityAttempt(it.id) }
            database.showDao().recordRefresh(show.id, listing.title.orEmpty(), listing.artworkUrl, listing.description, now)
            database.episodeDao().pruneShow(
                show.id,
                keep = if (playlistSource) MAX_PLAYLIST_ITEMS else MAX_INITIAL_CHANNEL_ITEMS,
            )
            incoming.size
        }.onFailure { error ->
            database.showDao().recordRefreshError(show.id, now, error.message.orEmpty().take(240))
        }
    }

    suspend fun refreshAll(
        categories: Set<PodcastCategory> = PodcastCategory.entries.toSet(),
    ): Pair<Int, Int> = coroutineScope {
        // Feed-backed platform shows are touched only when the immediately preceding feed refresh
        // failed. This provides a fallback without doubling normal background traffic.
        val shows = database.showDao().getSubscribed().filter {
            it.category in categories &&
                it.sourceType != ShowSourceType.SPOTIFY_PLAYLIST &&
                it.platformUrl != null && (it.feedUrl == null || it.lastRefreshError != null)
        }
        val semaphore = Semaphore(MAX_PARALLEL_PLATFORM_REFRESHES)
        val results = shows.mapNotNull { show ->
            if (resolverFor(show) == null) null else async(Dispatchers.IO) {
                semaphore.withPermit { refresh(show) }
            }
        }.map { it.await() }
        results.count { it.isSuccess } to results.count { it.isFailure }
    }

    private fun shouldPreferNativeFeed(show: ShowEntity, url: String): Boolean =
        show.sourceType == ShowSourceType.SOUNDCLOUD && !isSoundCloudPlaylist(url)

    private fun isPlaylistSource(show: ShowEntity, url: String): Boolean =
        show.sourceType == ShowSourceType.YOUTUBE_PLAYLIST ||
            (show.sourceType == ShowSourceType.SOUNDCLOUD && isSoundCloudPlaylist(url))

    private fun isSoundCloudPlaylist(url: String): Boolean =
        Regex("https?://(?:www\\.)?soundcloud\\.com/[^/?#]+/sets/", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun resolverFor(show: ShowEntity): PlatformListingResolver? {
        val url = show.platformUrl ?: return null
        return resolverFor(show.sourceType, url)
    }

    private fun resolverFor(sourceType: ShowSourceType, url: String): PlatformListingResolver? =
        resolvers.firstOrNull { it.supports(sourceType, url) }

    companion object {
        // A playlist is a membership list and can be edited away from the first page, so scan it
        // deeply. Channels are chronological: after the deep initial load a large recent window
        // catches new uploads without re-downloading years of history on every app start.
        private const val MAX_PLAYLIST_ITEMS = 5_000
        private const val MAX_INITIAL_CHANNEL_ITEMS = 2_000
        private const val MAX_CHANNEL_REFRESH_ITEMS = 250
        private const val MAX_PARALLEL_PLATFORM_REFRESHES = 3

        fun create(context: android.content.Context, database: WeeklyDjDatabase, feeds: FeedRepository): PlatformRefreshRepository {
            val spotifyCatalog = SpotifyPublicCatalog(
                UrlConnectionHttpClient("WeeklyDJShows/${BuildConfig.VERSION_NAME} SpotifyMetadata"),
                "WeeklyDJShows/${BuildConfig.VERSION_NAME} SpotifyMetadata",
            )
            return PlatformRefreshRepository(
                database,
                feeds,
                listOf(
                    NewPipePlatformListingResolver(context),
                    MixcloudPlatformListingResolver(),
                    SpotifyPlatformListingResolver(spotifyCatalog),
                ),
            )
        }
    }
}
