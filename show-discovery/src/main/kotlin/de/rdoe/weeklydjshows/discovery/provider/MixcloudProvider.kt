package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

class MixcloudProvider : SearchProvider, BrowseProvider {
    override val id: ProviderId = ProviderId.MIXCLOUD
    override val supportedModes: Set<BrowseMode> = setOf(BrowseMode.POPULAR, BrowseMode.TRENDING, BrowseMode.NEW, BrowseMode.GENRE)

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val limit = request.maxResultsPerProvider.coerceAtMost(50)
            val cloudcasts = executeSearch(request.query, "cloudcast", limit, context).mapIndexedNotNull { index, value -> parseCloudcast(value.asObject() ?: return@mapIndexedNotNull null, index) }
            val users = executeSearch(request.query, "user", limit.coerceAtMost(20), context).mapIndexedNotNull { index, value -> parseUser(value.asObject() ?: return@mapIndexedNotNull null, index) }
            val hits = cloudcasts + users
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    override fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val limit = request.limit.coerceAtMost(100)
            val values = when (request.mode) {
                BrowseMode.POPULAR, BrowseMode.TRENDING -> executeList("https://api.mixcloud.com/popular/hot/?limit=$limit", context)
                BrowseMode.NEW -> executeList("https://api.mixcloud.com/new/?limit=$limit", context)
                BrowseMode.GENRE -> executeSearch(request.genre.orEmpty(), "cloudcast", limit, context)
                else -> emptyList()
            }
            val hits = values.take(limit).mapIndexedNotNull { index, value -> parseCloudcast(value.asObject() ?: return@mapIndexedNotNull null, index) }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun executeSearch(query: String, type: String, limit: Int, context: ProviderContext): List<JsonValue> {
        val url = "https://api.mixcloud.com/search/?q=${TextTools.encode(query)}&type=$type&limit=$limit"
        return executeList(url, context)
    }

    private fun executeList(url: String, context: ProviderContext): List<JsonValue> {
        val response = context.http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 7_000))
        if (response.statusCode !in 200..299) throw IllegalStateException("Mixcloud returned HTTP ${response.statusCode}")
        return parseJsonObject(response).array("data")
    }

    private fun parseCloudcast(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val title = item.string("name") ?: return null
        val key = item.string("key")
        val url = item.string("url") ?: key?.let { "https://www.mixcloud.com$it" } ?: return null
        val user = item.obj("user")
        val tags = item.array("tags").mapNotNull { value -> value.asObject()?.string("name") ?: value.asString() }.toSet()
        val pictures = item.obj("pictures")
        val declaredMusic = tags.any { GenreCatalog.isMusicGenre(it) }
        return SourceHit(
            provider = id,
            providerItemId = key ?: url,
            providerRank = rank,
            providerPopularity = item.double("play_count") ?: item.double("listen_count") ?: item.double("favorite_count"),
            title = title,
            publisher = user?.string("name") ?: user?.string("username"),
            description = TextTools.stripHtml(item.string("description")),
            artworkUrl = artworkFrom(pictures?.string("extra_large"), pictures?.string("large"), pictures?.string("medium")),
            categories = tags,
            lastPublishedEpochMillis = TextTools.parseDate(item.string("created_time")),
            declaredMusic = declaredMusic,
            declaredMusicReason = if (declaredMusic) "Mixcloud music genre tag" else null,
            stableIds = mapOf("mixcloudKey" to (key ?: url)),
            targets = listOf(IntegrationTarget(TargetKind.MIXCLOUD_SHOW, url, stableId = key, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, title = title, metadata = mapOf("provider" to "mixcloud")))
        )
    }

    private fun parseUser(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val username = item.string("username") ?: item.string("key")?.trim('/') ?: return null
        val title = item.string("name") ?: username
        val url = item.string("url") ?: "https://www.mixcloud.com/$username/"
        val pictures = item.obj("pictures")
        return SourceHit(
            provider = id,
            providerItemId = username,
            providerRank = rank,
            providerPopularity = item.double("cloudcast_count") ?: item.double("follower_count"),
            title = title,
            publisher = username,
            description = TextTools.stripHtml(item.string("biog")),
            websiteUrl = item.string("website"),
            artworkUrl = artworkFrom(pictures?.string("extra_large"), pictures?.string("large"), pictures?.string("medium")),
            stableIds = mapOf("mixcloudUser" to username),
            targets = listOf(IntegrationTarget(TargetKind.MIXCLOUD_PROFILE, url, stableId = username, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, title = title, metadata = mapOf("provider" to "mixcloud")))
        )
    }
}
