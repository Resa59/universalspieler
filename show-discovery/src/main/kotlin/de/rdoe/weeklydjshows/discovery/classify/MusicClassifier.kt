package de.rdoe.weeklydjshows.discovery.classify

import de.rdoe.weeklydjshows.discovery.internal.GenreCatalog
import de.rdoe.weeklydjshows.discovery.internal.TextTools
import de.rdoe.weeklydjshows.discovery.model.*

class MusicClassifier(
    private val likelyThreshold: Double = 0.58
) {
    private val strongPhrases = linkedMapOf(
        "dj mix" to 0.34,
        "radio show" to 0.30,
        "radioshow" to 0.30,
        "mixshow" to 0.32,
        "podcast mix" to 0.28,
        "weekly mix" to 0.25,
        "guest mix" to 0.25,
        "live mix" to 0.20,
        "dj set" to 0.30,
        "club mix" to 0.24,
        "essential mix" to 0.30
    )

    private val mediumPhrases = linkedMapOf(
        "sessions" to 0.10,
        "session" to 0.07,
        "episode" to 0.03,
        "weekly" to 0.05,
        "radio" to 0.08,
        "dance" to 0.07,
        "club" to 0.07,
        "electronic music" to 0.16,
        "new music" to 0.08,
        "mix" to 0.08
    )

    private val negativePhrases = linkedMapOf(
        "music business" to -0.25,
        "music industry" to -0.22,
        "music history" to -0.17,
        "music theory" to -0.18,
        "interview podcast" to -0.16,
        "news podcast" to -0.13,
        "talk show" to -0.12,
        "spoken word" to -0.18
    )

    fun classify(hits: List<SourceHit>, feed: FeedVerification? = null): MusicClassification {
        val evidence = mutableListOf<MusicEvidence>()
        val declaredProviders = hits.filter { it.declaredMusic }.map { it.provider }.toMutableSet()
        if (feed?.podcastMedium.equals("music", true)) {
            declaredProviders += ProviderId.WEBSITE
            evidence += MusicEvidence("podcast:medium=music", 1.0, "feed")
        }
        hits.filter { it.declaredMusic }.forEach {
            evidence += MusicEvidence(it.declaredMusicReason ?: "Provider declared music", 1.0, it.provider.name)
        }

        val combinedCategories = (hits.flatMap { it.categories } + feed?.categories.orEmpty()).toSet()
        val textFields = mutableListOf<Pair<String, Double>>()
        hits.forEach { hit ->
            textFields += hit.title to 1.0
            hit.publisher?.let { textFields += it to 0.45 }
            hit.description?.let { textFields += it to 0.65 }
            hit.categories.forEach { textFields += it to 1.15 }
        }
        feed?.episodeTitles?.take(12)?.forEach { textFields += it to 0.55 }
        feed?.categories?.forEach { textFields += it to 1.2 }

        var score = if (declaredProviders.isNotEmpty()) 1.0 else 0.08
        var hasStrongSignal = false
        for ((raw, fieldWeight) in textFields) {
            val normalized = " ${TextTools.normalizeText(raw)} "
            for ((phrase, weight) in strongPhrases) {
                if (normalized.contains(" ${TextTools.normalizeText(phrase)} ")) {
                    val applied = weight * fieldWeight
                    score += applied
                    evidence += MusicEvidence("Matched '$phrase'", applied, raw.take(80))
                    hasStrongSignal = true
                }
            }
            for ((phrase, weight) in mediumPhrases) {
                if (normalized.contains(" ${TextTools.normalizeText(phrase)} ")) {
                    val applied = weight * fieldWeight
                    score += applied
                    evidence += MusicEvidence("Matched '$phrase'", applied, raw.take(80))
                }
            }
            for ((phrase, weight) in negativePhrases) {
                if (normalized.contains(" ${TextTools.normalizeText(phrase)} ")) {
                    val reduction = if (hasStrongSignal) weight * 0.35 else weight
                    score += reduction * fieldWeight
                    evidence += MusicEvidence("Negative context '$phrase'", reduction * fieldWeight, raw.take(80))
                }
            }
        }

        val genres = mutableSetOf<String>()
        textFields.forEach { genres += GenreCatalog.detect(it.first) }
        combinedCategories.forEach { genres += GenreCatalog.detect(it) }
        if (genres.isNotEmpty()) {
            val genreBonus = (0.10 + genres.size.coerceAtMost(3) * 0.05)
            score += genreBonus
            evidence += MusicEvidence("Recognized genres: ${genres.joinToString()}", genreBonus, "classifier")
        }

        val episodeSignals = feed?.episodeTitles.orEmpty().count { title ->
            val normalized = TextTools.normalizeText(title)
            strongPhrases.keys.any { normalized.contains(TextTools.normalizeText(it)) } ||
                Regex("\\b(ep|episode|show|mix)\\s*[#-]?\\s*\\d+\\b").containsMatchIn(normalized)
        }
        if (episodeSignals >= 2) {
            val bonus = (0.08 + episodeSignals.coerceAtMost(6) * 0.025)
            score += bonus
            evidence += MusicEvidence("Recurring mix/show episode pattern", bonus, "feed episodes")
        }

        val probability = if (declaredProviders.isNotEmpty()) 1.0 else score.coerceIn(0.0, 0.99)
        val group = when {
            declaredProviders.isNotEmpty() -> ResultGroup.DECLARED_MUSIC
            probability >= likelyThreshold -> ResultGroup.LIKELY_DJ_OR_MUSIC_SHOW
            else -> ResultGroup.OTHER
        }
        return MusicClassification(
            group = group,
            probability = probability,
            declaredBy = declaredProviders,
            genres = genres,
            evidence = evidence.sortedByDescending { kotlin.math.abs(it.weight) }.take(16)
        )
    }
}
