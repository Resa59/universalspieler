package de.rdoe.weeklydjshows.discovery

import de.rdoe.weeklydjshows.discovery.feed.DefaultFeedVerifier
import de.rdoe.weeklydjshows.discovery.merge.ResultMerger
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.*
import de.rdoe.weeklydjshows.discovery.provider.*
import de.rdoe.weeklydjshows.discovery.resolver.DefaultUrlResolver

object DiscoveryModule {
    /**
     * Creates the complete default module. Optional providers report CREDENTIALS_MISSING until the host app supplies
     * their secrets through [SecretProvider]. No secret is stored by this library.
     */
    fun create(
        secrets: SecretProvider = EmptySecretProvider,
        httpClient: DiscoveryHttpClient? = null,
        config: DiscoveryEngineConfig = DiscoveryEngineConfig(),
        extraSearchProviders: List<SearchProvider> = emptyList(),
        extraBrowseProviders: List<BrowseProvider> = emptyList()
    ): DiscoveryEngine {
        val http = httpClient ?: UrlConnectionHttpClient(config.userAgent)
        val context = ProviderContext(http, secrets, config.userAgent)
        val feedVerifier = DefaultFeedVerifier(http, config.userAgent)
        val resolver = DefaultUrlResolver(http, feedVerifier, secrets, config.userAgent)
        val podcastIndex = PodcastIndexProvider()
        val gPodder = GPodderProvider()
        val mixcloud = MixcloudProvider()
        val defaultSearchProviders: List<SearchProvider> = listOf(
            podcastIndex,
            ApplePodcastProvider(),
            FeedlyLegacyProvider(),
            gPodder,
            mixcloud,
            YouTubeProvider(),
            SpotifyProvider(),
            SoundCloudProvider()
        )
        // The Android host can provide a keyless platform adapter (for example NewPipe) for the
        // same provider id. In that case it replaces the credential-only default instead of
        // running two providers with one id and producing ambiguous status/future entries.
        val searchOverrides = extraSearchProviders.mapTo(mutableSetOf()) { it.id }
        val browseOverrides = extraBrowseProviders.mapTo(mutableSetOf()) { it.id }
        val searchProviders = defaultSearchProviders.filterNot { it.id in searchOverrides } + extraSearchProviders
        val browseProviders = listOf<BrowseProvider>(podcastIndex, gPodder, mixcloud)
            .filterNot { it.id in browseOverrides } + extraBrowseProviders
        return DiscoveryEngine(searchProviders, browseProviders, context, feedVerifier, resolver, ResultMerger(), config)
    }
}
