package com.lumione.player.search

import java.util.Locale

/** Coarse catalog taxonomy and explainable evidence scoring. No audio features are synthesized. */
data class TrackProfile(
    val title: String,
    val artist: String,
    val primaryGenre: String?,
    val subgenres: List<String>,
    val moodTags: List<String>,
    val confidence: Double,
    val provenance: String,
    val relatedArtists: List<String> = emptyList()
)

data class ScoreBreakdown(
    val relatedEvidencePoints: Double,
    val genreCompatibilityPoints: Double,
    val moodStylePoints: Double,
    val artistRelationshipPoints: Double,
    val contextualSimilarityPoints: Double,
    val penalties: Double,
    val totalScore: Double,
    val primaryExplanation: String
)

object MusicIntelligence {
    object GenreFamilies {
        const val ELECTRONIC_EDM = "electronic_edm"
        const val POP_MAINSTREAM = "pop_mainstream"
        const val HIPHOP_RAP = "hiphop_rap"
        const val ROCK_ALTERNATIVE = "rock_alternative"
        const val CHILL_AMBIENT = "chill_ambient"
        const val DEVOTIONAL_SPIRITUAL = "devotional_spiritual"
        const val INDIAN_REGIONAL_DESI = "indian_regional_desi"
        const val BOLLYWOOD_DESI = "bollywood_desi"
        const val CLASSICAL_ACOUSTIC = "classical_acoustic"
    }

    private data class ArtistStyle(val family: String, val subgenres: List<String>, val moods: List<String> = emptyList(), val related: List<String> = emptyList())
    private val artistStyles = mapOf(
        "alan walker" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("edm", "melodic_house", "dance_pop"), listOf("melancholic", "uplifting"), listOf("avicii", "the chainsmokers", "marshmello", "kygo", "martin garrix", "k-391", "alok", "illenium")),
        "avicii" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("edm", "progressive_house", "melodic_edm"), listOf("uplifting", "energetic"), listOf("alan walker", "kygo", "martin garrix", "david guetta")),
        "the chainsmokers" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("dance_pop", "edm", "electropop"), listOf("melancholic", "romantic"), listOf("alan walker", "marshmello", "halsey", "zedd")),
        "marshmello" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("future_bass", "edm", "dance_pop"), listOf("energetic"), listOf("the chainsmokers", "alan walker", "martin garrix")),
        "martin garrix" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("progressive_house", "edm"), listOf("energetic", "uplifting"), listOf("avicii", "tiesto", "david guetta", "alan walker")),
        "kygo" to ArtistStyle(GenreFamilies.ELECTRONIC_EDM, listOf("tropical_house", "melodic_house"), listOf("uplifting"), listOf("alan walker", "avicii", "gryffin")),
        "lady gaga" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "electropop", "pop"), related = listOf("madonna", "kylie minogue", "britney spears", "the weeknd", "dua lipa")),
        "jennifer lopez" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "latin_pop", "pop"), related = listOf("shakira", "pitbull", "madonna", "dua lipa", "calvin harris")),
        "madonna" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "electropop", "pop"), related = listOf("lady gaga", "kylie minogue", "britney spears")),
        "dua lipa" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "nu_disco"), listOf("energetic"), listOf("calvin harris", "lady gaga")),
        "passenger" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("indie_folk", "acoustic_pop"), listOf("melancholic", "romantic"), listOf("ed sheeran", "james blunt", "john mayer")),
        "ed sheeran" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("pop", "acoustic_pop"), listOf("romantic"), listOf("passenger", "john mayer")),
        "the weeknd" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("synthpop", "rnb", "pop"), listOf("dark", "melancholic"), listOf("dua lipa", "lady gaga")),
        "john legend" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("soul_pop", "rnb"), listOf("romantic", "melancholic"), listOf("adele", "ed sheeran")),
        "adele" to ArtistStyle(GenreFamilies.POP_MAINSTREAM, listOf("soul_pop", "pop"), listOf("melancholic", "romantic"), listOf("john legend", "ed sheeran")),
        "gulshan kumar" to ArtistStyle(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("bhajan", "aarti"), listOf("devotional", "spiritual"), listOf("hariharan", "anuradha paudwal", "anup jalota")),
        "anup jalota" to ArtistStyle(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("bhajan", "devotional"), listOf("devotional", "serene"), listOf("hariharan", "anuradha paudwal")),
        "hansraj raghuwanshi" to ArtistStyle(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("shiv_bhajan", "devotional"), listOf("devotional", "spiritual")),
        "mc square" to ArtistStyle(GenreFamilies.INDIAN_REGIONAL_DESI, listOf("haryanvi_hiphop", "desi_hiphop")),
        "arijit singh" to ArtistStyle(GenreFamilies.BOLLYWOOD_DESI, listOf("bollywood_romantic", "filmi"), listOf("romantic", "melancholic")),
        "kendrick lamar" to ArtistStyle(GenreFamilies.HIPHOP_RAP, listOf("conscious_hiphop", "rap")),
        "eminem" to ArtistStyle(GenreFamilies.HIPHOP_RAP, listOf("rap", "hiphop")),
        "brian eno" to ArtistStyle(GenreFamilies.CHILL_AMBIENT, listOf("ambient", "drone"), listOf("serene", "meditative")),
        "imagine dragons" to ArtistStyle(GenreFamilies.ROCK_ALTERNATIVE, listOf("alt_rock", "indie_rock")),
        "coldplay" to ArtistStyle(GenreFamilies.ROCK_ALTERNATIVE, listOf("alt_rock", "pop_rock"), listOf("uplifting", "emotional"))
    )

    private val compatibility = mapOf(
        GenreFamilies.ELECTRONIC_EDM to mapOf(GenreFamilies.ELECTRONIC_EDM to 1.0, GenreFamilies.POP_MAINSTREAM to .7, GenreFamilies.CHILL_AMBIENT to .35, GenreFamilies.HIPHOP_RAP to .3, GenreFamilies.ROCK_ALTERNATIVE to .25, GenreFamilies.BOLLYWOOD_DESI to .1, GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, GenreFamilies.INDIAN_REGIONAL_DESI to 0.0, GenreFamilies.CLASSICAL_ACOUSTIC to .05),
        GenreFamilies.POP_MAINSTREAM to mapOf(GenreFamilies.POP_MAINSTREAM to 1.0, GenreFamilies.ELECTRONIC_EDM to .7, GenreFamilies.BOLLYWOOD_DESI to .45, GenreFamilies.HIPHOP_RAP to .6, GenreFamilies.ROCK_ALTERNATIVE to .55, GenreFamilies.CHILL_AMBIENT to .45, GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, GenreFamilies.INDIAN_REGIONAL_DESI to .05, GenreFamilies.CLASSICAL_ACOUSTIC to .25),
        GenreFamilies.DEVOTIONAL_SPIRITUAL to mapOf(GenreFamilies.DEVOTIONAL_SPIRITUAL to 1.0, GenreFamilies.CLASSICAL_ACOUSTIC to .6, GenreFamilies.INDIAN_REGIONAL_DESI to .3, GenreFamilies.BOLLYWOOD_DESI to .2, GenreFamilies.CHILL_AMBIENT to .15, GenreFamilies.ELECTRONIC_EDM to 0.0, GenreFamilies.HIPHOP_RAP to 0.0, GenreFamilies.ROCK_ALTERNATIVE to 0.0, GenreFamilies.POP_MAINSTREAM to 0.0),
        GenreFamilies.CHILL_AMBIENT to mapOf(GenreFamilies.CHILL_AMBIENT to 1.0, GenreFamilies.CLASSICAL_ACOUSTIC to .75, GenreFamilies.POP_MAINSTREAM to .45, GenreFamilies.ELECTRONIC_EDM to .35, GenreFamilies.DEVOTIONAL_SPIRITUAL to .15),
        GenreFamilies.INDIAN_REGIONAL_DESI to mapOf(GenreFamilies.INDIAN_REGIONAL_DESI to 1.0, GenreFamilies.BOLLYWOOD_DESI to .6, GenreFamilies.DEVOTIONAL_SPIRITUAL to .3, GenreFamilies.ELECTRONIC_EDM to 0.0),
        GenreFamilies.BOLLYWOOD_DESI to mapOf(GenreFamilies.BOLLYWOOD_DESI to 1.0, GenreFamilies.POP_MAINSTREAM to .5, GenreFamilies.INDIAN_REGIONAL_DESI to .5, GenreFamilies.DEVOTIONAL_SPIRITUAL to .2, GenreFamilies.ELECTRONIC_EDM to .1),
        GenreFamilies.HIPHOP_RAP to mapOf(GenreFamilies.HIPHOP_RAP to 1.0, GenreFamilies.POP_MAINSTREAM to .6, GenreFamilies.ELECTRONIC_EDM to .3, GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0),
        GenreFamilies.ROCK_ALTERNATIVE to mapOf(GenreFamilies.ROCK_ALTERNATIVE to 1.0, GenreFamilies.POP_MAINSTREAM to .55, GenreFamilies.ELECTRONIC_EDM to .25, GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0),
        GenreFamilies.CLASSICAL_ACOUSTIC to mapOf(GenreFamilies.CLASSICAL_ACOUSTIC to 1.0, GenreFamilies.CHILL_AMBIENT to .75, GenreFamilies.DEVOTIONAL_SPIRITUAL to .6, GenreFamilies.ELECTRONIC_EDM to .05)
    )

    private val devotionalCue = Regex("(?i)\\b(chalisa|bhajan|aarti|stotra|stotram|mantra|satsang|kirtan|shiva|shiv|hanuman|krishna|ramayan|durga|ganesh|gayatri|bhakti|devotional|gurbani|shabad|hymn|worship|chant)\\b")
    private val edmCue = Regex("(?i)\\b(edm|electronic|house|dubstep|trance|techno|synthwave|rave|future bass)\\b")
    private val acousticCue = Regex("(?i)\\b(acoustic|indie folk|singer songwriter|piano ballad)\\b")
    private val moodCue = Regex("(?i)\\b(sad|heartbreak|melancholic|emotional|acoustic)\\b")

    fun extractArtistList(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank()) return emptyList()
        return rawArtist.split(Regex("[,/&|]|\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+", RegexOption.IGNORE_CASE))
            .map { cleanArtist(it) }.filter { it.isNotBlank() }
    }

    private fun cleanArtist(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b(vevo|topic|official|channel|records|music)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun buildTrackProfile(title: String, artist: String): TrackProfile {
        val clean = cleanArtist(artist)
        val parts = extractArtistList(artist)
        val match = parts.firstNotNullOfOrNull { artistStyles[it] }
        var family = match?.family
        var subgenres = match?.subgenres.orEmpty()
        var moods = match?.moods.orEmpty()
        var confidence = if (match != null) .65 else 0.0
        var provenance = if (match != null) "artist_taxonomy:$clean" else "metadata_missing"
        var related = match?.related.orEmpty()
        if (devotionalCue.containsMatchIn(title)) {
            family = GenreFamilies.DEVOTIONAL_SPIRITUAL
            subgenres = listOf("devotional", "bhajan")
            moods = listOf("devotional", "spiritual")
            confidence = .75
            provenance = "title_cue:devotional"
        } else if (family == null && edmCue.containsMatchIn(title)) {
            family = GenreFamilies.ELECTRONIC_EDM
            subgenres = listOf("electronic", "edm")
            confidence = .55
            provenance = "title_cue:electronic"
        } else if (family == null && acousticCue.containsMatchIn(title)) {
            family = GenreFamilies.CLASSICAL_ACOUSTIC
            subgenres = listOf("acoustic", "indie_folk")
            confidence = .55
            provenance = "title_cue:acoustic"
        }
        if (moods.isEmpty() && moodCue.containsMatchIn(title)) moods = listOf("melancholic")
        return TrackProfile(title, artist, family, subgenres, moods, confidence, provenance, related)
    }

    fun isIndianOrDevotionalProfile(title: String, artist: String): Boolean = buildTrackProfile(title, artist).primaryGenre in setOf(
        GenreFamilies.DEVOTIONAL_SPIRITUAL, GenreFamilies.INDIAN_REGIONAL_DESI, GenreFamilies.BOLLYWOOD_DESI
    )

    fun checkEligibilityGate(seedProfile: TrackProfile?, candidateProfile: TrackProfile): Pair<Boolean, String?> {
        val seed = seedProfile?.primaryGenre ?: return true to null
        val candidate = candidateProfile.primaryGenre ?: return true to null
        val score = compatibility[seed]?.get(candidate) ?: .5
        if (score == 0.0 && seedProfile.confidence >= .6 && candidateProfile.confidence >= .6) {
            return false to "Incompatible genre family: $candidate is disallowed for $seed"
        }
        return true to null
    }

    fun scoreCandidate(
        candidate: SearchResult,
        candProfile: TrackProfile,
        seedProfile: TrackProfile?,
        likedArtists: Set<String> = emptySet(),
        seedArtists: Set<String> = emptySet(),
        likedTrackKeys: Set<String> = emptySet(),
        skippedArtists: Set<String> = emptySet(),
        recentKeys: Set<String> = emptySet(),
        selectedSoFar: List<SearchResult> = emptyList()
    ): ScoreBreakdown {
        val sourcePoints = mapOf("watch_next" to 30.0, "related_artist_search" to 25.0, "same_artist_catalog" to 23.0, "genre_search" to 15.0)
        val sources = (candidate.retrievalSources + candidate.source).filter { it.isNotBlank() }.toSet()
        val relatedEvidence = sources.mapNotNull { sourcePoints[it] }.maxOrNull() ?: 0.0
        val family = seedProfile?.primaryGenre?.let { compatibility[it] }
        val familyMatch = candProfile.primaryGenre?.let { family?.get(it) }
        val subgenreMatch = seedProfile?.subgenres?.any { it in candProfile.subgenres } == true
        val genrePoints = if (familyMatch == null) 0.0 else minOf(25.0, familyMatch * 20.0 + if (subgenreMatch) 5.0 else 0.0)
        val sharedMoods = if (seedProfile != null && seedProfile.confidence >= .6 && candProfile.confidence >= .6) {
            seedProfile.moodTags.intersect(candProfile.moodTags.toSet()).size
        } else 0
        val moodPoints = if (sharedMoods == 0) 0.0 else minOf(20.0, 10.0 + 5.0 * minOf(sharedMoods, 2))
        val seedNames = (seedArtists + seedProfile?.artist.orEmpty()).map { cleanArtist(it) }.filter { it.isNotBlank() }.toSet()
        val candidateNames = extractArtistList(candidate.artist).toSet()
        val sameArtist = candidateNames.any { it in seedNames }
        val relatedNames = (seedProfile?.relatedArtists.orEmpty()).map { cleanArtist(it) }.toSet()
        val relatedArtist = candidateNames.any { it in relatedNames }
        val artistPoints = when { sameArtist -> 15.0; relatedArtist -> 10.0; else -> 0.0 }
        val likedNames = likedArtists.map { cleanArtist(it) }.toSet()
        val likedArtistMatch = candidateNames.any { it in likedNames }
        val candidateKey = TrackNormalizer.extractCanonicalKey(candidate.title, candidate.artist).lowercase(Locale.ROOT)
        val likedKeyMatch = likedTrackKeys.any { it.lowercase(Locale.ROOT) == candidateKey }
        val contextPoints = when {
            candidate.recordingRelationship || likedKeyMatch -> 10.0
            likedArtistMatch -> 5.0
            else -> 0.0
        }
        val duplicate = selectedSoFar.any { TrackNormalizer.extractCanonicalKey(it.title, it.artist) == TrackNormalizer.extractCanonicalKey(candidate.title, candidate.artist) }
        val skipped = candidateNames.any { candidateName -> skippedArtists.any { cleanArtist(it) == candidateName } }
        val recent = recentKeys.any { it.lowercase(Locale.ROOT) == candidateKey }
        val penalty = when {
            duplicate -> 100.0
            skipped -> 25.0
            recent -> 15.0
            TrackNormalizer.detectModifier(candidate.title) != null -> 12.0
            else -> 0.0
        }
        val total = (relatedEvidence + genrePoints + moodPoints + artistPoints + contextPoints - penalty).coerceIn(0.0, 100.0)
        return ScoreBreakdown(relatedEvidence, genrePoints, moodPoints, artistPoints, contextPoints, -penalty, total,
            "evidence $relatedEvidence, genre $genrePoints, mood/style $moodPoints, artist $artistPoints")
    }
}
