package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

class GPodderProvider : SearchProvider, BrowseProvider {
    override val id: ProviderId = ProviderId.GPODDER
    override val supportedModes: Set<BrowseMode> = setOf(BrowseMode.POPULAR, BrowseMode.GENRE)

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val url = "https://gpodder.net/search.json?q=${TextTools.encode(request.query)}&scale_logo=256"
            val response = context.http.execute(
                HttpRequest(url, headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 6_000)
            )
            if (response.statusCode !in 200..299) {
                return ProviderResult(id, emptyList(), providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, response.text().take(200)))
            }
            val hits = parseJsonArray(response).take(request.maxResultsPerProvider).mapIndexedNotNull { index, value ->
                parseItem(value.asObject() ?: return@mapIndexedNotNull null, index)
            }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    override fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val count = request.limit.coerceAtMost(100)
            val url = when (request.mode) {
                BrowseMode.POPULAR -> "https://gpodder.net/toplist/$count.json"
                BrowseMode.GENRE -> "https://gpodder.net/api/2/tag/${TextTools.encode(request.genre.orEmpty())}/$count.json"
                else -> return ProviderResult(id, emptyList(), ProviderStatus(id, ProviderState.DISABLED, message = "Browse mode ${request.mode} is not supported by gPodder"))
            }
            val response = context.http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 6_000))
            if (response.statusCode !in 200..299) {
                return ProviderResult(id, emptyList(), providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, response.text().take(200)))
            }
            val hits = parseJsonArray(response).take(count).mapIndexedNotNull { index, value -> parseItem(value.asObject() ?: return@mapIndexedNotNull null, index) }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun parseItem(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val title = item.string("title") ?: return null
        val feedUrl = item.string("url") ?: return null
        val categories = categoriesFrom(item["tags"])
        val declaredMusic = categories.any { TextTools.normalizeText(it) == "music" || TextTools.normalizeText(it) == "musik" }
        return SourceHit(
            provider = id,
            providerItemId = TextTools.normalizeUrl(feedUrl),
            providerRank = rank,
            providerPopularity = item.double("subscribers"),
            title = title,
            publisher = item.string("author"),
            description = TextTools.stripHtml(item.string("description")),
            feedUrl = feedUrl,
            websiteUrl = item.string("website"),
            artworkUrl = artworkFrom(item.string("scaled_logo_url"), item.string("logo_url")),
            categories = categories,
            declaredMusic = declaredMusic,
            declaredMusicReason = if (declaredMusic) "gPodder tag Music" else null,
            targets = buildList {
                add(IntegrationTarget(TargetKind.RSS_AUDIO, feedUrl, feedUrl = feedUrl, requirement = IntegrationRequirement.DIRECT_RSS_AUDIO, title = title))
                item.string("website")?.let { add(IntegrationTarget(TargetKind.WEBSITE, it, requirement = IntegrationRequirement.RESOLUTION_REQUIRED, title = title)) }
            }
        )
    }
}
