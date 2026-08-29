# Public API reference

## Factory

### `DiscoveryModule.create(...)`

Creates the default engine with Apple, Podcast Index, Feedly, gPodder, Mixcloud, YouTube, Spotify and SoundCloud search providers. Optional providers return `CREDENTIALS_MISSING` when their key/token is absent.

Inject a custom `DiscoveryHttpClient` for tests, proxying or application-wide networking policy. Custom providers can be appended through `extraSearchProviders` and `extraBrowseProviders`.

## `DiscoveryEngine`

- `search(request, listener)` – asynchronous concrete search
- `searchBlocking(request, listener)` – blocking concrete search
- `browse(request, listener)` – asynchronous discovery/start-page query
- `browseBlocking(request, listener)` – blocking browse
- `resolveUrl(input)` – feed/platform resolver
- `close()` – releases executors

## `SearchRequest`

- `query` – required free text
- `countries` – Apple storefront and Spotify market priority
- `language` – provider language hint where supported
- `maxResultsPerProvider` – independent per-provider cap
- `musicMode` – all, DJ/music only, or declared music only
- `includePlatformResults` – false removes results that have no direct RSS target
- `verifyTopRssResults` – number of top RSS URLs downloaded and inspected
- `enabledProviders` – optional provider allowlist

## `BrowseRequest`

- `mode` – trending, popular, new, recently updated, genre or random
- `genre` – required for genre mode
- `language`, `country`, `limit`
- `musicMode`
- `includePlatformResults`
- `verifyTopRssResults`
- `enabledProviders`

## `DiscoveryResponse`

- `results` – merged and ranked results
- `providerStatuses` – terminal status per attempted provider
- `startedAtEpochMillis`, `completedAtEpochMillis`

## `DiscoveryResult`

- `internalId` – deterministic internal merge ID; not a permanent global podcast ID
- `title`, `publisher`, `description`, `artworkUrl`, `websiteUrl`, `language`
- `categories`
- `sources`, `sourceHits`
- `targets`, `preferredTarget`
- `music`
- `feedVerification`
- `relevanceScore`
- `mergeWarnings`

## `MusicClassification`

- `group`
- `probability`
- `declaredBy`
- `genres`
- `evidence`

The probability is a ranking/classification confidence, not a statistical guarantee.

## `FeedVerification`

Important fields:

- requested/final URL and HTTP status
- RSS/Atom/RDF kind
- audio/video/without-media status
- episode and enclosure counts
- feed and episode images
- last publication
- 30- and 90-day activity counts
- activity status
- regularity status and median interval
- podcast GUID and medium
- categories and recent episode titles
- warnings/error

## Extension interfaces

### `SearchProvider`

```kotlin
interface SearchProvider {
    val id: ProviderId
    fun search(request: SearchRequest, context: ProviderContext): ProviderResult
}
```

### `BrowseProvider`

```kotlin
interface BrowseProvider {
    val id: ProviderId
    val supportedModes: Set<BrowseMode>
    fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult
}
```

### `DiscoveryHttpClient`

```kotlin
fun interface DiscoveryHttpClient {
    fun execute(request: HttpRequest): HttpResponse
}
```

This makes provider tests deterministic and allows the host app to add certificate policy, proxying or request instrumentation without modifying provider code.
