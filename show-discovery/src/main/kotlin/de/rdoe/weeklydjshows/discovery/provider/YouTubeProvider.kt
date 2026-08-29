package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

/** Optional official YouTube Data API search. Channel results include YouTube's official Atom update feed. */
class YouTubeProvider : SearchProvider {
    override val id: ProviderId = ProviderId.YOUTUBE

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        val apiKey = context.secrets.get(SecretName.YOUTUBE_API_KEY)
        if (apiKey.isNullOrBlank()) {
            return ProviderResult(id, emptyList(), ProviderStatus(id, ProviderState.CREDENTIALS_MISSING, message = "YouTube Data API key missing; URL resolution still works without it"))
        }
        return try {
            val limit = request.maxResultsPerProvider.coerceAtMost(50)
            val url = buildString {
                append("https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel%2Cplaylist&q=")
                append(TextTools.encode(request.query))
                append("&maxResults=").append(limit)
                append("&key=").append(TextTools.encode(apiKey))
            }
            val response = context.http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 7_000))
            if (response.statusCode !in 200..299) {
                return ProviderResult(id, emptyList(), providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, response.text().take(250), response.header("Retry-After")?.toLongOrNull()))
            }
            val hits = parseJsonObject(response).array("items").mapIndexedNotNull { index, value ->
                val item = value.asObject() ?: return@mapIndexedNotNull null
                val idObj = item.obj("id") ?: return@mapIndexedNotNull null
                val snippet = item.obj("snippet") ?: return@mapIndexedNotNull null
                val kind = idObj.string("kind") ?: return@mapIndexedNotNull null
                val title = snippet.string("title") ?: return@mapIndexedNotNull null
                val thumbnails = snippet.obj("thumbnails")
                val artwork = listOf("maxres", "standard", "high", "medium", "default")
                    .firstNotNullOfOrNull { thumbnails?.obj(it)?.string("url") }
                val target = when (kind) {
                    "youtube#channel" -> {
                        val channelId = idObj.string("channelId") ?: return@mapIndexedNotNull null
                        IntegrationTarget(
                            kind = TargetKind.YOUTUBE_CHANNEL,
                            url = "https://www.youtube.com/channel/$channelId",
                            stableId = channelId,
                            feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
                            requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
                            title = title,
                            metadata = mapOf("atomFeedUrl" to "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                        )
                    }
                    "youtube#playlist" -> {
                        val playlistId = idObj.string("playlistId") ?: return@mapIndexedNotNull null
                        val feed = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
                        IntegrationTarget(
                            kind = TargetKind.YOUTUBE_PLAYLIST,
                            url = "https://www.youtube.com/playlist?list=$playlistId",
                            stableId = playlistId,
                            feedUrl = feed,
                            requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
                            title = title,
                            metadata = mapOf("atomFeedUrl" to feed),
                        )
                    }
                    else -> return@mapIndexedNotNull null
                }
                SourceHit(
                    provider = id,
                    providerItemId = target.stableId,
                    providerRank = index,
                    title = TextTools.stripHtml(title) ?: title,
                    publisher = TextTools.stripHtml(snippet.string("channelTitle")),
                    description = TextTools.stripHtml(snippet.string("description")),
                    artworkUrl = artwork,
                    lastPublishedEpochMillis = TextTools.parseDate(snippet.string("publishedAt")),
                    stableIds = mapOf("youtubeId" to (target.stableId ?: target.url)),
                    targets = listOf(target)
                )
            }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }
}
