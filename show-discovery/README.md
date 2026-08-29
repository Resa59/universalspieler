# Weekly DJ Shows – Discovery Core 0.1.0

A UI-independent Kotlin/JVM module for finding, classifying, merging and validating recurring DJ shows, radio shows and music podcasts.

The module is designed to be copied into an Android project without pulling in an HTTP, JSON, XML or coroutine library. It uses only Kotlin/JDK APIs that are also available on Android and targets JVM 8 bytecode.

## Included functions

- Parallel concrete search across:
  - Podcast Index (keyless search; enhanced search with optional key/secret)
  - Apple Podcasts / iTunes Search API
  - Feedly legacy feed search
  - gPodder.net
  - Mixcloud
  - optional YouTube Data API
  - optional Spotify Web API
  - optional SoundCloud API
- Browse API for:
  - trending/popular shows
  - newly indexed shows
  - recently updated shows
  - genre-specific discovery
- Three result groups:
  - `DECLARED_MUSIC`
  - `LIKELY_DJ_OR_MUSIC_SHOW`
  - `OTHER`
- Feed URL normalization and cautious cross-directory deduplication
- RSS, Atom and RDF feed verification
- Audio/video enclosure detection
- last-release, activity and publishing-regularity analysis
- website RSS/Atom autodiscovery
- URL resolution for YouTube, Spotify, Mixcloud and SoundCloud
- explicit integration contracts when no RSS feed exists
- callbacks for provider status, partial results and feed-verification progress
- no UI dependency and no playback dependency

## Quick start

```kotlin
val engine = DiscoveryModule.create(
    secrets = object : SecretProvider {
        override fun get(name: SecretName): String? = when (name) {
            SecretName.PODCAST_INDEX_KEY -> secureStore.get("podcastIndexKey")
            SecretName.PODCAST_INDEX_SECRET -> secureStore.get("podcastIndexSecret")
            SecretName.YOUTUBE_API_KEY -> secureStore.get("youtubeApiKey")
            else -> null
        }
    }
)

val task = engine.search(
    SearchRequest(
        query = "A State of Trance",
        countries = listOf("DE", "US"),
        musicMode = MusicMode.ALL,
        verifyTopRssResults = 15
    ),
    object : DiscoveryListener {
        override fun onProviderStatus(status: ProviderStatus) {
            // Forward to application state; this callback is not guaranteed to run on the UI thread.
        }

        override fun onPartialResults(results: List<DiscoveryResult>) {
            // Results improve while more providers and feed checks finish.
        }
    }
)

val response = task.get()
val best = response.results.firstOrNull()
val target = best?.preferredTarget
```

The host app must call `engine.close()` when the engine is no longer needed.

## Handling the preferred target

```kotlin
when (target?.requirement) {
    IntegrationRequirement.DIRECT_RSS_AUDIO -> {
        // Subscribe target.feedUrl ?: target.url in the normal podcast subsystem.
    }
    IntegrationRequirement.FEED_AND_PLATFORM_PLAYER -> {
        // Subscribe to target.feedUrl for updates.
        // Use the platform adapter/NewPipe-compatible playback layer for each entry.
    }
    IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED -> {
        // Persist the platform URL and stableId.
        // The main app must provide listing/update and playback support for that platform.
    }
    IntegrationRequirement.RESOLUTION_REQUIRED -> {
        // Offer a later/manual resolution step.
    }
    IntegrationRequirement.EXTERNAL_ONLY, null -> {
        // Keep as a discovery link only.
    }
}
```

## Files

- `INTEGRATION.md` – full Android and API integration contract
- `PROVIDERS.md` – provider behavior, credentials and fallbacks
- `SOURCES.md` – official source documentation checked for this version
- `examples/ExampleUsage.kt` – complete host-side example
- `src/test/.../TestMain.kt` – dependency-free test suite

## Build

On a system with `kotlinc`:

```bash
./build-local.sh
./run-tests.sh
```

The included prebuilt thin JAR is:

`build/weekly-dj-shows-discovery-0.1.0.jar`

For an Android project, source-module integration is preferred over copying the JAR because it keeps package sources visible and lets the app use its existing Kotlin version.
