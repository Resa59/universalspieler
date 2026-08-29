package de.rdoe.weeklydjshows.discovery

import de.rdoe.weeklydjshows.discovery.feed.FeedVerifier
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.merge.ResultMerger
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.provider.*
import de.rdoe.weeklydjshows.discovery.resolver.UrlResolver
import java.io.Closeable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryTask<T> internal constructor(
    private val future: Future<T>,
    private val cancelled: AtomicBoolean
) {
    fun cancel(mayInterrupt: Boolean = true): Boolean {
        cancelled.set(true)
        return future.cancel(mayInterrupt)
    }

    fun isDone(): Boolean = future.isDone
    fun get(): T = future.get()
    fun get(timeout: Long, unit: TimeUnit): T = future.get(timeout, unit)
}

data class DiscoveryEngineConfig(
    val userAgent: String = "WeeklyDJShows/1.3.1 (show-discovery/0.2.4)",
    val workerThreads: Int = 8,
    val providerDeadlineMillis: Long = 12_000,
    val verificationThreads: Int = 4
) {
    init {
        require(workerThreads in 2..32)
        require(providerDeadlineMillis in 2_000..60_000)
        require(verificationThreads in 1..16)
    }
}

class DiscoveryEngine(
    private val searchProviders: List<SearchProvider>,
    private val browseProviders: List<BrowseProvider>,
    private val providerContext: ProviderContext,
    private val feedVerifier: FeedVerifier,
    private val urlResolver: UrlResolver,
    private val merger: ResultMerger = ResultMerger(),
    private val config: DiscoveryEngineConfig = DiscoveryEngineConfig()
) : Closeable {
    private val workerExecutor = Executors.newFixedThreadPool(config.workerThreads)
    private val verificationExecutor = Executors.newFixedThreadPool(config.verificationThreads)
    private val coordinatorExecutor = Executors.newCachedThreadPool()

    fun search(request: SearchRequest, listener: DiscoveryListener = NoOpDiscoveryListener): DiscoveryTask<DiscoveryResponse> {
        val cancelled = AtomicBoolean(false)
        val future = coordinatorExecutor.submit<DiscoveryResponse> { searchBlocking(request, listener, cancelled) }
        return DiscoveryTask(future, cancelled)
    }

    fun searchBlocking(request: SearchRequest, listener: DiscoveryListener = NoOpDiscoveryListener): DiscoveryResponse =
        searchBlocking(request, listener, AtomicBoolean(false))

    fun browse(request: BrowseRequest, listener: DiscoveryListener = NoOpDiscoveryListener): DiscoveryTask<DiscoveryResponse> {
        val cancelled = AtomicBoolean(false)
        val future = coordinatorExecutor.submit<DiscoveryResponse> { browseBlocking(request, listener, cancelled) }
        return DiscoveryTask(future, cancelled)
    }

    fun browseBlocking(request: BrowseRequest, listener: DiscoveryListener = NoOpDiscoveryListener): DiscoveryResponse =
        browseBlocking(request, listener, AtomicBoolean(false))

    fun resolveUrl(input: String): ResolutionResult = urlResolver.resolve(input)

    /** Converts a resolved URL into the same result model used by provider search cards. */
    fun discoveryResult(resolution: ResolutionResult): DiscoveryResult? {
        if (resolution.targets.isEmpty()) return null
        val verificationByUrl = linkedMapOf<String, FeedVerification>()
        resolution.feedVerifications.forEach { verification ->
            listOfNotNull(verification.requestedUrl, verification.finalUrl).forEach { rawUrl ->
                TextTools.normalizeUrl(rawUrl)?.let { verificationByUrl[it] = verification }
            }
        }
        val target = resolution.targets.first()
        val verification = resolution.feedVerifications
            .sortedByDescending {
                when (it.status) {
                    FeedStatus.VALID_AUDIO_FEED -> 3
                    FeedStatus.VALID_VIDEO_FEED -> 2
                    FeedStatus.VALID_FEED_WITHOUT_MEDIA -> 1
                    else -> 0
                }
            }
            .firstOrNull()
        val provider = when (target.kind) {
            TargetKind.YOUTUBE_CHANNEL, TargetKind.YOUTUBE_PLAYLIST, TargetKind.YOUTUBE_VIDEO -> ProviderId.YOUTUBE
            TargetKind.SOUNDCLOUD_PROFILE, TargetKind.SOUNDCLOUD_PLAYLIST, TargetKind.SOUNDCLOUD_TRACK -> ProviderId.SOUNDCLOUD
            TargetKind.SPOTIFY_SHOW, TargetKind.SPOTIFY_PLAYLIST, TargetKind.SPOTIFY_ARTIST, TargetKind.SPOTIFY_EPISODE -> ProviderId.SPOTIFY
            TargetKind.MIXCLOUD_PROFILE, TargetKind.MIXCLOUD_SHOW -> ProviderId.MIXCLOUD
            TargetKind.APPLE_PODCAST -> ProviderId.APPLE_PODCASTS
            else -> ProviderId.WEBSITE
        }
        val title = verification?.title?.takeIf { it.isNotBlank() }
            ?: target.title?.takeIf { it.isNotBlank() }
            ?: directTargetTitle(target)
        val feedUrl = target.feedUrl ?: target.url.takeIf {
            target.kind in setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED)
        }
        val hit = SourceHit(
            provider = provider,
            providerItemId = target.stableId ?: target.url,
            providerRank = 0,
            title = title,
            description = verification?.description,
            feedUrl = feedUrl,
            websiteUrl = verification?.websiteUrl ?: target.url,
            artworkUrl = verification?.imageUrl,
            categories = when (provider) {
                ProviderId.YOUTUBE -> setOf("YouTube")
                ProviderId.SOUNDCLOUD -> setOf("SoundCloud")
                ProviderId.SPOTIFY -> setOf("Spotify")
                ProviderId.MIXCLOUD -> setOf("Mixcloud")
                ProviderId.APPLE_PODCASTS -> setOf("Podcast")
                else -> emptySet()
            },
            stableIds = target.stableId?.let { mapOf("direct" to it) }.orEmpty(),
            targets = resolution.targets,
            rawMetadata = mapOf("directInput" to "true"),
        )
        return merger.merge(
            hits = listOf(hit),
            verifications = verificationByUrl,
            musicMode = MusicMode.ALL,
            includePlatformResults = true,
        ).firstOrNull()
    }

    private fun directTargetTitle(target: IntegrationTarget): String = when (target.kind) {
        TargetKind.YOUTUBE_CHANNEL -> target.stableId?.takeIf { it.startsWith("@") } ?: "YouTube-Kanal"
        TargetKind.YOUTUBE_PLAYLIST -> "YouTube-Playlist"
        TargetKind.SOUNDCLOUD_PROFILE -> target.stableId?.substringAfterLast('/')?.ifBlank { null } ?: "SoundCloud-Profil"
        TargetKind.SOUNDCLOUD_PLAYLIST -> "SoundCloud-Playlist"
        TargetKind.SPOTIFY_PLAYLIST -> "Spotify-Playlist"
        TargetKind.APPLE_PODCAST -> "Apple Podcast"
        TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED -> "Feed"
        else -> target.url
    }

    private fun searchBlocking(request: SearchRequest, listener: DiscoveryListener, cancelled: AtomicBoolean): DiscoveryResponse {
        val started = System.currentTimeMillis()
        val providers = searchProviders.filter { request.enabledProviders == null || it.id in request.enabledProviders }
        val statuses = mutableMapOf<ProviderId, ProviderStatus>()
        val hits = mutableListOf<SourceHit>()
        providers.forEach {
            val status = ProviderStatus(it.id, ProviderState.SEARCHING)
            statuses[it.id] = status
            listener.onProviderStatus(status)
        }
        val completion = ExecutorCompletionService<ProviderCompletion>(workerExecutor)
        val futures = providers.associateWith { provider ->
            completion.submit {
                try {
                    ProviderCompletion(provider.id, provider.search(request, providerContext))
                } catch (error: Throwable) {
                    ProviderCompletion(provider.id, error = error)
                }
            }
        }
        collectProviderResults(
            expected = providers.size,
            completion = completion,
            futures = futures.mapKeys { it.key.id },
            statuses = statuses,
            hits = hits,
            cancelled = cancelled,
            listener = listener,
            partialBuilder = {
                merger.merge(hits.toList(), request.query, musicMode = request.musicMode, includePlatformResults = request.includePlatformResults)
            }
        )
        if (cancelled.get()) throw CancellationException("Discovery cancelled")
        val initial = merger.merge(hits, request.query, musicMode = request.musicMode, includePlatformResults = request.includePlatformResults)
        val verifications = verifyTopFeeds(initial, request.verifyTopRssResults, cancelled, listener) { current ->
            listener.onPartialResults(merger.merge(hits, request.query, current, request.musicMode, request.includePlatformResults))
        }
        val results = merger.merge(hits, request.query, verifications, request.musicMode, request.includePlatformResults)
        return DiscoveryResponse(results, statuses.values.sortedBy { it.provider.name }, started, System.currentTimeMillis())
    }

    private fun browseBlocking(request: BrowseRequest, listener: DiscoveryListener, cancelled: AtomicBoolean): DiscoveryResponse {
        val started = System.currentTimeMillis()
        val providers = browseProviders.filter {
            request.mode in it.supportedModes && (request.enabledProviders == null || it.id in request.enabledProviders)
        }
        val statuses = mutableMapOf<ProviderId, ProviderStatus>()
        val hits = mutableListOf<SourceHit>()
        providers.forEach {
            val status = ProviderStatus(it.id, ProviderState.SEARCHING)
            statuses[it.id] = status
            listener.onProviderStatus(status)
        }
        val completion = ExecutorCompletionService<ProviderCompletion>(workerExecutor)
        val futures = providers.associateWith { provider ->
            completion.submit {
                try {
                    ProviderCompletion(provider.id, provider.browse(request, providerContext))
                } catch (error: Throwable) {
                    ProviderCompletion(provider.id, error = error)
                }
            }
        }
        collectProviderResults(
            expected = providers.size,
            completion = completion,
            futures = futures.mapKeys { it.key.id },
            statuses = statuses,
            hits = hits,
            cancelled = cancelled,
            listener = listener,
            partialBuilder = {
                merger.merge(hits.toList(), request.genre, musicMode = request.musicMode, includePlatformResults = request.includePlatformResults)
            }
        )
        if (cancelled.get()) throw CancellationException("Discovery cancelled")
        val initial = merger.merge(hits, request.genre, musicMode = request.musicMode, includePlatformResults = request.includePlatformResults)
        val verifications = verifyTopFeeds(initial, request.verifyTopRssResults, cancelled, listener) { current ->
            listener.onPartialResults(merger.merge(hits, request.genre, current, request.musicMode, request.includePlatformResults))
        }
        val results = merger.merge(hits, request.genre, verifications, request.musicMode, request.includePlatformResults)
        return DiscoveryResponse(results, statuses.values.sortedBy { it.provider.name }, started, System.currentTimeMillis())
    }

    private fun collectProviderResults(
        expected: Int,
        completion: ExecutorCompletionService<ProviderCompletion>,
        futures: Map<ProviderId, Future<ProviderCompletion>>,
        statuses: MutableMap<ProviderId, ProviderStatus>,
        hits: MutableList<SourceHit>,
        cancelled: AtomicBoolean,
        listener: DiscoveryListener,
        partialBuilder: () -> List<DiscoveryResult>
    ) {
        val deadline = System.currentTimeMillis() + config.providerDeadlineMillis
        var completed = 0
        while (completed < expected && !cancelled.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val future = completion.poll(minOf(remaining, 250L), TimeUnit.MILLISECONDS) ?: continue
            val completedProvider = try { future.get() } catch (error: Throwable) {
                null
            }
            completed++
            val result = completedProvider?.result
            if (result != null) {
                statuses[result.provider] = result.status
                hits += result.hits
                listener.onProviderStatus(result.status)
                listener.onPartialResults(partialBuilder())
            } else if (completedProvider != null) {
                val error = completedProvider.error
                val status = ProviderStatus(
                    provider = completedProvider.provider,
                    state = if (error is InterruptedException) ProviderState.TIMEOUT else ProviderState.FAILED,
                    message = error?.message?.take(180) ?: error?.javaClass?.simpleName ?: "Provider fehlgeschlagen",
                )
                statuses[completedProvider.provider] = status
                listener.onProviderStatus(status)
            }
        }
        futures.forEach { (providerId, future) ->
            // A provider can finish exactly as the global deadline expires, after the last poll.
            // Consume that completed future here instead of leaving its UI state at SEARCHING.
            if (statuses[providerId]?.state != ProviderState.SEARCHING) return@forEach
            if (future.isDone) {
                val completedProvider = runCatching { future.get() }.getOrNull()
                val result = completedProvider?.result
                if (result != null) {
                    statuses[providerId] = result.status
                    hits += result.hits
                    listener.onProviderStatus(result.status)
                    listener.onPartialResults(partialBuilder())
                } else {
                    val error = completedProvider?.error
                    val status = ProviderStatus(
                        provider = providerId,
                        state = if (error is InterruptedException) ProviderState.TIMEOUT else ProviderState.FAILED,
                        message = error?.message?.take(180) ?: error?.javaClass?.simpleName ?: "Provider fehlgeschlagen",
                    )
                    statuses[providerId] = status
                    listener.onProviderStatus(status)
                }
            } else {
                future.cancel(true)
                val status = ProviderStatus(providerId, ProviderState.TIMEOUT, message = "Provider exceeded ${config.providerDeadlineMillis} ms")
                statuses[providerId] = status
                listener.onProviderStatus(status)
            }
        }
    }

    private data class ProviderCompletion(
        val provider: ProviderId,
        val result: ProviderResult? = null,
        val error: Throwable? = null,
    )

    private fun verifyTopFeeds(
        results: List<DiscoveryResult>,
        maxCount: Int,
        cancelled: AtomicBoolean,
        listener: DiscoveryListener,
        onUpdate: (Map<String, FeedVerification>) -> Unit
    ): Map<String, FeedVerification> {
        if (maxCount <= 0) return emptyMap()
        val urls = results.asSequence()
            .flatMap { it.targets.asSequence() }
            .filter {
                it.feedUrl != null || it.kind == TargetKind.RSS_AUDIO ||
                    it.kind == TargetKind.RSS_VIDEO || it.kind == TargetKind.ATOM_FEED
            }
            .mapNotNull { TextTools.normalizeUrl(it.feedUrl ?: it.url) }
            .distinct()
            .take(maxCount)
            .toList()
        if (urls.isEmpty()) return emptyMap()
        val completion = ExecutorCompletionService<Pair<String, FeedVerification>>(verificationExecutor)
        val futures = urls.map { url -> completion.submit { url to feedVerifier.verify(url, VerificationLevel.RECENT_EPISODES) } }
        val output = linkedMapOf<String, FeedVerification>()
        var completed = 0
        while (completed < urls.size && !cancelled.get()) {
            val future = completion.poll(12, TimeUnit.SECONDS) ?: break
            val pair = try { future.get() } catch (_: Throwable) { null }
            completed++
            if (pair != null) {
                output[pair.first] = pair.second
                TextTools.normalizeUrl(pair.second.finalUrl)?.let { output[it] = pair.second }
                listener.onVerificationProgress(completed, urls.size)
                onUpdate(output.toMap())
            }
        }
        futures.filter { !it.isDone }.forEach { it.cancel(true) }
        return output
    }

    override fun close() {
        coordinatorExecutor.shutdownNow()
        workerExecutor.shutdownNow()
        verificationExecutor.shutdownNow()
    }
}
