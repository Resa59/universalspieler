package de.rdoe.weeklydjshows

import de.rdoe.weeklydjshows.database.ShowEntity
import de.rdoe.weeklydjshows.model.ShowSourceType

/** Only sources that can provide a playable newest episode belong in the autostart picker. */
internal fun ShowEntity.isAutostartEpisodeSource(): Boolean =
    subscribed &&
        id != BundledShowLayoutV128.TRACKLISTS_ID &&
        sourceType != ShowSourceType.MIXCLOUD &&
        sourceType != ShowSourceType.SPOTIFY_PLAYLIST &&
        sourceType != ShowSourceType.PLATFORM_LINK
