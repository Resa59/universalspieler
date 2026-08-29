package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpRequest

class SoundCloudProvider : SearchProvider {
    override val id: ProviderId = ProviderId.SOUNDCLOUD

    override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
        val started = context.nowEpochMillis()
        val token = context.secrets.get(SecretName.SOUNDCLOUD_ACCESS_TOKEN)
        if (token.isNullOrBlank()) {
            return ProviderResult(id, emptyList(), ProviderStatus(id, ProviderState.CREDENTIALS_MISSING, message = "SoundCloud OAuth access token missing"))
        }
        return try {
            val limit = request.maxResultsPerProvider.coerceAtMost(50)
            val hits = mutableListOf<SourceHit>()
            hits += execute("users", request.query, limit.coerceAtMost(20), token, context).mapIndexedNotNull { index, value -> parseUser(value.asObject() ?: return@mapIndexedNotNull null, index) }
            hits += execute("playlists", request.query, limit, token, context).mapIndexedNotNull { index, value -> parsePlaylist(value.asObject() ?: return@mapIndexedNotNull null, index) }
            ProviderResult(id, hits, ProviderStatus(id, if (hits.isEmpty()) ProviderState.NO_RESULTS else ProviderState.SUCCESS, hits.size, durationMillis = context.nowEpochMillis() - started))
        } catch (error: Throwable) {
            ProviderResult(id, emptyList(), statusForException(id, started, error))
        }
    }

    private fun execute(resource: String, query: String, limit: Int, token: String, context: ProviderContext): List<JsonValue> {
        val url = "https://api.soundcloud.com/$resource?q=${TextTools.encode(query)}&limit=$limit&linked_partitioning=true"
        val response = context.http.execute(HttpRequest(url, headers = mapOf("Accept" to "application/json", "Authorization" to "OAuth $token", "User-Agent" to context.userAgent), connectTimeoutMillis = 4_000, readTimeoutMillis = 7_000))
        if (response.statusCode !in 200..299) throw IllegalStateException("SoundCloud returned HTTP ${response.statusCode}: ${response.text().take(200)}")
        val root = Json.parse(response.text())
        return root.asArray() ?: root.asObject()?.array("collection").orEmpty()
    }

    private fun parseUser(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val idValue = item.long("id")?.toString() ?: return null
        val username = item.string("username") ?: return null
        val url = item.string("permalink_url") ?: "https://soundcloud.com/${item.string("permalink") ?: idValue}"
        return SourceHit(
            provider = id,
            providerItemId = idValue,
            providerRank = rank,
            providerPopularity = item.double("followers_count"),
            title = item.string("full_name") ?: username,
            publisher = username,
            description = TextTools.stripHtml(item.string("description")),
            websiteUrl = item.string("website"),
            artworkUrl = item.string("avatar_url"),
            stableIds = mapOf("soundcloudUser" to idValue),
            targets = listOf(IntegrationTarget(TargetKind.SOUNDCLOUD_PROFILE, url, stableId = idValue, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, title = username))
        )
    }

    private fun parsePlaylist(item: Map<String, JsonValue>, rank: Int): SourceHit? {
        val idValue = item.long("id")?.toString() ?: return null
        val title = item.string("title") ?: return null
        val url = item.string("permalink_url") ?: return null
        val user = item.obj("user")
        val genres = setOfNotNull(item.string("genre")).filter { it.isNotBlank() }.toSet()
        val declaredMusic = genres.any { GenreCatalog.isMusicGenre(it) }
        return SourceHit(
            provider = id,
            providerItemId = idValue,
            providerRank = rank,
            providerPopularity = item.double("likes_count") ?: item.double("reposts_count"),
            title = title,
            publisher = user?.string("username"),
            description = TextTools.stripHtml(item.string("description")),
            artworkUrl = item.string("artwork_url"),
            categories = genres,
            lastPublishedEpochMillis = TextTools.parseDate(item.string("created_at")),
            declaredMusic = declaredMusic,
            declaredMusicReason = if (declaredMusic) "SoundCloud genre" else null,
            stableIds = mapOf("soundcloudPlaylist" to idValue),
            targets = listOf(IntegrationTarget(TargetKind.SOUNDCLOUD_PLAYLIST, url, stableId = idValue, requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED, title = title))
        )
    }
}
