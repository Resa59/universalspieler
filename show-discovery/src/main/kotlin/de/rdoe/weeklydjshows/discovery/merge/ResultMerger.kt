package de.rdoe.weeklydjshows.discovery.merge

import de.rdoe.weeklydjshows.discovery.classify.MusicClassifier
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.model.*
import kotlin.math.ln

class ResultMerger(
    private val musicClassifier: MusicClassifier = MusicClassifier()
) {
    private val providerTrust = mapOf(
        ProviderId.PODCAST_INDEX to 0.95,
        ProviderId.APPLE_PODCASTS to 0.90,
        ProviderId.FEEDLY to 0.74,
        ProviderId.GPODDER to 0.72,
        ProviderId.MIXCLOUD to 0.78,
        ProviderId.YOUTUBE to 0.76,
        ProviderId.SPOTIFY to 0.72,
        ProviderId.SOUNDCLOUD to 0.72,
        ProviderId.WEBSITE to 0.84
    )

    fun merge(
        hits: List<SourceHit>,
        query: String? = null,
        verifications: Map<String, FeedVerification> = emptyMap(),
        musicMode: MusicMode = MusicMode.ALL,
        includePlatformResults: Boolean = true
    ): List<DiscoveryResult> {
        if (hits.isEmpty()) return emptyList()
        val union = UnionFind(hits.size)
        val textIdentityKeys = hits.map(::exactTextIdentityKey)
        val ambiguousTextIdentityKeys = hits.indices
            .mapNotNull { index -> textIdentityKeys[index]?.let { it to hits[index].provider } }
            .groupBy({ it.first }, { it.second })
            .filterValues { providers -> providers.groupingBy { it }.eachCount().any { it.value > 1 } }
            .keys
        for (i in hits.indices) {
            for (j in i + 1 until hits.size) {
                val textKeyI = textIdentityKeys[i]
                val textKeyJ = textIdentityKeys[j]
                val exactCrossProviderIdentity = hits[i].provider != hits[j].provider &&
                    textKeyI != null &&
                    textKeyI == textKeyJ &&
                    textKeyI !in ambiguousTextIdentityKeys
                if (hasHardIdentityMatch(hits[i], hits[j]) || exactCrossProviderIdentity) union.union(i, j)
            }
        }
        val groups = hits.indices.groupBy { union.find(it) }.values
        val resultComparator = if (query.isNullOrBlank()) {
            compareBy<DiscoveryResult> { groupOrder(it.music.group) }
                .thenByDescending { it.relevanceScore }
                .thenBy { it.title.lowercase() }
        } else {
            // A concrete name search must not bury an exact title merely because the provider did
            // not explicitly tag it as music. Classification remains part of ranking, but only
            // after lexical match quality. This is important for names such as "The Anjunadeep
            // Edition", which contain no generic DJ/radio keyword themselves.
            compareBy<DiscoveryResult> { queryMatchTier(query, it) }
                .thenBy { groupOrder(it.music.group) }
                .thenByDescending { it.relevanceScore }
                .thenBy { it.title.lowercase() }
        }
        return groups.map { indices ->
            buildResult(indices.map { hits[it] }, query, verifications)
        }.filter { result ->
            val platformAllowed = includePlatformResults || result.targets.any { it.requirement == IntegrationRequirement.DIRECT_RSS_AUDIO }
            val musicAllowed = when (musicMode) {
                MusicMode.ALL -> true
                MusicMode.DJ_AND_MUSIC -> result.music.group != ResultGroup.OTHER
                MusicMode.DECLARED_MUSIC_ONLY -> result.music.group == ResultGroup.DECLARED_MUSIC
            }
            result.targets.isNotEmpty() && platformAllowed && musicAllowed
        }.sortedWith(resultComparator)
    }

    private fun buildResult(
        hits: List<SourceHit>,
        query: String?,
        verifications: Map<String, FeedVerification>
    ): DiscoveryResult {
        val rankedHits = hits.sortedByDescending { completeness(it) + (providerTrust[it.provider] ?: 0.5) }
        val representative = rankedHits.first()
        val allTargets = hits.flatMap { it.targets }
            .distinctBy { "${it.kind}|${TextTools.normalizeUrl(it.url) ?: it.url}|${it.stableId}" }
        val allRssTargets = allTargets.filter { it.kind in RSS_TARGET_KINDS }
        // A verified 404/410, invalid XML document, etc. is not a useful subscription choice.
        // Transport failures without an HTTP response and retryable HTTP statuses stay visible:
        // a temporary offline/rate-limit/server problem must not masquerade as a dead feed.
        val rssTargets = allRssTargets.filterNot { isConfirmedDeadFeed(it, verifications) }
        // A directory disagreement about two RSS URLs is useful internally, but it must not turn
        // into several visually identical "RSS · Audio" choices. Keep the best verified RSS
        // target and expose the genuinely different platform alternatives beside it.
        val bestRssTarget = rssTargets.maxByOrNull { targetRank(it, verifications) }
        val targets = allTargets.filterNot { it.kind in RSS_TARGET_KINDS } + listOfNotNull(bestRssTarget)
        val feedVerification = rssTargets.asSequence()
            .mapNotNull { target ->
                val key = TextTools.normalizeUrl(target.feedUrl ?: target.url)
                if (key != null) verifications[key] else null
            }
            .sortedByDescending { verificationRank(it) }
            .firstOrNull()
        val music = musicClassifier.classify(hits, feedVerification)
        val preferredTarget = targets.maxByOrNull { targetRank(it, verifications) }
        val rssUrls = rssTargets
            .mapNotNull { TextTools.normalizeUrl(it.feedUrl ?: it.url) }
            .distinct()
        val warnings = mutableListOf<String>()
        if (allRssTargets.size > rssTargets.size) warnings += "Verified dead or invalid feeds were removed"
        if (rssUrls.size > 1) warnings += "Multiple distinct feed URLs were retained; they may be regional, migrated or edited editions"
        if (preferredTarget?.requirement == IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED) warnings += "No directly subscribable audio RSS feed was found; the host app needs a platform adapter/player"
        if (preferredTarget?.requirement == IntegrationRequirement.FEED_AND_PLATFORM_PLAYER) warnings += "Updates can be followed as a feed, but playback requires a platform-aware player"

        val title = firstMeaningful(rankedHits.map { it.title }) ?: representative.title
        val publisher = firstMeaningful(rankedHits.mapNotNull { it.publisher })
        val description = rankedHits.mapNotNull { it.description }.maxByOrNull { it.length }
        val artwork = firstMeaningful(rankedHits.mapNotNull { it.artworkUrl }) ?: feedVerification?.imageUrl
        val website = firstMeaningful(rankedHits.mapNotNull { it.websiteUrl }) ?: feedVerification?.websiteUrl
        val language = firstMeaningful(rankedHits.mapNotNull { it.language })
        val categories = (hits.flatMap { it.categories } + feedVerification?.categories.orEmpty()).toSet()
        val relevance = score(query, title, publisher, hits, music, feedVerification, preferredTarget)
        val id = TextTools.stableId(
            rssUrls.firstOrNull(),
            preferredTarget?.stableId,
            title,
            publisher
        )
        return DiscoveryResult(
            internalId = id,
            title = title,
            publisher = publisher,
            description = description,
            artworkUrl = artwork,
            websiteUrl = website,
            language = language,
            categories = categories,
            sources = hits.map { it.provider }.toSet(),
            sourceHits = hits,
            targets = targets.sortedByDescending { targetRank(it, verifications) },
            preferredTarget = preferredTarget,
            music = music,
            feedVerification = feedVerification,
            relevanceScore = relevance,
            mergeWarnings = warnings
        )
    }

    private fun hasHardIdentityMatch(a: SourceHit, b: SourceHit): Boolean {
        val feedA = TextTools.normalizeUrl(a.feedUrl)
        val feedB = TextTools.normalizeUrl(b.feedUrl)
        if (feedA != null && feedA == feedB) return true

        if (a.provider == b.provider && a.providerItemId != null && a.providerItemId == b.providerItemId) return true
        val sharedStableId = a.stableIds.entries.any { (key, value) -> b.stableIds[key] == value }
        return sharedStableId
    }

    /**
     * Search results are containers, not tracks. A shared host, artwork or merely similar wording
     * is not identity. Across independent providers we retain the useful source chooser only when
     * title *and* publisher are exactly equal after normalization. If a provider contributes that
     * same text identity more than once, [merge] marks the key ambiguous and disables this fallback
     * entirely, preventing one RSS hit from transitively gluing duplicate platform playlists
     * together.
     */
    private fun exactTextIdentityKey(hit: SourceHit): String? {
        val title = TextTools.normalizeText(hit.title)
        val publisher = TextTools.normalizeText(hit.publisher)
        if (title.length < 4 || publisher.length < 3) return null
        return "$title\u001f$publisher"
    }

    private fun score(
        query: String?,
        title: String,
        publisher: String?,
        hits: List<SourceHit>,
        music: MusicClassification,
        verification: FeedVerification?,
        preferredTarget: IntegrationTarget?
    ): Double {
        val textScore = if (query.isNullOrBlank()) 0.55 else maxOf(
            TextTools.similarity(query, title),
            TextTools.similarity(query, publisher) * 0.86,
            hits.maxOfOrNull { TextTools.similarity(query, "${it.title} ${it.publisher.orEmpty()} ${it.description.orEmpty()}") } ?: 0.0
        )
        val sourceTrust = hits.maxOfOrNull { providerTrust[it.provider] ?: 0.5 } ?: 0.5
        val sourceBonus = ((hits.map { it.provider }.distinct().size - 1).coerceAtLeast(0) * 0.035).coerceAtMost(0.14)
        val directBonus = when (preferredTarget?.requirement) {
            IntegrationRequirement.DIRECT_RSS_AUDIO -> 0.16
            IntegrationRequirement.FEED_AND_PLATFORM_PLAYER -> 0.08
            IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED -> 0.01
            else -> 0.0
        }
        val verificationBonus = when (verification?.status) {
            FeedStatus.VALID_AUDIO_FEED -> 0.16
            FeedStatus.VALID_VIDEO_FEED -> 0.08
            FeedStatus.VALID_FEED_WITHOUT_MEDIA -> -0.09
            FeedStatus.UNREACHABLE, FeedStatus.INVALID_XML, FeedStatus.NOT_A_FEED -> -0.16
            else -> 0.0
        }
        val activityBonus = when (verification?.activityStatus) {
            ActivityStatus.ACTIVE_RECENT -> 0.10
            ActivityStatus.ACTIVE_REGULAR -> 0.08
            ActivityStatus.ACTIVE_IRREGULAR -> 0.04
            ActivityStatus.LIKELY_DISCONTINUED -> -0.12
            ActivityStatus.INACTIVE_LONG -> -0.08
            else -> 0.0
        }
        val musicBonus = when (music.group) {
            ResultGroup.DECLARED_MUSIC -> 0.18
            ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW -> 0.12 * music.probability
            ResultGroup.OTHER -> 0.0
        }
        val popularity = hits.mapNotNull { it.providerPopularity }.maxOrNull()?.coerceAtLeast(0.0)
        val popularityBonus = popularity?.let { (ln(it + 1.0) / 20.0).coerceAtMost(0.09) } ?: 0.0
        return (textScore * 0.48 + sourceTrust * 0.22 + sourceBonus + directBonus + verificationBonus + activityBonus + musicBonus + popularityBonus)
            .coerceIn(0.0, 1.5)
    }

    private fun targetRank(target: IntegrationTarget, verifications: Map<String, FeedVerification>): Int {
        val verification = TextTools.normalizeUrl(target.feedUrl ?: target.url)?.let { verifications[it] }
        return when {
            verification?.status == FeedStatus.VALID_AUDIO_FEED -> 120
            target.requirement == IntegrationRequirement.DIRECT_RSS_AUDIO -> 100
            target.kind == TargetKind.YOUTUBE_CHANNEL && target.feedUrl != null -> 82
            target.requirement == IntegrationRequirement.FEED_AND_PLATFORM_PLAYER -> 75
            target.kind == TargetKind.SPOTIFY_SHOW -> 60
            target.kind == TargetKind.MIXCLOUD_PROFILE -> 58
            target.kind == TargetKind.SOUNDCLOUD_PROFILE -> 56
            target.kind == TargetKind.YOUTUBE_PLAYLIST -> 54
            target.kind == TargetKind.SPOTIFY_PLAYLIST -> 52
            target.requirement == IntegrationRequirement.PLATFORM_ADAPTER_REQUIRED -> 45
            target.requirement == IntegrationRequirement.RESOLUTION_REQUIRED -> 25
            else -> 10
        }
    }

    private fun verificationRank(verification: FeedVerification): Int = when (verification.status) {
        FeedStatus.VALID_AUDIO_FEED -> 5
        FeedStatus.VALID_VIDEO_FEED -> 4
        FeedStatus.VALID_FEED_WITHOUT_MEDIA -> 2
        else -> 0
    }

    private fun isConfirmedDeadFeed(
        target: IntegrationTarget,
        verifications: Map<String, FeedVerification>,
    ): Boolean {
        val key = TextTools.normalizeUrl(target.feedUrl ?: target.url) ?: return false
        val verification = verifications[key] ?: return false
        return when (verification.status) {
            FeedStatus.INVALID_XML, FeedStatus.NOT_A_FEED, FeedStatus.UNSUPPORTED_URL -> true
            FeedStatus.UNREACHABLE -> {
                val status = verification.httpStatus ?: return false
                status in 400..499 && status !in RETRYABLE_HTTP_STATUSES
            }
            else -> false
        }
    }

    private fun completeness(hit: SourceHit): Double = listOf(
        hit.publisher,
        hit.description,
        hit.feedUrl,
        hit.websiteUrl,
        hit.artworkUrl,
        hit.language
    ).count { !it.isNullOrBlank() } * 0.08 + hit.categories.size.coerceAtMost(4) * 0.02

    private fun firstMeaningful(values: List<String>): String? = values.firstOrNull { it.isNotBlank() }

    private fun groupOrder(group: ResultGroup): Int = when (group) {
        ResultGroup.DECLARED_MUSIC -> 0
        ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW -> 1
        ResultGroup.OTHER -> 2
    }

    private fun queryMatchTier(query: String, result: DiscoveryResult): Int {
        val normalizedQuery = TextTools.normalizeText(query)
        val normalizedTitle = TextTools.normalizeText(result.title)
        val titleSimilarity = TextTools.similarity(query, result.title)
        val publisherSimilarity = TextTools.similarity(query, result.publisher)
        return when {
            normalizedQuery.isNotBlank() && normalizedQuery == normalizedTitle -> 0
            titleSimilarity >= 0.92 -> 1
            titleSimilarity >= 0.78 || publisherSimilarity >= 0.90 -> 2
            else -> 3
        }
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)
        fun find(value: Int): Int {
            if (parent[value] != value) parent[value] = find(parent[value])
            return parent[value]
        }
        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return
            when {
                rank[rootA] < rank[rootB] -> parent[rootA] = rootB
                rank[rootA] > rank[rootB] -> parent[rootB] = rootA
                else -> { parent[rootB] = rootA; rank[rootA]++ }
            }
        }
    }

    private companion object {
        val RSS_TARGET_KINDS = setOf(TargetKind.RSS_AUDIO, TargetKind.RSS_VIDEO, TargetKind.ATOM_FEED)
        val RETRYABLE_HTTP_STATUSES = setOf(408, 425, 429)
    }
}
