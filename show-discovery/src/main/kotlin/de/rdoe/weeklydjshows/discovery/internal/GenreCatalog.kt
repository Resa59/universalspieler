package de.rdoe.weeklydjshows.discovery.internal

object GenreCatalog {
    private val aliases: Map<String, Set<String>> = linkedMapOf(
        "Electronic" to setOf("electronic", "electronica", "elektronisch", "edm", "dance music"),
        "House" to setOf("house", "deep house", "tech house", "progressive house", "melodic house", "afro house", "organic house"),
        "Techno" to setOf("techno", "melodic techno", "industrial techno", "hard techno", "minimal techno", "detroit techno"),
        "Trance" to setOf("trance", "progressive trance", "uplifting trance", "psytrance", "psy trance", "goa trance"),
        "Drum & Bass" to setOf("drum and bass", "drum & bass", "dnb", "liquid dnb", "neurofunk", "jungle"),
        "Hard Dance" to setOf("hardstyle", "hardcore", "gabber", "hard dance", "uptempo"),
        "Dubstep" to setOf("dubstep", "riddim", "bass music"),
        "Disco" to setOf("disco", "nu disco", "italo disco"),
        "Hip-Hop" to setOf("hip hop", "hip-hop", "rap", "turntablism"),
        "Reggae" to setOf("reggae", "dancehall", "dub"),
        "Ambient" to setOf("ambient", "downtempo", "chillout", "chill out"),
        "Experimental" to setOf("experimental", "idm", "leftfield"),
        "Funk & Soul" to setOf("funk", "soul", "boogie"),
        "Jazz" to setOf("jazz", "nu jazz"),
        "Rock" to setOf("rock", "indie rock", "alternative rock"),
        "Pop" to setOf("pop", "dance pop")
    )

    private val musicWords = aliases.values.flatten().map { TextTools.normalizeText(it) }.toSet() + setOf(
        "dj", "dj mix", "radio show", "radioshow", "mixshow", "club music", "music podcast"
    ).map { TextTools.normalizeText(it) }

    fun detect(text: String?): Set<String> {
        val normalized = TextTools.normalizeText(text)
        if (normalized.isBlank()) return emptySet()
        return aliases.filterValues { values -> values.any { containsPhrase(normalized, TextTools.normalizeText(it)) } }.keys
    }

    fun isMusicGenre(value: String?): Boolean {
        val normalized = TextTools.normalizeText(value)
        if (normalized.isBlank()) return false
        return musicWords.any { containsPhrase(normalized, it) || containsPhrase(it, normalized) }
    }

    fun canonicalGenres(): Set<String> = aliases.keys

    private fun containsPhrase(text: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        return " $text ".contains(" $phrase ")
    }
}
