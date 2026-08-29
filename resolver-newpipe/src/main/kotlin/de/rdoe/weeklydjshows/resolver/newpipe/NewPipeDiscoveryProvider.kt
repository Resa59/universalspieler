package de.rdoe.weeklydjshows.resolver.newpipe

import android.content.Context
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.provider.BrowseProvider
import de.rdoe.weeklydjshows.discovery.provider.ProviderContext
import de.rdoe.weeklydjshows.discovery.provider.SearchProvider
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android-host discovery adapter backed by the same pinned NewPipe Extractor as playback.
 * It intentionally returns containers only: channels/profiles and playlists, never individual
 * videos/tracks that could accidentally become a podcast subscription.
 */
class NewPipeDiscoveryProvider private constructor(
    context: Context,
    override val id: ProviderId,
    private val service: StreamingService,
    private val contentFilters: List<String>,
) : SearchProvider, BrowseProvider {
    @Suppress("unused")
    private val initializer = NewPipeStreamResolver(context.applicationContext)

    override val supportedModes: Set<BrowseMode> = setOf(
        BrowseMode.POPULAR,
        BrowseMode.TRENDING,
        BrowseMode.NEW,
        BrowseMode.RECENTLY_UPDATED,
        BrowseMode.GENRE,
        BrowseMode.RANDOM,
    )

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult =
        execute(request.query, request.maxResultsPerProvider, context)

    override fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult =
        execute(browseQuery(request), request.limit, context)

    private fun execute(query: String, maxResults: Int, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            // Search enough candidates in every container type before ranking locally. The old
            // split (30 total -> only 15 playlists on YouTube) could simply miss a very strong
            // playlist match. Manual discovery is infrequent, so one extra continuation page is
            // a good trade for materially better recall.
            val perFilter = if (id == ProviderId.YOUTUBE || id == ProviderId.SOUNDCLOUD) {
                (maxResults * 2).coerceIn(20, 60)
            } else {
                ((maxResults + contentFilters.size - 1) / contentFilters.size).coerceIn(1, 20)
            }
            val failures = mutableListOf<String>()
            // Channel/user and playlist search are independent. Running them concurrently keeps
            // the deeper playlist recall from doubling perceived search time, especially on
            // SoundCloud where one of the two result types can occasionally be slow.
            val filterExecutor = Executors.newFixedThreadPool(contentFilters.size.coerceAtLeast(1))
            val filterResults = try {
                val tasks = contentFilters.map { filter ->
                    Callable {
                        try {
                            FilterSearchResult(
                                filter = filter,
                                candidates = searchItemsWithRetry(query, filter, perFilter).mapIndexed { index, item ->
                                    SearchCandidate(item, index)
                                },
                            )
                        } catch (error: Throwable) {
                            if (Thread.currentThread().isInterrupted) throw error
                            FilterSearchResult(filter, emptyList(), error.message ?: error.javaClass.simpleName)
                        }
                    }
                }
                val futures = filterExecutor.invokeAll(tasks, FILTER_SEARCH_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                futures.mapIndexed { index, future ->
                    if (future.isCancelled) {
                        FilterSearchResult(contentFilters[index], emptyList(), "Zeitlimit")
                    } else {
                        runCatching { future.get() }.getOrElse { error ->
                            FilterSearchResult(contentFilters[index], emptyList(), error.message ?: error.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                filterExecutor.shutdownNow()
            }
            filterResults.mapNotNull { result -> result.error?.let { "${result.filter}: $it" } }.let(failures::addAll)
            val raw = filterResults
                .flatMap { it.candidates }
                .distinctBy { "${it.item.infoType}:${it.item.url}" }
                .sortedWith(
                    compareByDescending<SearchCandidate> { candidateMatch(query, it.item) }
                        .thenByDescending { if (it.item is ChannelInfoItem) 1 else 0 }
                        .thenBy { it.filterRank },
                )
                .take(maxResults)
            if (raw.isEmpty() && failures.size == contentFilters.size) {
                throw IllegalStateException(failures.joinToString(" · ").take(300))
            }
            val hits = raw.mapIndexedNotNull { rank, candidate -> toHit(query, rank, candidate.item) }
            ProviderResult(
                id,
                hits,
                ProviderStatus(
                    provider = id,
                    state = if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS,
                    resultCount = hits.size,
                    message = buildString {
                        append("NewPipe Extractor v0.26.4 · Profile/Playlists")
                        if (failures.isNotEmpty()) append(" · Teilfehler: ").append(failures.joinToString("; ").take(180))
                    },
                    durationMillis = context.nowEpochMillis() - started,
                ),
            )
        } catch (error: Throwable) {
            ProviderResult(
                id,
                emptyList(),
                ProviderStatus(
                    provider = id,
                    state = if (Thread.currentThread().isInterrupted) ProviderState.TIMEOUT else ProviderState.FAILED,
                    message = error.message?.take(180) ?: error.javaClass.simpleName,
                    durationMillis = context.nowEpochMillis() - started,
                ),
            )
        }
    }

    private data class SearchCandidate(val item: InfoItem, val filterRank: Int)
    private data class FilterSearchResult(
        val filter: String,
        val candidates: List<SearchCandidate>,
        val error: String? = null,
    )

    private fun candidateMatch(query: String, item: InfoItem): Double =
        TextTools.similarity(query, item.name)

    private fun searchItemsWithRetry(query: String, filter: String, limit: Int): List<InfoItem> {
        var firstFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                return searchItems(query, filter, limit)
            } catch (error: Throwable) {
                if (Thread.currentThread().isInterrupted) throw error
                if (attempt == 0) firstFailure = error else {
                    firstFailure?.let(error::addSuppressed)
                    throw error
                }
            }
        }
        return emptyList()
    }

    private fun searchItems(query: String, filter: String, limit: Int): List<InfoItem> {
        val extractor = service.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        var page: ListExtractor.InfoItemsPage<InfoItem> = extractor.initialPage
        val output = mutableListOf<InfoItem>()
        while (output.size < limit) {
            output += page.items
                .asSequence()
                .filter { it is ChannelInfoItem || it is PlaylistInfoItem }
                .filter { isUsefulShowContainer(query, it) }
                .take(limit - output.size)
                .toList()
            if (!page.hasNextPage() || output.size >= limit || Thread.currentThread().isInterrupted) break
            page = extractor.getPage(page.nextPage)
        }
        return output
    }

    /**
     * Platform search endpoints can return technically valid playlists that are useless as a
     * recurring show: YouTube auto-mixes and one-track/one-video playlists are the common cases.
     * Also reject playlist titles that are clearly one numbered episode derived from the query.
     */
    private fun isUsefulShowContainer(query: String, item: InfoItem): Boolean = when (item) {
        is ChannelInfoItem -> true
        is PlaylistInfoItem -> {
            val tooSmall = item.streamCount == 0L || item.streamCount == 1L
            val youtubeMix = id == ProviderId.YOUTUBE && youtubePlaylistId(item.url)?.startsWith("RD") == true
            !tooSmall && !youtubeMix && !TextTools.looksLikeEpisodeTitleForQuery(query, item.name)
        }
        else -> false
    }

    private fun youtubePlaylistId(url: String?): String? = url
        ?.let { Regex("[?&]list=([^&#]+)", RegexOption.IGNORE_CASE).find(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }

    private fun toHit(query: String, rank: Int, item: InfoItem): SourceHit? = when (item) {
        is ChannelInfoItem -> channelHit(query, rank, item)
        is PlaylistInfoItem -> playlistHit(rank, item)
        else -> null
    }

    private fun channelHit(query: String, rank: Int, item: ChannelInfoItem): SourceHit? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        val title = item.name?.takeIf { it.isNotBlank() } ?: return null
        val isYoutube = id == ProviderId.YOUTUBE
        val kind = if (isYoutube) TargetKind.YOUTUBE_CHANNEL else TargetKind.SOUNDCLOUD_PROFILE
        val targets = mutableListOf(
            IntegrationTarget(
                kind = kind,
                url = url,
                stableId = url,
                requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
                title = title,
                metadata = mapOf("resolver" to "newpipe-extractor-v0.26.4"),
            ),
        )
        // YouTube search itself returns a channel container, not its individual channel tabs.
        // For an exact channel-name search, expose /streams as one additional selectable source.
        // The preview resolver validates the tab when the user opens it, so discovery stays fast
        // and does not issue another channel request for every result.
        if (isYoutube && shouldOfferYoutubeLivestreams(query, title, rank)) {
            val streamsUrl = youtubeChannelTabUrl(url, "streams")
            targets += IntegrationTarget(
                kind = TargetKind.YOUTUBE_CHANNEL,
                url = streamsUrl,
                stableId = streamsUrl,
                requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
                title = title,
                metadata = mapOf(
                    "resolver" to "newpipe-extractor-v0.26.4",
                    "channelTab" to "streams",
                ),
            )
        }
        return SourceHit(
            provider = id,
            providerItemId = url,
            providerRank = rank,
            providerPopularity = item.subscriberCount.takeIf { it >= 0 }?.toDouble(),
            title = title,
            publisher = title,
            description = item.description?.takeIf { it.isNotBlank() },
            websiteUrl = url,
            artworkUrl = bestArtwork(item),
            categories = setOf(if (isYoutube) "YouTube" else "SoundCloud"),
            stableIds = mapOf((if (isYoutube) "youtubeChannel" else "soundcloudProfile") to url),
            targets = targets,
            rawMetadata = mapOf("resolver" to "newpipe-extractor-v0.26.4"),
        )
    }

    private fun shouldOfferYoutubeLivestreams(query: String, channelTitle: String, rank: Int): Boolean {
        if (rank >= MAX_YOUTUBE_CHANNEL_TAB_OFFER_RANK) return false
        val normalizedQuery = TextTools.normalizeText(query)
        val normalizedTitle = TextTools.normalizeText(channelTitle)
        return normalizedQuery.isNotBlank() && normalizedQuery == normalizedTitle
    }

    private fun youtubeChannelTabUrl(url: String, tab: String): String =
        url.substringBefore('#').substringBefore('?').trimEnd('/') + "/$tab"

    private fun playlistHit(rank: Int, item: PlaylistInfoItem): SourceHit? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        val title = item.name?.takeIf { it.isNotBlank() } ?: return null
        val isYoutube = id == ProviderId.YOUTUBE
        return SourceHit(
            provider = id,
            providerItemId = url,
            providerRank = rank,
            providerPopularity = item.streamCount.takeIf { it >= 0 }?.toDouble(),
            title = title,
            publisher = item.uploaderName?.takeIf { it.isNotBlank() },
            description = item.description?.content?.takeIf { it.isNotBlank() },
            websiteUrl = url,
            artworkUrl = bestArtwork(item),
            categories = setOf(if (isYoutube) "YouTube" else "SoundCloud"),
            stableIds = mapOf((if (isYoutube) "youtubePlaylist" else "soundcloudPlaylist") to url),
            targets = listOf(
                IntegrationTarget(
                    kind = if (isYoutube) TargetKind.YOUTUBE_PLAYLIST else TargetKind.SOUNDCLOUD_PLAYLIST,
                    url = url,
                    stableId = url,
                    requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
                    title = title,
                    metadata = mapOf("resolver" to "newpipe-extractor-v0.26.4"),
                ),
            ),
            rawMetadata = mapOf("resolver" to "newpipe-extractor-v0.26.4"),
        )
    }

    private fun bestArtwork(item: InfoItem): String? = item.thumbnails
        .maxByOrNull { image ->
            image.width.toLong().coerceAtLeast(0L) * image.height.toLong().coerceAtLeast(0L)
        }
        ?.url

    private fun browseQuery(request: BrowseRequest): String = when (request.mode) {
        BrowseMode.GENRE -> "${request.genre.orEmpty()} DJ mix"
        BrowseMode.NEW -> "DJ mix ${Calendar.getInstance().get(Calendar.YEAR)}"
        BrowseMode.RECENTLY_UPDATED -> "weekly DJ radio show"
        BrowseMode.TRENDING -> "DJ mix radio show"
        BrowseMode.POPULAR -> "DJ mix"
        BrowseMode.RANDOM -> "electronic music DJ mix"
    }

    companion object {
        private const val FILTER_SEARCH_BUDGET_MILLIS = 8_500L
        private const val MAX_YOUTUBE_CHANNEL_TAB_OFFER_RANK = 4

        fun youtube(context: Context): NewPipeDiscoveryProvider = NewPipeDiscoveryProvider(
            context,
            ProviderId.YOUTUBE,
            ServiceList.YouTube,
            listOf(YoutubeSearchQueryHandlerFactory.CHANNELS, YoutubeSearchQueryHandlerFactory.PLAYLISTS),
        )

        fun soundCloud(context: Context): NewPipeDiscoveryProvider = NewPipeDiscoveryProvider(
            context,
            ProviderId.SOUNDCLOUD,
            ServiceList.SoundCloud,
            listOf(SoundcloudSearchQueryHandlerFactory.USERS, SoundcloudSearchQueryHandlerFactory.PLAYLISTS),
        )
    }
}
