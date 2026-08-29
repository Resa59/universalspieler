package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

/** Spotify catalogue discovery. An explicit API bearer is used when supplied; otherwise the
 * public WebPlayer metadata path keeps playlist discovery keyless. Spotify results never pretend
 * to be RSS feeds. */
class SpotifyProvider : SearchProvider {
    override val id: ProviderId = ProviderId.SPOTIFY
    @Volatile private var publicCatalog: SpotifyPublicCatalog? = null
    @Volatile private var catalogHttp: Any? = null

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        val token = context.secrets.get(SecretName.SPOTIFY_BEARER_TOKEN)
        if (token.isNullOrBlank()) {
            return searchPublic(request, context, started)
        }
        return try {
            val market = request.countries.firstOrNull()?.uppercase() ?: "DE"
            val limit = request.maxResultsPerProvider.coerceAtMost(10)
            val url = "https://api.spotify.com/v1/search?q=${TextTools.encode(request.query)}&type=show%2Cplaylist%2Cartist&market=$market&limit=$limit"
            val response = context.http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 7_000))
            if (response.statusCode !in 200..299) {
                return ProviderResult(id, emptyList(), providerStatusFromHttp(id, response.statusCode, context.nowEpochMillis() - started, response.text().take(250), response.header("Retry-After")?.toLongOrNull()))
            }
            val root = parseJsonObject(response)
            val hits = mutableListOf<SourceHit>()
            hits += parseContainer(root.obj("shows"), "show")
            hits += parseContainer(root.obj("playlists"), "playlist")
                .filterNot { TextTools.looksLikeEpisodeTitleForQuery(request.query, it.title) }
            hits += parseContainer(root.obj("artists"), "artist")
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun searchPublic(request: SearchRequest, context: ProviderContext, started: Long): ProviderResult = try {
        val catalog = synchronized(this) {
            if (publicCatalog == null || catalogHttp !== context.http) {
                publicCatalog = SpotifyPublicCatalog(context.http, context.userAgent)
                catalogHttp = context.http
            }
            publicCatalog!!
        }
        val hits = catalog.searchPlaylists(request.query, request.maxResultsPerProvider.coerceAtMost(20))
            .filterNot { TextTools.looksLikeEpisodeTitleForQuery(request.query, it.title) }
            .mapIndexed { index, item ->
                SourceHit(
                    provider = id,
                    providerItemId = item.id,
                    providerRank = index,
                    title = item.title,
                    publisher = item.owner,
                    description = item.description,
                    websiteUrl = item.url,
                    artworkUrl = item.artworkUrl,
                    categories = setOf("Spotify", "Music"),
                    declaredMusic = true,
                    declaredMusicReason = "Spotify playlist",
                    stableIds = mapOf("spotifyplaylist" to item.id),
                    targets = listOf(
                        IntegrationTarget(
                            kind = TargetKind.SPOTIFY_PLAYLIST,
                            url = item.url,
                            stableId = item.id,
                            requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
                            title = item.title,
                            metadata = mapOf("spotifyType" to "playlist", "metadataAdapter" to "public-webplayer"),
                        ),
                    ),
                    rawMetadata = mapOf("metadataAdapter" to "public-webplayer"),
                )
            }
        ProviderResult(
            id,
            hits,
            ProviderStatus(
                id,
                if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS,
                resultCount = hits.size,
                message = "Öffentliche Spotify-Playlist-Metadaten",
                durationMillis = context.nowEpochMillis() - started,
            ),
        )
    } catch (error: Throwable) {
        ProviderResult(
            id,
            emptyList(),
            ProviderStatus(
                id,
                if (Thread.currentThread().isInterrupted) ProviderState.TIMEOUT else ProviderState.UNAVAILABLE,
                message = error.message?.take(180) ?: error.javaClass.simpleName,
                durationMillis = context.nowEpochMillis() - started,
            ),
        )
    }

    private fun parseContainer(container: Map<String, JsonValue>?, type: String): List<SourceHit> {
        if (container == null) return emptyList()
        return container.array("items").mapIndexedNotNull { index, value ->
            val item = value.asObject() ?: return@mapIndexedNotNull null
            val spotifyId = item.string("id") ?: return@mapIndexedNotNull null
            val title = item.string("name") ?: return@mapIndexedNotNull null
            val externalUrl = item.obj("external_urls")?.string("spotify") ?: "https://open.spotify.com/$type/$spotifyId"
            val images = item.array("images")
            val artwork = images.firstNotNullOfOrNull { it.asObject()?.string("url") }
            val kind = when (type) {
                "show" -> TargetKind.SPOTIFY_SHOW
                "playlist" -> TargetKind.SPOTIFY_PLAYLIST
                else -> TargetKind.SPOTIFY_ARTIST
            }
            val publisher = when (type) {
                "show" -> item.string("publisher")
                "playlist" -> item.obj("owner")?.string("display_name")
                else -> null
            }
            SourceHit(
                provider = id,
                providerItemId = spotifyId,
                providerRank = index,
                providerPopularity = item.double("popularity") ?: item.obj("followers")?.double("total"),
                title = title,
                publisher = publisher,
                description = TextTools.stripHtml(item.string("description") ?: item.string("html_description")),
                artworkUrl = artwork,
                categories = item.stringList("genres").toSet(),
                stableIds = mapOf("spotify$type" to spotifyId),
                targets = listOf(IntegrationTarget(kind, externalUrl, stableId = spotifyId, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, title = title, metadata = mapOf("spotifyType" to type)))
            )
        }
    }
}
