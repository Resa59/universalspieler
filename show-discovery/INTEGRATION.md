# Integration contract

## 1. Add the module to an Android project

### Preferred: source module

Copy this directory into the project as `show-discovery` and include it:

```kotlin
// settings.gradle.kts
include(":app", ":show-discovery")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":show-discovery"))
}
```

Add internet permission in the app manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

The module contains no Android resources and no UI code.

### Alternative: thin JAR

Copy `build/weekly-dj-shows-discovery-0.1.0.jar` into `app/libs/`:

```kotlin
dependencies {
    implementation(files("libs/weekly-dj-shows-discovery-0.1.0.jar"))
}
```

The host app must already include the Kotlin standard library, as normal Kotlin Android projects do.

## 2. Secrets

The library never stores, serializes or exports credentials. It asks the host app through:

```kotlin
fun interface SecretProvider {
    fun get(name: SecretName): String?
}
```

Supported values:

- `FEEDLY_TOKEN`
- `PODCAST_INDEX_KEY`
- `PODCAST_INDEX_SECRET`
- `YOUTUBE_API_KEY`
- `SPOTIFY_BEARER_TOKEN`
- `SOUNDCLOUD_ACCESS_TOKEN`

The host app should retrieve them from its own Keystore-backed storage. Do not hardcode secrets in the APK. `DiscoveryResult`, `SourceHit`, `DiscoveryResponse` and `ResolutionResult` contain no credential fields.

## 3. Concrete search API

### Asynchronous

```kotlin
fun search(
    request: SearchRequest,
    listener: DiscoveryListener = NoOpDiscoveryListener
): DiscoveryTask<DiscoveryResponse>
```

`DiscoveryTask` supports `cancel()`, `isDone()` and `get()`.

### Blocking

```kotlin
fun searchBlocking(
    request: SearchRequest,
    listener: DiscoveryListener = NoOpDiscoveryListener
): DiscoveryResponse
```

Never call the blocking form on Android's main thread.

### Search request

```kotlin
data class SearchRequest(
    val query: String,
    val countries: List<String> = listOf("DE", "US"),
    val language: String? = null,
    val maxResultsPerProvider: Int = 30,
    val musicMode: MusicMode = MusicMode.ALL,
    val includePlatformResults: Boolean = true,
    val verifyTopRssResults: Int = 12,
    val enabledProviders: Set<ProviderId>? = null
)
```

Recommended behavior:

- Use `ALL` for the normal search so non-music results are not lost.
- Use the result's `music.group` for the three sections.
- Use `DJ_AND_MUSIC` only for an explicit filter.
- Keep `verifyTopRssResults` around 10–20. Setting it to zero returns directory results without downloading feeds.

## 4. Browse API

```kotlin
fun browse(
    request: BrowseRequest,
    listener: DiscoveryListener = NoOpDiscoveryListener
): DiscoveryTask<DiscoveryResponse>
```

Supported modes in the default providers:

| Mode | Podcast Index | gPodder | Mixcloud |
|---|---:|---:|---:|
| `TRENDING` | yes, credentials required | no | yes |
| `POPULAR` | yes, credentials required | yes | yes |
| `NEW` | yes, credentials required | no | yes |
| `RECENTLY_UPDATED` | yes, credentials required | no | no |
| `GENRE` | music search, credentials required | tag directory | search |
| `RANDOM` | no default provider | no | no |

Genre names exposed for a first start page are available from:

```kotlin
DiscoveryGenres.canonical
```

The host app may also pass any free genre/tag string.

## 5. Result contract

Important `DiscoveryResult` fields:

- `title`, `publisher`, `description`, `artworkUrl`
- `sources`: all directories/platforms that contributed
- `targets`: all retained RSS and platform integration targets
- `preferredTarget`: recommended integration target
- `music.group`, `music.probability`, `music.genres`, `music.evidence`
- `feedVerification`: populated for verified top RSS results
- `relevanceScore`
- `mergeWarnings`

### Music groups

`DECLARED_MUSIC` means a provider or feed explicitly declared music, such as Podcast Index `podcast:medium=music` or an official Music category.

`LIKELY_DJ_OR_MUSIC_SHOW` means the weighted classifier found multiple music/DJ signals. The evidence list explains the decision.

`OTHER` remains available in normal search and must not be silently discarded.

## 6. Target contract

### Normal podcast

```text
kind = RSS_AUDIO
requirement = DIRECT_RSS_AUDIO
feedUrl = stable feed URL
```

This is ready for the app's ordinary subscription and player subsystem.

### YouTube channel

```text
kind = YOUTUBE_CHANNEL
requirement = FEED_AND_PLATFORM_PLAYER
feedUrl = https://www.youtube.com/feeds/videos.xml?channel_id=...
```

The Atom feed can be used for update discovery. Its entries point to YouTube media rather than audio enclosures, so playback needs the future YouTube/NewPipe adapter.

A YouTube handle such as `/@name` cannot be converted to the official Atom feed without first resolving the channel ID. With a YouTube API key, search results already contain the channel ID. URL-only resolution marks handle URLs as `RESOLUTION_REQUIRED`.

### YouTube playlist

```text
kind = YOUTUBE_PLAYLIST
requirement = FEED_AND_PLATFORM_PLAYER
feedUrl = https://www.youtube.com/feeds/videos.xml?playlist_id=...
```

The small Atom feed is used for update discovery while the playlist URL and stable ID remain the
canonical platform identity. Feed entries still point to YouTube media, so playback uses the
platform-aware resolver. If the feed is temporarily unavailable, the host can fall back to listing
the persisted playlist URL.

### Spotify show, playlist or artist

```text
kind = SPOTIFY_SHOW / SPOTIFY_PLAYLIST / SPOTIFY_ARTIST
requirement = PLATFORM_ADAPTER_REQUIRED
```

Spotify's catalogue API does not expose a general RSS URL for playlist or artist objects. The module therefore returns the Spotify URL and ID without pretending it is a podcast feed. A same-title RSS result from Apple/Podcast Index may be merged when identity evidence is strong enough.

### Mixcloud and SoundCloud

These are also returned as platform targets. They need a host-side listing/update adapter and player. No unofficial RSS URL is invented.

## 7. URL resolution

```kotlin
val result = engine.resolveUrl(userInput)
```

The resolver:

1. recognizes Spotify URIs and supported platform URLs;
2. checks whether an HTTP URL is already RSS/Atom;
3. reads website RSS/Atom autodiscovery links;
4. if needed, probes a small set of conventional paths;
5. returns discovered feeds and platform links separately.

Use this for an “RSS feed on website” or “add link” function.

## 8. Provider status and partial results

`DiscoveryListener` callbacks may arrive from background threads. Forward them to the app's state layer or main dispatcher.

Provider states include:

- `SEARCHING`
- `SUCCESS`
- `NO_RESULTS`
- `CREDENTIALS_MISSING`
- `RATE_LIMITED`
- `TIMEOUT`
- `UNAVAILABLE`
- `INVALID_RESPONSE`
- `FAILED`

One provider failure never aborts the others.

## 9. Deduplication guarantees

Automatic merge is based on strong identifiers first:

- normalized feed URL
- same provider ID
- Podcast Index ID / GUID
- Apple ID
- platform stable ID

Website, title, publisher and artwork similarity are secondary evidence. Distinct RSS URLs are retained and a warning is added instead of silently deleting one edition.

## 10. Threading and lifecycle

- network providers run in a fixed worker pool;
- feed checks run in a separate verification pool;
- partial results are recalculated whenever a provider or verification completes;
- `DiscoveryTask.cancel()` interrupts outstanding work where possible;
- call `DiscoveryEngine.close()` to release executors.
