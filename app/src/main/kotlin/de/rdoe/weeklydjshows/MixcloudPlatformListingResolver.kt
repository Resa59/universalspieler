package de.rdoe.weeklydjshows

import de.rdoe.weeklydjshows.discovery.internal.Json
import de.rdoe.weeklydjshows.discovery.internal.JsonValue
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.internal.array
import de.rdoe.weeklydjshows.discovery.internal.asObject
import de.rdoe.weeklydjshows.discovery.internal.long
import de.rdoe.weeklydjshows.discovery.internal.obj
import de.rdoe.weeklydjshows.discovery.internal.string
import de.rdoe.weeklydjshows.discovery.network.DiscoveryHttpClient
import de.rdoe.weeklydjshows.discovery.network.HttpRequest
import de.rdoe.weeklydjshows.discovery.network.UrlConnectionHttpClient
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.PlatformEpisode
import de.rdoe.weeklydjshows.model.PlatformListing
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.resolver.PlatformListingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Lists a public Mixcloud profile through Mixcloud's documented, unauthenticated REST API. */
class MixcloudPlatformListingResolver(
    private val http: DiscoveryHttpClient = UrlConnectionHttpClient(USER_AGENT),
) : PlatformListingResolver {
    override fun supports(sourceType: ShowSourceType, url: String): Boolean =
        sourceType == ShowSourceType.MIXCLOUD && profileName(url) != null

    override suspend fun list(
        sourceType: ShowSourceType,
        url: String,
        maxItems: Int,
    ): Result<PlatformListing> = withContext(Dispatchers.IO) {
        runCatching {
            require(supports(sourceType, url)) { "Ungültiges Mixcloud-Profil" }
            val username = profileName(url) ?: error("Mixcloud-Profilname fehlt")
            val encoded = encodePathSegment(username)
            val profile = getObject("https://api.mixcloud.com/$encoded/?metadata=1")
            val episodes = mutableListOf<PlatformEpisode>()
            val visitedPages = mutableSetOf<String>()
            var nextUrl: String? = "https://api.mixcloud.com/$encoded/cloudcasts/?limit=${maxItems.coerceIn(1, PAGE_SIZE)}"

            while (nextUrl != null && episodes.size < maxItems.coerceAtLeast(1) && visitedPages.add(nextUrl)) {
                val page = getObject(nextUrl)
                page.array("data").forEach { value ->
                    if (episodes.size >= maxItems) return@forEach
                    parseCloudcast(value.asObject())?.let(episodes::add)
                }
                nextUrl = page.obj("paging")?.string("next")
            }

            PlatformListing(
                title = profile.string("name") ?: username,
                artworkUrl = bestPicture(profile.obj("pictures")),
                publisher = profile.string("username") ?: username,
                description = profile.string("biog").orEmpty(),
                episodes = episodes,
            )
        }
    }

    private fun parseCloudcast(item: Map<String, JsonValue>?): PlatformEpisode? {
        item ?: return null
        val key = item.string("key") ?: return null
        val url = item.string("url") ?: "https://www.mixcloud.com$key"
        val created = item.string("created_time")
        return PlatformEpisode(
            stableId = key,
            url = url,
            title = item.string("name") ?: return null,
            description = item.string("description").orEmpty(),
            artworkUrl = bestPicture(item.obj("pictures")),
            durationMs = item.long("audio_length")?.takeIf { it > 0L }?.times(1_000L),
            publishedAtEpochMs = TextTools.parseDate(created),
            publishedText = created.orEmpty(),
            sourceType = EpisodeSourceType.MIXCLOUD,
        )
    }

    private fun getObject(url: String): Map<String, JsonValue> {
        val response = http.execute(
            HttpRequest(
                url = url,
                headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 10_000,
            ),
        )
        if (response.statusCode !in 200..299) {
            error("Mixcloud antwortet mit HTTP ${response.statusCode}")
        }
        return Json.parse(response.text()).asObject() ?: error("Ungültige Mixcloud-Antwort")
    }

    private fun bestPicture(pictures: Map<String, JsonValue>?): String? = sequenceOf(
        pictures?.string("1024wx1024h"),
        pictures?.string("extra_large"),
        pictures?.string("large"),
        pictures?.string("medium"),
    ).firstOrNull { !it.isNullOrBlank() }

    private fun profileName(raw: String): String? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (host != "mixcloud.com" && host != "www.mixcloud.com") return@runCatching null
        val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        segments.singleOrNull()
    }.getOrNull()

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val PAGE_SIZE = 100
        const val USER_AGENT = "WeeklyDJShows/1.3.1 (Mixcloud catalog)"
    }
}
