package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.network.DiscoveryHttpClient
import de.rdoe.weeklydjshows.discovery.network.HttpRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Small, keyless reader for public Spotify web metadata.
 *
 * This deliberately handles metadata only. It never obtains protected audio and it keeps Spotify's
 * anonymous web bearer in memory. Persisted-query hashes are centralized here so a Spotify web
 * schema change has one maintenance point.
 */
class SpotifyPublicCatalog(
    private val http: DiscoveryHttpClient,
    private val userAgent: String = "WeeklyDJShows/1.3.1",
) {
    data class PlaylistSummary(
        val id: String,
        val title: String,
        val owner: String?,
        val description: String?,
        val url: String,
        val artworkUrl: String?,
    )

    data class PlaylistItem(
        val id: String,
        val title: String,
        val artists: String,
        val url: String,
        val artworkUrl: String?,
        val durationMs: Long?,
        val addedAtEpochMs: Long?,
        val addedAtText: String,
    )

    data class PlaylistSnapshot(
        val title: String?,
        val artworkUrl: String?,
        val owner: String?,
        val description: String?,
        val items: List<PlaylistItem>,
    )

    @Volatile private var session: Session? = null

    fun searchPlaylists(query: String, limit: Int = 10): List<PlaylistSummary> {
        require(query.isNotBlank())
        val safeLimit = limit.coerceIn(1, 20)
        val variables = buildString {
            append("{\"searchTerm\":")
            append(jsonString(query))
            append(",\"offset\":0,\"limit\":")
            append(safeLimit)
            append(",\"numberOfTopResults\":5,\"includeAudiobooks\":true")
            append(",\"includePreReleases\":true,\"includeAlbumPreReleases\":false")
            append(",\"includeAuthors\":false,\"includeEpisodeContentRatingsV2\":false}")
        }
        val root = runPersistedQuery("searchDesktop", variables, SEARCH_HASHES)
        val search = root.obj("data")?.obj("searchV2")
            ?: throw SpotifyPublicException("Spotify-Suche lieferte keine searchV2-Daten")
        return search.obj("playlists")?.array("items").orEmpty()
            .mapNotNull(::parsePlaylistSummary)
            .distinctBy { it.id }
            .take(safeLimit)
    }

    fun playlist(value: String, limit: Int = 100): PlaylistSnapshot {
        val id = playlistId(value) ?: throw SpotifyPublicException("Ungültige Spotify-Playlist-URL")
        val safeLimit = limit.coerceIn(1, 100)
        val variables = "{\"uri\":\"spotify:playlist:$id\",\"offset\":0,\"limit\":$safeLimit," +
            "\"enableWatchFeedEntrypoint\":false}"
        val root = runPersistedQuery("fetchPlaylist", variables, listOf(FETCH_PLAYLIST_HASH))
        val playlist = root.obj("data")?.obj("playlistV2")
            ?: throw SpotifyPublicException("Spotify-Playlist lieferte keine playlistV2-Daten")
        val items = playlist.obj("content")?.array("items").orEmpty()
            .mapNotNull(::parsePlaylistItem)
            .distinctBy { it.id }
            .take(safeLimit)
        return PlaylistSnapshot(
            title = playlist.string("name"),
            artworkUrl = bestImage(JsonValue.Obj(playlist)),
            owner = playlist.obj("ownerV2")?.obj("data")?.string("name")
                ?: playlist.obj("owner")?.string("name")
                ?: playlist.obj("owner")?.string("display_name"),
            description = TextTools.stripHtml(textValue(playlist["description"])),
            items = items,
        )
    }

    private fun runPersistedQuery(
        operationName: String,
        variables: String,
        hashes: List<String>,
    ): Map<String, JsonValue> {
        var token = bearerToken(forceRefresh = false)
        var lastMessage = "Spotify-Webabfrage fehlgeschlagen"
        var retriedAuth = false
        for (hash in hashes.distinct()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Spotify-Abfrage abgebrochen")
            var response = executeQuery(operationName, variables, hash, token)
            if (response.statusCode == 401 && !retriedAuth) {
                retriedAuth = true
                token = bearerToken(forceRefresh = true)
                response = executeQuery(operationName, variables, hash, token)
            }
            if (response.statusCode !in 200..299) {
                lastMessage = "Spotify-Webabfrage: HTTP ${response.statusCode}"
                continue
            }
            val root = runCatching { Json.parse(response.text()).asObject() }.getOrNull()
            if (root != null && root["data"]?.asObject() != null) return root
            lastMessage = "Spotify-Webabfrage: Persisted Query nicht mehr gültig"
        }
        throw SpotifyPublicException(lastMessage)
    }

    private fun executeQuery(
        operationName: String,
        variables: String,
        hash: String,
        token: String,
    ) = http.execute(
        HttpRequest(
            url = "$PATHFINDER?operationName=${encode(operationName)}" +
                "&variables=${encode(variables)}&extensions=${encode(persistedExtension(hash))}",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $token",
                "app-platform" to "WebPlayer",
                "Origin" to "https://open.spotify.com",
                "Referer" to "https://open.spotify.com/",
                "User-Agent" to browserUserAgent(),
            ),
            connectTimeoutMillis = 2_500,
            readTimeoutMillis = 3_500,
            maxBytes = 2 * 1024 * 1024,
        ),
    )

    @Synchronized
    private fun bearerToken(forceRefresh: Boolean): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            session?.takeIf { it.expiresAtEpochMs > now + 60_000 }?.let { return it.token }
        }
        val response = http.execute(
            HttpRequest(
                url = TOKEN_BOOTSTRAP,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "User-Agent" to browserUserAgent(),
                ),
                connectTimeoutMillis = 2_500,
                readTimeoutMillis = 3_500,
                maxBytes = 3 * 1024 * 1024,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw SpotifyPublicException("Spotify-Token: HTTP ${response.statusCode}")
        }
        val json = NEXT_DATA.find(response.text())?.groupValues?.getOrNull(1)
            ?: throw SpotifyPublicException("Spotify-Embed enthält keine Sitzungsdaten")
        val root = runCatching { Json.parse(json).asObject() }.getOrNull()
            ?: throw SpotifyPublicException("Spotify-Sitzungsdaten sind ungültig")
        val sessionData = root.obj("props")
            ?.obj("pageProps")
            ?.obj("state")
            ?.obj("settings")
            ?.obj("session")
            ?: throw SpotifyPublicException("Spotify-Sitzung fehlt")
        val token = sessionData.string("accessToken")
            ?: throw SpotifyPublicException("Spotify-Bearer fehlt")
        val expiry = sessionData.long("accessTokenExpirationTimestampMs")
            ?.takeIf { it > now + 60_000 }
            ?: now + 30 * 60_000L
        session = Session(token, expiry)
        return token
    }

    private fun parsePlaylistSummary(value: JsonValue): PlaylistSummary? {
        val wrapper = value.asObject() ?: return null
        val data = wrapper.obj("data") ?: wrapper.obj("item")?.obj("data") ?: return null
        val uri = data.string("uri") ?: return null
        val id = spotifyId(uri, "playlist") ?: return null
        val title = data.string("name") ?: return null
        val owner = data.obj("ownerV2")?.obj("data")?.string("name")
            ?: data.obj("owner")?.string("name")
            ?: data.obj("owner")?.string("display_name")
        val description = textValue(data["description"])
        return PlaylistSummary(
            id = id,
            title = title,
            owner = owner,
            description = TextTools.stripHtml(description),
            url = "https://open.spotify.com/playlist/$id",
            artworkUrl = bestImage(JsonValue.Obj(data)),
        )
    }

    private fun parsePlaylistItem(value: JsonValue): PlaylistItem? {
        val wrapper = value.asObject() ?: return null
        val itemV2 = wrapper.obj("itemV2") ?: wrapper.obj("item") ?: return null
        val data = itemV2.obj("data") ?: return null
        val uri = data.string("uri") ?: return null
        val id = spotifyId(uri, "track") ?: return null
        val title = data.string("name") ?: return null
        val artists = data.obj("artists")?.array("items").orEmpty().mapNotNull { artistValue ->
            val artist = artistValue.asObject() ?: return@mapNotNull null
            artist.obj("profile")?.string("name") ?: artist.string("name")
        }.distinct().joinToString(", ")
        val addedAtText = wrapper.obj("addedAt")?.string("isoString")
            ?: wrapper.string("addedAt")
            ?: ""
        val addedAt = addedAtText.takeIf { it.isNotBlank() }?.let(::parseInstant)
        return PlaylistItem(
            id = id,
            title = title,
            artists = artists,
            url = "https://open.spotify.com/track/$id",
            artworkUrl = bestImage(JsonValue.Obj(data)),
            durationMs = data.obj("duration")?.long("totalMilliseconds"),
            addedAtEpochMs = addedAt,
            addedAtText = addedAtText,
        )
    }

    private fun bestImage(value: JsonValue): String? {
        data class Candidate(val url: String, val score: Long)
        fun collect(node: JsonValue, depth: Int): List<Candidate> {
            if (depth > 9) return emptyList()
            return when (node) {
                is JsonValue.Obj -> {
                    val url = node.values.string("url")
                    val width = node.values.long("width") ?: 0L
                    val height = node.values.long("height") ?: 0L
                    val self = url?.takeIf {
                        it.startsWith("http") && (width > 0 || height > 0 || "scdn" in it || "spotifycdn" in it)
                    }?.let { listOf(Candidate(it, (width * height).coerceAtLeast(1L))) }.orEmpty()
                    self + node.values.values.flatMap { collect(it, depth + 1) }
                }
                is JsonValue.Arr -> node.values.flatMap { collect(it, depth + 1) }
                else -> emptyList()
            }
        }
        return collect(value, 0).maxByOrNull { it.score }?.url
    }

    private fun textValue(value: JsonValue?): String? = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Obj -> value.values.string("text")
            ?: value.values.string("value")
            ?: value.values.values.firstNotNullOfOrNull(::textValue)
        is JsonValue.Arr -> value.values.firstNotNullOfOrNull(::textValue)
        else -> null
    }

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun playlistId(value: String): String? = spotifyId(value, "playlist")
        ?: Regex("(?:open\\.spotify\\.com/)?playlist/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)

    private fun spotifyId(value: String, type: String): String? {
        Regex("spotify:$type:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("/$type/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
    }

    private fun persistedExtension(hash: String) =
        "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$hash\"}}"

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun browserUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0 Mobile Safari/537.36 $userAgent"

    private data class Session(val token: String, val expiresAtEpochMs: Long)

    companion object {
        private const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val TOKEN_BOOTSTRAP = "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC"
        private val NEXT_DATA = Regex(
            "<script[^>]+id=[\\\"']__NEXT_DATA__[\\\"'][^>]*>(.*?)</script>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Current public WebPlayer hashes plus two recent fallbacks. A hash mismatch returns
        // quickly and the next value is tried; all schema-sensitive constants live here.
        private val SEARCH_HASHES = listOf(
            "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0",
            "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
            "21b3fe49546912ba782db5c47e9ef5a7dbd20329520ba0c7d0fcfadee671d24e",
        )
        private const val FETCH_PLAYLIST_HASH =
            "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
    }
}

class SpotifyPublicException(message: String) : IllegalStateException(message)
