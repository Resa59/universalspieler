package de.rdoe.weeklydjshows.discovery

import de.rdoe.weeklydjshows.discovery.classify.MusicClassifier
import de.rdoe.weeklydjshows.discovery.feed.DefaultFeedVerifier
import de.rdoe.weeklydjshows.discovery.internal.Json
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.internal.asObject
import de.rdoe.weeklydjshows.discovery.internal.string
import de.rdoe.weeklydjshows.discovery.merge.ResultMerger
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.*
import de.rdoe.weeklydjshows.discovery.provider.*
import de.rdoe.weeklydjshows.discovery.resolver.DefaultUrlResolver

private class FakeHttpClient(private val responder: (HttpRequest) -> HttpResponse) : DiscoveryHttpClient {
    override fun execute(request: HttpRequest): HttpResponse = responder(request)
}

private fun response(url: String, body: String, status: Int = 200, contentType: String = "application/json") = HttpResponse(
    status,
    url,
    mapOf("Content-Type" to listOf(contentType)),
    body.toByteArray()
)

fun main() {
    testJson()
    testEpisodeDerivedTitleDetection()
    testMusicClassification()
    testMerge()
    testMergeKeepsUnrelatedContainersSeparate()
    testExactCrossProviderShowIdentityMerge()
    testAmbiguousTextIdentityDoesNotBridgePlatformDuplicates()
    testVerifiedDeadFeedsAreRemoved()
    testTransientFeedFailureIsNotTreatedAsDead()
    testFeedVerification()
    testTruncatedFeedPreview()
    testUrlResolution()
    testApplePodcastFeedResolution()
    testShareTextAndRedirectResolution()
    testExactNameRanking()
    testSpotifyPublicCatalog()
    testProviderParsingAndEngine()
    testThrownProviderNeverStaysSearching()
    println("ALL TESTS PASSED")
}

private fun testSpotifyPublicCatalog() {
    val tokenHtml = """
        <html><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"state":{"settings":{"session":{"accessToken":"public-test-token","accessTokenExpirationTimestampMs":4102444800000}}}}}}
        </script></html>
    """.trimIndent()
    val searchJson = """
        {"data":{"searchV2":{"playlists":{"items":[{"data":{
          "uri":"spotify:playlist:6b6l3l5UnpTNDUXTPLdiWU",
          "name":"The Anjunadeep Edition",
          "ownerV2":{"data":{"name":"Christian Hawkins"}},
          "images":{"items":[{"sources":[{"url":"https://i.scdn.co/image/cover","width":640,"height":640}]}]}
        }}]}}}}
    """.trimIndent()
    val playlistJson = """
        {"data":{"playlistV2":{"name":"The Anjunadeep Edition","content":{"items":[{
          "addedAt":{"isoString":"2026-08-06T15:30:00Z"},
          "itemV2":{"data":{"__typename":"Track","uri":"spotify:track:abc123","name":"Deep Test",
          "duration":{"totalMilliseconds":321000},"artists":{"items":[{"profile":{"name":"DJ Unit"}}]},
          "albumOfTrack":{"coverArt":{"sources":[{"url":"https://i.scdn.co/image/track","width":640,"height":640}]}}}}
        }]}}}}
    """.trimIndent()
    val http = FakeHttpClient { request ->
        when {
            request.url.contains("/embed/track/") -> response(request.url, tokenHtml, contentType = "text/html")
            request.url.contains("operationName=searchDesktop") -> response(request.url, searchJson)
            request.url.contains("operationName=fetchPlaylist") -> response(request.url, playlistJson)
            else -> response(request.url, "{}", 404)
        }
    }
    val catalog = SpotifyPublicCatalog(http, "test-agent")
    val found = catalog.searchPlaylists("Anjunadeep", 10)
    check(found.single().title == "The Anjunadeep Edition")
    check(found.single().id == "6b6l3l5UnpTNDUXTPLdiWU")
    val playlist = catalog.playlist(found.single().url, 50)
    check(playlist.items.single().title == "Deep Test")
    check(playlist.items.single().artists == "DJ Unit")
    check(playlist.items.single().durationMs == 321000L)
    check(playlist.items.single().addedAtEpochMs == 1786030200000L)
}

private fun testJson() {
    val parsed = Json.parse("{\"a\":\"x\",\"n\":2,\"items\":[true,null]}").asObject()!!
    check(parsed.string("a") == "x")
}

private fun testEpisodeDerivedTitleDetection() {
    check(TextTools.looksLikeEpisodeTitleForQuery("The Anjunadeep Edition", "The Anjunadeep Edition 248 with Lane 8"))
    check(TextTools.looksLikeEpisodeTitleForQuery("Future Sound of Egypt", "Future Sound of Egypt Episode 974"))
    check(!TextTools.looksLikeEpisodeTitleForQuery("The Anjunadeep Edition", "The Anjunadeep Edition"))
    check(!TextTools.looksLikeEpisodeTitleForQuery("The Anjunadeep Edition", "Anjunadeep Worldwide"))
}

private fun testMusicClassification() {
    val classifier = MusicClassifier()
    val likely = classifier.classify(listOf(SourceHit(ProviderId.GPODDER, title = "Weekly Techno DJ Mix Radio Show", description = "A new club set every Friday")))
    check(likely.group == ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW) { "Expected likely music, got $likely" }
    val declared = classifier.classify(listOf(SourceHit(ProviderId.PODCAST_INDEX, title = "Label Sessions", declaredMusic = true, declaredMusicReason = "podcast:medium=music")))
    check(declared.group == ResultGroup.DECLARED_MUSIC)
    val other = classifier.classify(listOf(SourceHit(ProviderId.APPLE_PODCASTS, title = "The Music Business Interview Podcast", description = "Interviews about the music industry")))
    check(other.group == ResultGroup.OTHER) { "Expected OTHER, got $other" }
}

private fun testMerge() {
    val url = "https://example.org/feed.xml"
    val a = SourceHit(
        ProviderId.APPLE_PODCASTS,
        providerItemId = "1",
        title = "Night Sessions",
        publisher = "DJ Example",
        feedUrl = url,
        targets = listOf(IntegrationTarget(TargetKind.RSS_AUDIO, url, feedUrl = url, requirement = IntegrationRequirement.DIRECT_RSS_AUDIO))
    )
    val b = SourceHit(
        ProviderId.PODCAST_INDEX,
        providerItemId = "99",
        title = "Night Sessions Podcast",
        publisher = "DJ Example",
        feedUrl = "https://example.org/feed.xml?utm_source=test",
        targets = listOf(IntegrationTarget(TargetKind.RSS_AUDIO, "https://example.org/feed.xml?utm_source=test", feedUrl = "https://example.org/feed.xml?utm_source=test", requirement = IntegrationRequirement.DIRECT_RSS_AUDIO))
    )
    val merged = ResultMerger().merge(listOf(a, b), "Night Sessions")
    check(merged.size == 1)
    check(merged.first().sources.size == 2)
}

private fun testMergeKeepsUnrelatedContainersSeparate() {
    val first = SourceHit(
        provider = ProviderId.YOUTUBE,
        providerItemId = "https://www.youtube.com/playlist?list=episode248",
        title = "The Anjunadeep Edition 248 with Lane 8",
        publisher = "alexhousebass",
        websiteUrl = "https://www.youtube.com/playlist?list=episode248",
        targets = listOf(
            IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=episode248",
                requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
            ),
        ),
    )
    val second = SourceHit(
        provider = ProviderId.YOUTUBE,
        providerItemId = "https://www.youtube.com/playlist?list=episode249",
        title = "The Anjunadeep Edition 249 with James Grant",
        publisher = "another-user",
        websiteUrl = "https://www.youtube.com/playlist?list=episode249",
        targets = listOf(
            IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=episode249",
                requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
            ),
        ),
    )
    val merged = ResultMerger().merge(listOf(first, second), "The Anjunadeep Edition")
    check(merged.size == 2) {
        "Different YouTube containers must never merge merely because their titles and hosts are similar: $merged"
    }
}

private fun testExactCrossProviderShowIdentityMerge() {
    val rss = SourceHit(
        provider = ProviderId.PODCAST_INDEX,
        title = "The Anjunadeep Edition",
        publisher = "Anjunadeep",
        feedUrl = "https://example.org/anjunadeep.xml",
        targets = listOf(
            IntegrationTarget(
                TargetKind.RSS_AUDIO,
                "https://example.org/anjunadeep.xml",
                feedUrl = "https://example.org/anjunadeep.xml",
                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
            ),
        ),
    )
    val youtube = SourceHit(
        provider = ProviderId.YOUTUBE,
        title = "the  anjunadeep edition",
        publisher = "ANJUNADEEP",
        targets = listOf(
            IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=official-show",
                requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED,
            ),
        ),
    )
    val merged = ResultMerger().merge(listOf(rss, youtube), "The Anjunadeep Edition")
    check(merged.size == 1) { "Exact title + publisher across providers should remain a useful source chooser" }
    check(merged.single().targets.size == 2)
}

private fun testAmbiguousTextIdentityDoesNotBridgePlatformDuplicates() {
    val rss = SourceHit(
        provider = ProviderId.PODCAST_INDEX,
        title = "Aly & Fila",
        publisher = "Aly & Fila",
        feedUrl = "https://example.org/alyfila.xml",
        targets = listOf(
            IntegrationTarget(TargetKind.RSS_AUDIO, "https://example.org/alyfila.xml", feedUrl = "https://example.org/alyfila.xml", requirement = IntegrationRequirement.DIRECT_RSS_AUDIO),
        ),
    )
    fun youtube(id: String) = SourceHit(
        provider = ProviderId.YOUTUBE,
        providerItemId = id,
        title = "Aly & Fila",
        publisher = "Aly & Fila",
        targets = listOf(
            IntegrationTarget(TargetKind.YOUTUBE_PLAYLIST, "https://www.youtube.com/playlist?list=$id", requirement = IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED),
        ),
    )
    val merged = ResultMerger().merge(listOf(rss, youtube("one"), youtube("two")), "Aly & Fila")
    check(merged.size == 3) {
        "An RSS hit must not bridge multiple same-provider containers that only share display text: $merged"
    }
}

private fun testVerifiedDeadFeedsAreRemoved() {
    val deadUrl = "https://dead.example/show.xml"
    val dead = SourceHit(
        provider = ProviderId.PODCAST_INDEX,
        title = "Dead Show",
        publisher = "DJ Unit",
        feedUrl = deadUrl,
        targets = listOf(
            IntegrationTarget(
                TargetKind.RSS_AUDIO,
                deadUrl,
                feedUrl = deadUrl,
                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
            ),
        ),
    )
    val verification = FeedVerification(
        requestedUrl = deadUrl,
        finalUrl = deadUrl,
        httpStatus = 404,
        status = FeedStatus.UNREACHABLE,
    )
    val merged = ResultMerger().merge(
        listOf(dead),
        "Dead Show",
        mapOf(TextTools.normalizeUrl(deadUrl)!! to verification),
    )
    check(merged.isEmpty()) { "A verified HTTP 404 feed must not remain subscribable: $merged" }
}

private fun testTransientFeedFailureIsNotTreatedAsDead() {
    val url = "https://temporarily-offline.example/show.xml"
    val hit = SourceHit(
        provider = ProviderId.GPODDER,
        title = "Temporary Show",
        publisher = "DJ Unit",
        feedUrl = url,
        targets = listOf(
            IntegrationTarget(
                TargetKind.RSS_AUDIO,
                url,
                feedUrl = url,
                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
            ),
        ),
    )
    val verification = FeedVerification(
        requestedUrl = url,
        status = FeedStatus.UNREACHABLE,
        error = "timeout",
    )
    val merged = ResultMerger().merge(
        listOf(hit),
        "Temporary Show",
        mapOf(TextTools.normalizeUrl(url)!! to verification),
    )
    check(merged.single().targets.single().kind == TargetKind.RSS_AUDIO) {
        "A timeout without an HTTP response is transient and must not permanently hide the feed"
    }
}

private fun testFeedVerification() {
    val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
          <channel>
            <title>Weekly Techno</title>
            <link>https://example.org</link>
            <itunes:image href="https://example.org/cover.jpg"/>
            <podcast:medium>music</podcast:medium>
            <item><title>DJ Mix 3</title><pubDate>Thu, 06 Aug 2026 12:00:00 +0000</pubDate><enclosure url="https://example.org/3.mp3" type="audio/mpeg"/></item>
            <item><title>DJ Mix 2</title><pubDate>Thu, 30 Jul 2026 12:00:00 +0000</pubDate><enclosure url="https://example.org/2.mp3" type="audio/mpeg"/></item>
            <item><title>DJ Mix 1</title><pubDate>Thu, 23 Jul 2026 12:00:00 +0000</pubDate><enclosure url="https://example.org/1.mp3" type="audio/mpeg"/></item>
          </channel>
        </rss>
    """.trimIndent()
    val http = FakeHttpClient { request -> response(request.url, feed, contentType = "application/rss+xml") }
    val verification = DefaultFeedVerifier(http).verify("https://example.org/feed.xml")
    check(verification.status == FeedStatus.VALID_AUDIO_FEED) { verification.toString() }
    check(verification.audioEnclosureCount == 3)
    check(verification.regularityStatus == RegularityStatus.WEEKLY)
    check(verification.podcastMedium == "music")
}

private fun testTruncatedFeedPreview() {
    val boundedPrefix = """
        <rss version="2.0"><channel><title>Large Podcast</title>
        <item><title>Episode that must remain visible</title><enclosure url="https://example.org/1.mp3" type="audio/mpeg"/></item>
        <item><title>This second entry was cut off
    """.trimIndent()
    val http = FakeHttpClient { request ->
        HttpResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("Content-Type" to listOf("application/rss+xml")),
            body = boundedPrefix.toByteArray(),
            truncated = true,
        )
    }
    val verification = DefaultFeedVerifier(http).verify("https://example.org/very-large-feed.xml")
    check(verification.status == FeedStatus.VALID_AUDIO_FEED) { verification.toString() }
    check(verification.episodeTitles == listOf("Episode that must remain visible"))
    check(verification.warnings.any { it.contains("Vorschau") })
}

private fun testShareTextAndRedirectResolution() {
    val shared = "Hör dir The Anjunadeep Edition auf SoundCloud an:\nhttps://on.soundcloud.com/uXJdqTxzEy0WcRgKyq\n#Anjunadeep"
    val shortUrl = TextTools.firstHttpUrl(shared)
    check(shortUrl == "https://on.soundcloud.com/uXJdqTxzEy0WcRgKyq")
    val canonical = "https://soundcloud.com/anjunadeep/sets/theanjunadeepedition?ref=clipboard&p=a&c=0&si=test"
    val http = FakeHttpClient { request ->
        if (request.url == shortUrl) response(canonical, "<html></html>", contentType = "text/html")
        else response(request.url, "", 404, "text/plain")
    }
    val verifier = DefaultFeedVerifier(http)
    val resolved = DefaultUrlResolver(http, verifier).resolve(shortUrl!!)
    val target = resolved.targets.single()
    check(target.kind == TargetKind.SOUNDCLOUD_PLAYLIST) { target.toString() }
    check(target.url == "https://soundcloud.com/anjunadeep/sets/theanjunadeepedition") { target.url }
}

private fun testUrlResolution() {
    val soundCloudRss = """
        <rss version="2.0"><channel><title>Legacy SoundCloud Podcast</title>
        <item><title>Episode 1</title><enclosure url="https://example.org/episode.mp3" type="audio/mpeg"/></item>
        </channel></rss>
    """.trimIndent()
    val http = FakeHttpClient { request ->
        if (request.url.startsWith("https://feeds.soundcloud.com/")) {
            response(request.url, soundCloudRss, contentType = "application/rss+xml")
        } else {
            response(request.url, "", 404, "text/plain")
        }
    }
    val verifier = DefaultFeedVerifier(http)
    val resolver = DefaultUrlResolver(http, verifier)
    val yt = resolver.resolve("https://www.youtube.com/channel/UC1234567890")
    check(yt.targets.single().feedUrl?.contains("channel_id=UC1234567890") == true)
    check(yt.targets.single().requirement == IntegrationRequirement.FEED_AND_PLATFORM_PLAYER)
    val ytStreams = resolver.resolve("https://www.youtube.com/@alyandfila/streams")
    check(ytStreams.targets.single().kind == TargetKind.YOUTUBE_CHANNEL)
    check(ytStreams.targets.single().url == "https://www.youtube.com/@alyandfila/streams") {
        "An explicit YouTube channel tab must not be collapsed to the channel root"
    }
    check(ytStreams.targets.single().feedUrl == null) {
        "Channel tabs must stay platform listings; the generic channel Atom feed mixes the scope"
    }
    val anjunadeepId = "PLOftnzGIKwJB1h6ErEcFJTObuqqGNZPXI"
    val ytPlaylist = resolver.resolve("https://youtube.com/playlist?list=$anjunadeepId&si=test-share-token")
    check(ytPlaylist.targets.single().kind == TargetKind.YOUTUBE_PLAYLIST)
    check(ytPlaylist.targets.single().feedUrl == "https://www.youtube.com/feeds/videos.xml?playlist_id=$anjunadeepId")
    check(ytPlaylist.targets.single().requirement == IntegrationRequirement.FEED_AND_PLATFORM_PLAYER)
    val pastedPlaylistFeed = resolver.resolve("https://www.youtube.com/feeds/videos.xml?playlist_id=$anjunadeepId")
    check(pastedPlaylistFeed.targets.single().kind == TargetKind.YOUTUBE_PLAYLIST)
    check(pastedPlaylistFeed.targets.single().stableId == anjunadeepId)
    val soundCloudFeed = resolver.resolve("https://feeds.soundcloud.com/users/soundcloud:users:123/sounds.rss")
    check(soundCloudFeed.targets.single().kind == TargetKind.RSS_AUDIO)
    check(soundCloudFeed.targets.single().feedUrl?.startsWith("https://feeds.soundcloud.com/") == true)
    val spotify = resolver.resolve("spotify:playlist:abc123")
    check(spotify.targets.single().kind == TargetKind.SPOTIFY_PLAYLIST)
    check(spotify.targets.single().requirement == IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED)
}

private fun testApplePodcastFeedResolution() {
    val appleUrl = "https://podcasts.apple.com/de/podcast/machtwechsel/id1568123217"
    val feedUrl = "https://feeds.example.org/machtwechsel.xml"
    val rss = """
        <rss version="2.0"><channel><title>Machtwechsel</title>
        <item><title>Testfolge</title><enclosure url="https://feeds.example.org/test.mp3" type="audio/mpeg"/></item>
        </channel></rss>
    """.trimIndent()
    val lookup = """{"resultCount":1,"results":[{"collectionId":1568123217,"collectionName":"Machtwechsel","artistName":"Test","feedUrl":"$feedUrl","collectionViewUrl":"$appleUrl","primaryGenreName":"News"}]}"""
    val http = FakeHttpClient { request ->
        when {
            request.url.startsWith("https://itunes.apple.com/lookup") -> response(request.url, lookup)
            request.url.startsWith("https://itunes.apple.com/search") -> response(request.url, lookup)
            request.url == feedUrl -> response(request.url, rss, contentType = "application/rss+xml")
            else -> response(request.url, "", 404, "text/plain")
        }
    }
    val verifier = DefaultFeedVerifier(http)
    val resolved = DefaultUrlResolver(http, verifier).resolve(appleUrl)
    val appleTarget = resolved.targets.single()
    check(appleTarget.kind == TargetKind.APPLE_PODCAST)
    check(appleTarget.feedUrl == feedUrl)
    check(appleTarget.requirement == IntegrationRequirement.DIRECT_RSS_AUDIO)
    check(resolved.feedVerifications.single().status == FeedStatus.VALID_AUDIO_FEED)

    val providerHit = ApplePodcastProvider().search(
        SearchRequest("Machtwechsel", countries = listOf("DE")),
        ProviderContext(http, EmptySecretProvider, "test-agent"),
    ).hits.single()
    val providerAppleTarget = providerHit.targets.single { it.kind == TargetKind.APPLE_PODCAST }
    check(providerAppleTarget.feedUrl == feedUrl)
    check(providerAppleTarget.requirement == IntegrationRequirement.DIRECT_RSS_AUDIO)
}

private fun testExactNameRanking() {
    val exactYoutube = SourceHit(
        provider = ProviderId.YOUTUBE,
        title = "The Anjunadeep Edition",
        categories = setOf("YouTube"),
        targets = listOf(
            IntegrationTarget(
                TargetKind.YOUTUBE_PLAYLIST,
                "https://www.youtube.com/playlist?list=PLOftnzGIKwJB1h6ErEcFJTObuqqGNZPXI",
                stableId = "PLOftnzGIKwJB1h6ErEcFJTObuqqGNZPXI",
                requirement = IntegrationRequirement.FEED_AND_PLATFORM_PLAYER,
            ),
        ),
    )
    val declaredButWeaker = SourceHit(
        provider = ProviderId.PODCAST_INDEX,
        title = "Anjunadeep Sessions Radio Show",
        declaredMusic = true,
        declaredMusicReason = "podcast:medium=music",
        targets = listOf(
            IntegrationTarget(
                TargetKind.RSS_AUDIO,
                "https://example.org/anjunadeep-sessions.xml",
                feedUrl = "https://example.org/anjunadeep-sessions.xml",
                requirement = IntegrationRequirement.DIRECT_RSS_AUDIO,
            ),
        ),
    )
    val ranked = ResultMerger().merge(listOf(declaredButWeaker, exactYoutube), "The Anjunadeep Edition")
    check(ranked.first().title == "The Anjunadeep Edition") { ranked.joinToString { it.title } }
    check(ranked.first().music.group == ResultGroup.OTHER) {
        "The ranking test must prove that an exact name can outrank classification without faking a music declaration"
    }
}

private fun testProviderParsingAndEngine() {
    val rss = """
        <rss version="2.0"><channel><title>A State of Test</title><category>Music</category>
        <item><title>Radio Show 2</title><pubDate>Thu, 06 Aug 2026 10:00:00 +0000</pubDate><enclosure url="https://test.example/2.mp3" type="audio/mpeg"/></item>
        <item><title>Radio Show 1</title><pubDate>Thu, 30 Jul 2026 10:00:00 +0000</pubDate><enclosure url="https://test.example/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>
    """.trimIndent()
    val http = FakeHttpClient { request ->
        when {
            request.url.contains("itunes.apple.com") -> response(request.url, """{"resultCount":1,"results":[{"collectionId":7,"collectionName":"A State of Test","artistName":"DJ Unit","feedUrl":"https://test.example/feed.xml","primaryGenreName":"Music","genres":["Music"],"artworkUrl600":"https://test.example/a.jpg"}]}""")
            request.url.contains("api.podcastindex.org/search") -> response(request.url, """{"resultCount":1,"results":[{"collectionId":7,"collectionName":"A State of Test","artistName":"DJ Unit","feedUrl":"https://test.example/feed.xml","primaryGenreName":"Music"}]}""")
            request.url == "https://test.example/feed.xml" -> response(request.url, rss, contentType = "application/rss+xml")
            else -> response(request.url, "[]")
        }
    }
    val context = ProviderContext(http, EmptySecretProvider, "test-agent")
    val verifier = DefaultFeedVerifier(http)
    val resolver = DefaultUrlResolver(http, verifier)
    val engine = DiscoveryEngine(
        searchProviders = listOf(ApplePodcastProvider(), PodcastIndexProvider()),
        browseProviders = emptyList(),
        providerContext = context,
        feedVerifier = verifier,
        urlResolver = resolver,
        config = DiscoveryEngineConfig(workerThreads = 3, providerDeadlineMillis = 5_000, verificationThreads = 2)
    )
    engine.use {
        val result = it.searchBlocking(SearchRequest("A State of Test", countries = listOf("DE"), verifyTopRssResults = 2))
        check(result.results.size == 1) { result.toString() }
        val first = result.results.first()
        check(first.sources == setOf(ProviderId.APPLE_PODCASTS, ProviderId.PODCAST_INDEX))
        check(first.music.group == ResultGroup.DECLARED_MUSIC)
        check(first.feedVerification?.status == FeedStatus.VALID_AUDIO_FEED)
        check(first.preferredTarget?.requirement == IntegrationRequirement.DIRECT_RSS_AUDIO)
    }
}

private fun testThrownProviderNeverStaysSearching() {
    val http = FakeHttpClient { request -> response(request.url, "[]") }
    val context = ProviderContext(http, EmptySecretProvider, "test-agent")
    val verifier = DefaultFeedVerifier(http)
    val throwing = object : SearchProvider {
        override val id = ProviderId.SOUNDCLOUD
        override fun search(request: SearchRequest, context: ProviderContext): ProviderResult {
            error("synthetic provider failure")
        }
    }
    val engine = DiscoveryEngine(
        searchProviders = listOf(throwing),
        browseProviders = emptyList(),
        providerContext = context,
        feedVerifier = verifier,
        urlResolver = DefaultUrlResolver(http, verifier),
        config = DiscoveryEngineConfig(workerThreads = 2, providerDeadlineMillis = 2_000, verificationThreads = 1),
    )
    engine.use {
        val response = it.searchBlocking(SearchRequest("test", verifyTopRssResults = 0))
        check(response.providerStatuses.single().state == ProviderState.FAILED) { response.providerStatuses.toString() }
    }
}
