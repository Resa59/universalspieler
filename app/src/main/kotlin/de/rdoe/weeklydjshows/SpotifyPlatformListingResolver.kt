package de.rdoe.weeklydjshows

import de.rdoe.weeklydjshows.discovery.provider.SpotifyPublicCatalog
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.PlatformEpisode
import de.rdoe.weeklydjshows.model.PlatformListing
import de.rdoe.weeklydjshows.model.ShowSourceType
import de.rdoe.weeklydjshows.resolver.PlatformListingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preview-only metadata adapter for public Spotify playlists. Subscriptions stay external links. */
class SpotifyPlatformListingResolver(
    private val catalog: SpotifyPublicCatalog,
) : PlatformListingResolver {
    override fun supports(sourceType: ShowSourceType, url: String): Boolean =
        sourceType == ShowSourceType.SPOTIFY_PLAYLIST &&
            ("open.spotify.com/playlist/" in url || url.startsWith("spotify:playlist:"))

    override suspend fun list(
        sourceType: ShowSourceType,
        url: String,
        maxItems: Int,
    ): Result<PlatformListing> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = catalog.playlist(url, maxItems.coerceAtMost(100))
            PlatformListing(
                title = snapshot.title,
                artworkUrl = snapshot.artworkUrl,
                publisher = snapshot.owner,
                description = snapshot.description.orEmpty(),
                episodes = snapshot.items.map { item ->
                    PlatformEpisode(
                        stableId = item.id,
                        url = item.url,
                        title = item.title,
                        description = item.artists,
                        artworkUrl = item.artworkUrl,
                        durationMs = item.durationMs,
                        publishedAtEpochMs = item.addedAtEpochMs,
                        publishedText = item.addedAtText,
                        sourceType = EpisodeSourceType.SPOTIFY,
                    )
                },
            )
        }
    }
}
