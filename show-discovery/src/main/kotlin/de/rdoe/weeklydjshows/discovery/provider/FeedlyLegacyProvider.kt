package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

/** Best-effort compatibility provider for the search endpoint used by the old AppYet app. */
class FeedlyLegacyProvider : SearchProvider {
    override val id: ProviderId = ProviderId.FEEDLY

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val token = context.secrets.get(SecretName.FEEDLY_TOKEN)
            val count = request.maxResultsPerProvider.coerceAtMost(100)
            val headers = mutableMapOf("Accept" to "application/json", "User-Agent" to context.userAgent)
            if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
            var response = context.http.execute(HttpRequest("https://cloud.feedly.com/v3/search/feeds?query=${TextTools.encode(request.query)}&count=$count", headers = headers, connectTimeoutMillis = 3_500, readTimeoutMillis = 5_000))
            if (response.statusCode == 400 || response.statusCode == 404) {
                response = context.http.execute(HttpRequest("https://cloud.feedly.com/v3/search/feeds?q=${TextTools.encode(request.query)}&count=$count", headers = headers, connectTimeoutMillis = 3_500, readTimeoutMillis = 5_000))
            }
            if (response.statusCode !in 200..299) {
                return ProviderResult(id, emptyList(), providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, "Feedly legacy endpoint: ${response.text().take(200)}", response.header("Retry-After")?.toLongOrNull()))
            }
            val root = parseJsonObject(response)
            val values = root.array("results").ifEmpty { root.array("feeds") }
            val hits = values.take(request.maxResultsPerProvider).mapIndexedNotNull { index, value ->
                val item = value.asObject() ?: return@mapIndexedNotNull null
                val title = item.string("title") ?: return@mapIndexedNotNull null
                val feedId = item.string("feedId") ?: item.string("id")
                val feedUrl = feedId?.removePrefix("feed/")?.takeIf { it.startsWith("http") } ?: item.string("feedUrl") ?: item.string("url")
                if (feedUrl == null) return@mapIndexedNotNull null
                val categories = categoriesFrom(item["topics"]) + categoriesFrom(item["categories"])
                val declaredMusic = categories.any { TextTools.normalizeText(it) == "music" || TextTools.normalizeText(it) == "musik" }
                SourceHit(
                    provider = id,
                    providerItemId = feedId ?: feedUrl,
                    providerRank = index,
                    providerPopularity = item.double("subscribers"),
                    title = title,
                    publisher = item.string("publisher"),
                    description = TextTools.stripHtml(item.string("description")),
                    feedUrl = feedUrl,
                    websiteUrl = item.string("website"),
                    artworkUrl = artworkFrom(item.string("visualUrl"), item.string("coverUrl"), item.string("iconUrl")),
                    categories = categories,
                    declaredMusic = declaredMusic,
                    declaredMusicReason = if (declaredMusic) "Feedly category Music" else null,
                    targets = listOf(IntegrationTarget(TargetKind.RSS_AUDIO, feedUrl, feedUrl = feedUrl, requirement = IntegrationRequirement.DIRECT_RSS_AUDIO, title = title)),
                    rawMetadata = buildMap {
                        item.double("velocity")?.let { put("velocity", it.toString()) }
                    }
                )
            }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, message = "Legacy best-effort endpoint", durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error).copy(message = "Feedly legacy endpoint: ${error.message}"))
        }
    }
}
