package de.rdoe.weeklydjshows.resolver.newpipe

import android.content.Context
import de.rdoe.weeklydjshows.model.*
import de.rdoe.weeklydjshows.resolver.PlatformListingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.security.MessageDigest

/** Lists playlist-style platform subscriptions without leaking NewPipe classes into the app layer. */
class NewPipePlatformListingResolver(context: Context) : PlatformListingResolver {
    @Suppress("unused")
    private val streamResolver = NewPipeStreamResolver(context)

    override fun supports(sourceType: ShowSourceType, url: String): Boolean =
        sourceType in setOf(
            ShowSourceType.YOUTUBE_CHANNEL,
            ShowSourceType.YOUTUBE_PLAYLIST,
            ShowSourceType.SOUNDCLOUD,
            ShowSourceType.BANDCAMP,
        ) && runCatching {
            NewPipe.getServiceByUrl(url).getLinkTypeByUrl(url) in setOf(
                StreamingService.LinkType.CHANNEL,
                StreamingService.LinkType.PLAYLIST,
            )
        }.getOrDefault(false)

    override suspend fun list(sourceType: ShowSourceType, url: String, maxItems: Int): Result<PlatformListing> =
        withContext(Dispatchers.IO) {
            runCatching {
                val service = NewPipe.getServiceByUrl(url)
                when (service.getLinkTypeByUrl(url)) {
                    StreamingService.LinkType.PLAYLIST -> listPlaylist(service, url, maxItems)
                    StreamingService.LinkType.CHANNEL -> listChannel(service, url, maxItems)
                    else -> throw IllegalArgumentException("URL ist weder Profil/Kanal noch Playlist")
                }
            }
        }

    override suspend fun discoverFeedUrl(sourceType: ShowSourceType, url: String): String? =
        withContext(Dispatchers.IO) {
            if (!supports(sourceType, url)) return@withContext null
            runCatching {
                val service = NewPipe.getServiceByUrl(url)
                when (service.getLinkTypeByUrl(url)) {
                    StreamingService.LinkType.CHANNEL -> {
                        val channel = service.getChannelExtractor(url)
                        channel.fetchPage()
                        channel.feedUrl?.takeIf { it.isNotBlank() }
                    }
                    StreamingService.LinkType.PLAYLIST -> youtubePlaylistFeed(url)
                    else -> null
                }
            }.getOrNull()
        }

    private fun listPlaylist(service: StreamingService, url: String, maxItems: Int): PlatformListing {
        val extractor = service.getPlaylistExtractor(url)
        extractor.fetchPage()
        val items = collectStreamItems(extractor, extractor.initialPage, maxItems)
        return PlatformListing(
            title = extractor.name,
            artworkUrl = bestArtwork(extractor.thumbnails),
            publisher = extractor.uploaderName?.takeIf { it.isNotBlank() },
            description = extractor.description?.content.orEmpty(),
            episodes = platformEpisodes(items),
        )
    }

    private fun listChannel(service: StreamingService, url: String, maxItems: Int): PlatformListing {
        val channel = service.getChannelExtractor(url)
        channel.fetchPage()
        val items = mutableListOf<StreamInfoItem>()
        val requestedTab = requestedYoutubeChannelTab(url)
        val tabs = if (requestedTab == null) {
            channel.tabs
        } else {
            channel.tabs.filter { tabHandler ->
                tabHandler.contentFilters.any { it.equals(requestedTab, ignoreCase = true) }
            }.ifEmpty {
                // An explicit channel tab must never silently widen to the complete channel.
                throw IllegalArgumentException("Der angeforderte YouTube-Kanalbereich ist derzeit nicht verfügbar")
            }
        }
        // Profiles without an explicit tab keep the service-specific behavior (YouTube videos,
        // SoundCloud tracks, etc.). For /streams, /videos or /shorts only the matching NewPipe
        // ListLinkHandler reaches the extractor.
        tabs.forEach { tabHandler ->
            if (items.size >= maxItems) return@forEach
            runCatching {
                val tab = service.getChannelTabExtractor(tabHandler)
                tab.fetchPage()
                items += collectStreamItems(tab, tab.initialPage, maxItems - items.size)
            }
        }
        return PlatformListing(
            title = channel.name,
            artworkUrl = bestArtwork(channel.avatars),
            publisher = channel.name?.takeIf { it.isNotBlank() },
            description = channel.description.orEmpty(),
            episodes = platformEpisodes(items.distinctBy { it.url }.take(maxItems)),
        )
    }

    private fun requestedYoutubeChannelTab(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return null
        return when (uri.path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase()) {
            "streams" -> ChannelTabs.LIVESTREAMS
            "videos" -> ChannelTabs.VIDEOS
            "shorts" -> ChannelTabs.SHORTS
            else -> null
        }
    }

    private fun <T : org.schabi.newpipe.extractor.InfoItem> collectStreamItems(
        extractor: ListExtractor<T>,
        initialPage: ListExtractor.InfoItemsPage<T>,
        maxItems: Int,
    ): List<StreamInfoItem> {
        var page = initialPage
        val items = mutableListOf<StreamInfoItem>()
        while (items.size < maxItems) {
            items += page.items.filterIsInstance<StreamInfoItem>().take(maxItems - items.size)
            if (!page.hasNextPage() || items.size >= maxItems || Thread.currentThread().isInterrupted) break
            page = extractor.getPage(page.nextPage)
        }
        return items
    }

    private fun platformEpisodes(items: List<StreamInfoItem>): List<PlatformEpisode> {
        val now = System.currentTimeMillis()
        return items.distinctBy { it.url }.map { item ->
            val publishedAt = item.uploadDate?.instant?.toEpochMilli()
            val youtube = episodeType(item.url) == EpisodeSourceType.YOUTUBE
            // YouTube exposes scheduled premieres/live streams in playlist listings before their
            // stream is playable. Treat the future timestamp as a hint, never as a hard block:
            // an explicit tap on Play still asks the extractor before showing the explanation.
            val scheduled = item.contentAvailability == ContentAvailability.UPCOMING ||
                (youtube && publishedAt != null && publishedAt > now + CLOCK_SKEW_TOLERANCE_MS)
            PlatformEpisode(
                stableId = sha256(item.url),
                url = item.url,
                title = item.name,
                description = item.shortDescription.orEmpty(),
                artworkUrl = bestArtwork(item.thumbnails),
                durationMs = item.duration.takeIf { it > 0 }?.times(1000),
                publishedAtEpochMs = publishedAt,
                publishedText = item.textualUploadDate.orEmpty(),
                availability = when {
                    scheduled -> PlatformAvailability.SCHEDULED
                    item.contentAvailability == ContentAvailability.AVAILABLE -> PlatformAvailability.AVAILABLE
                    else -> PlatformAvailability.UNKNOWN
                },
                scheduledForEpochMs = publishedAt.takeIf { scheduled },
                sourceType = episodeType(item.url),
            )
        }
    }

    private fun bestArtwork(images: List<org.schabi.newpipe.extractor.Image>): String? = images
        .maxByOrNull { image ->
            image.width.toLong().coerceAtLeast(0L) * image.height.toLong().coerceAtLeast(0L)
        }
        ?.url

    private fun episodeType(url: String): EpisodeSourceType = when {
        "youtube.com" in url || "youtu.be" in url -> EpisodeSourceType.YOUTUBE
        "soundcloud.com" in url -> EpisodeSourceType.SOUNDCLOUD
        "bandcamp.com" in url -> EpisodeSourceType.BANDCAMP
        else -> EpisodeSourceType.UNKNOWN_WEBPAGE
    }

    private fun youtubePlaylistFeed(url: String): String? {
        if (!("youtube.com" in url || "youtu.be" in url)) return null
        val playlistId = Regex("[?&]list=([^&#]+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
