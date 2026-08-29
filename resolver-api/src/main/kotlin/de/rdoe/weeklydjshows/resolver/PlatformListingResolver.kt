package de.rdoe.weeklydjshows.resolver

import de.rdoe.weeklydjshows.model.PlatformListing
import de.rdoe.weeklydjshows.model.ShowSourceType

interface PlatformListingResolver {
    fun supports(sourceType: ShowSourceType, url: String): Boolean
    suspend fun list(sourceType: ShowSourceType, url: String, maxItems: Int = 250): Result<PlatformListing>

    /** Returns a small native feed when the platform exposes one; null keeps the listing adapter. */
    suspend fun discoverFeedUrl(sourceType: ShowSourceType, url: String): String? = null
}
