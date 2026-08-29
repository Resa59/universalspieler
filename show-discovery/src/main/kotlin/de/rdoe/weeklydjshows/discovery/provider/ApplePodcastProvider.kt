package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

class ApplePodcastProvider : SearchProvider {
    override val id: ProviderId = ProviderId.APPLE_PODCASTS

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        return try {
            val hits = mutableListOf<SourceHit>()
            val countries = request.countries.map { it.uppercase() }.distinct().ifEmpty { listOf("DE") }
            for (country in countries) {
                val url = buildString {
                    append("https://itunes.apple.com/search?term=")
                    append(TextTools.encode(request.query))
                    append("&country=").append(TextTools.encode(country))
                    append("&media=podcast&entity=podcast&limit=")
                    append(request.maxResultsPerProvider.coerceAtMost(200))
                }
                val response = context.http.execute(
                    HttpRequest(
                        url = url,
                        headers = mapOf("Accept" to "application/json", "User-Agent" to context.userAgent),
                        connectTimeoutMillis = 4_000,
                        readTimeoutMillis = 6_000
                    )
                )
                if (response.statusCode !in 200..299) {
                    val status = providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, response.text().take(300))
                    if (hits.isEmpty()) return ProviderResult(id, emptyList(), status)
                    continue
                }
                val root = parseJsonObject(response)
                root.array("results").forEachIndexed { index, value ->
                    val item = value.asObject() ?: return@forEachIndexed
                    val title = item.string("collectionName") ?: item.string("trackName") ?: return@forEachIndexed
                    val feedUrl = item.string("feedUrl")
                    val appleUrl = item.string("collectionViewUrl") ?: item.string("trackViewUrl")
                    val appleId = item.long("collectionId")?.toString() ?: item.long("trackId")?.toString()
                    val categories = (item.stringList("genres") + listOfNotNull(item.string("primaryGenreName"))).toSet()
                    val declaredMusic = categories.any { TextTools.normalizeText(it) == "music" || TextTools.normalizeText(it) == "musik" }
                    val targets = buildList {
                        if (feedUrl != null) add(
                            IntegrationTarget(
                                kind = TargetKind.RSS_AUDIO,
                                url = feedUrl,
                                feedUrl = feedUrl,
                                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
                                title = title
                            )
                        )
                        if (appleUrl != null) add(
                            IntegrationTarget(
                                kind = TargetKind.APPLE_PODCAST,
                                url = appleUrl,
                                stableId = appleId,
                                // Apple returns the publisher's underlying RSS URL in the same
                                // result. Keep the Apple page as identity/source while making this
                                // target a normal subscribable feed instead of an external dead end.
                                feedUrl = feedUrl,
                                requirement = if (feedUrl != null) {
                                    IntegrationRequirement.DIRECT_RSS_AUDIO
                                } else {
                                    IntegrationRequirement.EXTERNAL_ONLY
                                },
                                title = title
                            )
                        )
                    }
                    hits += SourceHit(
                        provider = id,
                        providerItemId = appleId,
                        providerRank = index,
                        title = title,
                        publisher = item.string("artistName"),
                        description = TextTools.stripHtml(item.string("description") ?: item.string("shortDescription")),
                        feedUrl = feedUrl,
                        websiteUrl = null,
                        artworkUrl = artworkFrom(item.string("artworkUrl600"), item.string("artworkUrl100"), item.string("artworkUrl60")),
                        categories = categories,
                        language = item.string("language"),
                        lastPublishedEpochMillis = TextTools.parseDate(item.string("releaseDate")),
                        declaredMusic = declaredMusic,
                        declaredMusicReason = if (declaredMusic) "Apple genre Music" else null,
                        stableIds = buildMap {
                            if (appleId != null) put("appleId", appleId)
                        },
                        targets = targets,
                        rawMetadata = mapOf("country" to country)
                    )
                }
            }
            val deduped = hits.distinctBy { it.stableIds["appleId"] ?: TextTools.normalizeUrl(it.feedUrl) ?: "${it.title}|${it.publisher}" }
            ProviderResult(
                id,
                deduped,
                ProviderStatus(
                    provider = id,
                    state = if (deduped.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS,
                    resultCount = deduped.size,
                    durationMillis = context.nowEpochMillis() - started
                )
            )
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }
}
