package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest
import java.security.MessageDigest

class PodcastIndexProvider : SearchProvider, BrowseProvider {
    override val id: ProviderId = ProviderId.PODCAST_INDEX
    override val supportedModes: Set<BrowseMode> = setOf(
        BrowseMode.TRENDING,
        BrowseMode.NEW,
        BrowseMode.RECENTLY_UPDATED,
        BrowseMode.GENRE
    )

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val key = context.secrets.get(SecretName.PODCAST_INDEX_KEY)
            val secret = context.secrets.get(SecretName.PODCAST_INDEX_SECRET)
            val hits = if (!key.isNullOrBlank() && !secret.isNullOrBlank()) {
                searchAuthenticated(request, context, key, secret)
            } else {
                searchBasic(request, context)
            }
            ProviderResult(
                id,
                hits,
                ProviderStatus(
                    provider = id,
                    state = if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS,
                    resultCount = hits.size,
                    message = if (key.isNullOrBlank() || secret.isNullOrBlank()) "Basic keyless search; music endpoint and browse require Podcast Index credentials" else null,
                    durationMillis = context.nowEpochMillis() - started
                )
            )
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun searchBasic(request: SearchRequest, context: ProviderContext): List<SourceHit> {
        val url = "https://api.podcastindex.org/search?term=${TextTools.encode(request.query)}"
        val response = context.http.execute(
            HttpRequest(
                url = url,
                headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent),
                connectTimeoutMillis = 4_000,
                readTimeoutMillis = 6_000
            )
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Podcast Index basic search returned HTTP ${response.statusCode}")
        }
        val root = parseJsonObject(response)
        return root.array("results").take(request.maxResultsPerProvider).mapIndexedNotNull { index, value ->
            parseAppleReplacement(value.asObject() ?: return@mapIndexedNotNull null, index)
        }
    }

    private fun searchAuthenticated(
        request: SearchRequest,
        context: ProviderContext,
        key: String,
        secret: String
    ): List<SourceHit> {
        val max = request.maxResultsPerProvider.coerceAtMost(100)
        val musicUrl = "https://api.podcastindex.org/api/1.0/search/music/byterm?q=${TextTools.encode(request.query)}&max=$max&clean"
        val generalUrl = "https://api.podcastindex.org/api/1.0/search/byterm?q=${TextTools.encode(request.query)}&max=$max&clean"
        val musicHits = executeAuthenticated(musicUrl, context, key, secret)
            .array("feeds")
            .mapIndexedNotNull { index, value -> parseFeed(value.asObject() ?: return@mapIndexedNotNull null, index, true) }
        val generalHits = executeAuthenticated(generalUrl, context, key, secret)
            .array("feeds")
            .mapIndexedNotNull { index, value -> parseFeed(value.asObject() ?: return@mapIndexedNotNull null, index, false) }
        return (musicHits + generalHits).distinctBy {
            it.stableIds["podcastIndexId"] ?: TextTools.normalizeUrl(it.feedUrl) ?: "${it.title}|${it.publisher}"
        }
    }

    override fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        val key = context.secrets.get(SecretName.PODCAST_INDEX_KEY)
        val secret = context.secrets.get(SecretName.PODCAST_INDEX_SECRET)
        if (key.isNullOrBlank() || secret.isNullOrBlank()) {
            return ProviderResult(
                id,
                emptyList(),
                ProviderStatus(id, ProviderState.CREDENTIALS_MISSING, message = "Podcast Index key and secret are required for browse")
            )
        }
        return try {
            val max = request.limit.coerceAtMost(100)
            val language = request.language?.takeIf { it.isNotBlank() }?.let { "&lang=${TextTools.encode(it)}" }.orEmpty()
            val url = when (request.mode) {
                BrowseMode.TRENDING, BrowseMode.POPULAR ->
                    "https://api.podcastindex.org/api/1.0/podcasts/trending?max=$max$language"
                BrowseMode.NEW ->
                    "https://api.podcastindex.org/api/1.0/recent/newfeeds?max=$max"
                BrowseMode.RECENTLY_UPDATED ->
                    "https://api.podcastindex.org/api/1.0/recent/feeds?max=$max$language"
                BrowseMode.GENRE -> {
                    val genre = request.genre.orEmpty()
                    "https://api.podcastindex.org/api/1.0/search/music/byterm?q=${TextTools.encode(genre)}&max=$max&clean"
                }
                BrowseMode.RANDOM -> error("RANDOM is not supported by Podcast Index provider")
            }
            val root = executeAuthenticated(url, context, key, secret)
            val values = root.array("feeds").ifEmpty { root.array("items") }
            val hits = values.take(max).mapIndexedNotNull { index, value ->
                val item = value.asObject() ?: return@mapIndexedNotNull null
                parseFeed(item, index, item.string("medium")?.equals("music", true) == true)
            }
            ProviderResult(
                id,
                hits,
                ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started)
            )
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun executeAuthenticated(
        url: String,
        context: ProviderContext,
        key: String,
        secret: String
    ): Map<String, JsonValue> {
        val epochSeconds = context.nowEpochMillis() / 1000L
        val authorization = sha1(key + secret + epochSeconds)
        val response = context.http.execute(
            HttpRequest(
                url = url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to context.userAgent,
                    "X-Auth-Key" to key,
                    "X-Auth-Date" to epochSeconds.toString(),
                    "Authorization" to authorization
                ),
                connectTimeoutMillis = 4_000,
                readTimeoutMillis = 7_000
            )
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Podcast Index returned HTTP ${response.statusCode}: ${response.text().take(200)}")
        }
        return parseJsonObject(response)
    }

    private fun parseAppleReplacement(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val title = item.string("collectionName") ?: item.string("trackName") ?: item.string("title") ?: return null
        val feedUrl = item.string("feedUrl") ?: item.string("url")
        val appleId = item.long("collectionId")?.toString() ?: item.long("trackId")?.toString()
        val categories = (item.stringList("genres") + listOfNotNull(item.string("primaryGenreName"))).toSet()
        val declaredMusic = categories.any { TextTools.normalizeText(it) == "music" }
        return SourceHit(
            provider = id,
            providerItemId = item.long("id")?.toString() ?: appleId,
            providerRank = rank,
            title = title,
            publisher = item.string("artistName") ?: item.string("author"),
            description = TextTools.stripHtml(item.string("description")),
            feedUrl = feedUrl,
            websiteUrl = item.string("link"),
            artworkUrl = artworkFrom(item.string("artworkUrl600"), item.string("artworkUrl100"), item.string("image")),
            categories = categories,
            language = item.string("language"),
            lastPublishedEpochMillis = TextTools.parseDate(item.string("releaseDate")),
            declaredMusic = declaredMusic,
            declaredMusicReason = if (declaredMusic) "Podcast Index keyless response category Music" else null,
            stableIds = buildMap {
                item.long("id")?.let { put("podcastIndexId", it.toString()) }
                if (appleId != null) put("appleId", appleId)
            },
            targets = buildTargets(title, feedUrl, item.string("collectionViewUrl"), item.long("id")?.toString())
        )
    }

    private fun parseFeed(item: Map<String, JsonValue>, rank: Int, declaredByEndpoint: Boolean): SourceHit? {
        val title = item.string("title") ?: return null
        val feedUrl = item.string("url") ?: item.string("originalUrl")
        val website = item.string("link")
        val idValue = item.long("id")?.toString()
        val medium = item.string("medium")
        val declaredMusic = declaredByEndpoint || medium.equals("music", true)
        val categories = categoriesFrom(item["categories"])
        val popularity = item.double("trendScore") ?: item.double("score")
        return SourceHit(
            provider = id,
            providerItemId = idValue,
            providerRank = rank,
            providerPopularity = popularity,
            title = title,
            publisher = item.string("author") ?: item.string("ownerName"),
            description = TextTools.stripHtml(item.string("description")),
            feedUrl = feedUrl,
            websiteUrl = website,
            artworkUrl = artworkFrom(item.string("artwork"), item.string("image")),
            categories = categories,
            language = item.string("language"),
            lastPublishedEpochMillis = item.long("lastUpdateTime")?.let { it * 1000L }
                ?: item.long("newestItemPublishTime")?.let { it * 1000L },
            declaredMusic = declaredMusic,
            declaredMusicReason = when {
                declaredByEndpoint -> "Podcast Index music search endpoint"
                medium.equals("music", true) -> "podcast:medium=music"
                else -> null
            },
            stableIds = buildMap {
                if (idValue != null) put("podcastIndexId", idValue)
                item.string("podcastGuid")?.let { put("podcastGuid", it) }
                item.long("itunesId")?.let { put("appleId", it.toString()) }
            },
            targets = buildTargets(title, feedUrl, website, idValue),
            rawMetadata = buildMap {
                if (medium != null) put("medium", medium)
                item.long("dead")?.let { put("dead", it.toString()) }
                item.long("lastHttpStatus")?.let { put("lastHttpStatus", it.toString()) }
            }
        )
    }

    private fun buildTargets(title: String, feedUrl: String?, externalUrl: String?, idValue: String?): List<IntegrationTarget> = buildList {
        if (feedUrl != null) add(
            IntegrationTarget(
                kind = TargetKind.RSS_AUDIO,
                url = feedUrl,
                feedUrl = feedUrl,
                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
                title = title
            )
        )
        if (idValue != null) add(
            IntegrationTarget(
                kind = TargetKind.PODCAST_INDEX_PAGE,
                url = "https://podcastindex.org/podcast/$idValue",
                stableId = idValue,
                requirement = IntegrationRequirement.EXTERNAL_ONLY,
                title = title
            )
        ) else if (externalUrl != null) add(
            IntegrationTarget(
                kind = TargetKind.WEBSITE,
                url = externalUrl,
                requirement = IntegrationRequirement.RESOLUTION_REQUIRED,
                title = title
            )
        )
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
