package com.lumione.player.search

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * LumiOne Music Intelligence & Acoustic Profiling Engine (Kotlin / Android)
 *
 * Implements:
 * 1. Embeat-inspired 7-dimensional normalized acoustic vector space:
 *    [danceability-0.5, energy-0.5, valence-0.5, acousticness-0.5, liveness-0.5, speechiness-0.5, instrumentalness-0.5]
 * 2. Genre taxonomy with hierarchical compatibility matrix.
 * 3. Mood tags (melancholic, uplifting, energetic, chill, romantic, dark, serene).
 * 4. Genre & Mood Eligibility Gate (strict pre-scoring rejection of incompatible styles).
 * 5. Weighted 0–100 point-scoring system with explainable score breakdown.
 */
data class TrackProfile(
    val title: String,
    val artist: String,
    val primaryGenre: String,
    val subgenres: List<String>,
    val moodTags: List<String>,
    val energy: Double,
    val valence: Double,
    val danceability: Double,
    val acousticness: Double,
    val acousticVector: DoubleArray,
    val confidence: Double,
    val provenance: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackProfile
        return title == other.title && artist == other.artist && primaryGenre == other.primaryGenre
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + primaryGenre.hashCode()
        return result
    }
}

data class ScoreBreakdown(
    val acousticSimilarityPoints: Double,
    val genreCompatibilityPoints: Double,
    val moodEnergyPoints: Double,
    val preferencePoints: Double,
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

    // Genre Compatibility Matrix [0.0 = hard rejection, 1.0 = identical family]
    private val GENRE_COMPATIBILITY_MATRIX = mapOf(
        GenreFamilies.ELECTRONIC_EDM to mapOf(
            GenreFamilies.ELECTRONIC_EDM to 1.0,
            GenreFamilies.POP_MAINSTREAM to 0.75,
            GenreFamilies.CHILL_AMBIENT to 0.40,
            GenreFamilies.HIPHOP_RAP to 0.35,
            GenreFamilies.ROCK_ALTERNATIVE to 0.30,
            GenreFamilies.BOLLYWOOD_DESI to 0.15,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, // HARD REJECTION
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.0, // HARD REJECTION
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.05
        ),
        GenreFamilies.POP_MAINSTREAM to mapOf(
            GenreFamilies.POP_MAINSTREAM to 1.0,
            GenreFamilies.ELECTRONIC_EDM to 0.75,
            GenreFamilies.BOLLYWOOD_DESI to 0.50,
            GenreFamilies.HIPHOP_RAP to 0.60,
            GenreFamilies.ROCK_ALTERNATIVE to 0.55,
            GenreFamilies.CHILL_AMBIENT to 0.45,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, // HARD REJECTION
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.10,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.20
        ),
        GenreFamilies.DEVOTIONAL_SPIRITUAL to mapOf(
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 1.0,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.60,
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.40,
            GenreFamilies.BOLLYWOOD_DESI to 0.25,
            GenreFamilies.CHILL_AMBIENT to 0.20,
            GenreFamilies.ELECTRONIC_EDM to 0.0, // HARD REJECTION
            GenreFamilies.HIPHOP_RAP to 0.0,     // HARD REJECTION
            GenreFamilies.ROCK_ALTERNATIVE to 0.0,
            GenreFamilies.POP_MAINSTREAM to 0.0
        ),
        GenreFamilies.CHILL_AMBIENT to mapOf(
            GenreFamilies.CHILL_AMBIENT to 1.0,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.70,
            GenreFamilies.POP_MAINSTREAM to 0.45,
            GenreFamilies.ELECTRONIC_EDM to 0.40,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.20,
            GenreFamilies.HIPHOP_RAP to 0.20,
            GenreFamilies.ROCK_ALTERNATIVE to 0.20,
            GenreFamilies.BOLLYWOOD_DESI to 0.20,
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.05
        ),
        GenreFamilies.INDIAN_REGIONAL_DESI to mapOf(
            GenreFamilies.INDIAN_REGIONAL_DESI to 1.0,
            GenreFamilies.BOLLYWOOD_DESI to 0.60,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.40,
            GenreFamilies.POP_MAINSTREAM to 0.20,
            GenreFamilies.ELECTRONIC_EDM to 0.0, // HARD REJECTION
            GenreFamilies.HIPHOP_RAP to 0.25,
            GenreFamilies.ROCK_ALTERNATIVE to 0.05,
            GenreFamilies.CHILL_AMBIENT to 0.05,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.15
        ),
        GenreFamilies.BOLLYWOOD_DESI to mapOf(
            GenreFamilies.BOLLYWOOD_DESI to 1.0,
            GenreFamilies.POP_MAINSTREAM to 0.55,
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.50,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.25,
            GenreFamilies.CHILL_AMBIENT to 0.30,
            GenreFamilies.ELECTRONIC_EDM to 0.15,
            GenreFamilies.HIPHOP_RAP to 0.35,
            GenreFamilies.ROCK_ALTERNATIVE to 0.20,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.30
        ),
        GenreFamilies.HIPHOP_RAP to mapOf(
            GenreFamilies.HIPHOP_RAP to 1.0,
            GenreFamilies.POP_MAINSTREAM to 0.60,
            GenreFamilies.ELECTRONIC_EDM to 0.30,
            GenreFamilies.ROCK_ALTERNATIVE to 0.25,
            GenreFamilies.BOLLYWOOD_DESI to 0.30,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, // HARD REJECTION
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.20,
            GenreFamilies.CHILL_AMBIENT to 0.20,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.05
        ),
        GenreFamilies.ROCK_ALTERNATIVE to mapOf(
            GenreFamilies.ROCK_ALTERNATIVE to 1.0,
            GenreFamilies.POP_MAINSTREAM to 0.55,
            GenreFamilies.ELECTRONIC_EDM to 0.25,
            GenreFamilies.CLASSICAL_ACOUSTIC to 0.35,
            GenreFamilies.HIPHOP_RAP to 0.25,
            GenreFamilies.CHILL_AMBIENT to 0.20,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.0, // HARD REJECTION
            GenreFamilies.BOLLYWOOD_DESI to 0.15,
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.0
        ),
        GenreFamilies.CLASSICAL_ACOUSTIC to mapOf(
            GenreFamilies.CLASSICAL_ACOUSTIC to 1.0,
            GenreFamilies.CHILL_AMBIENT to 0.75,
            GenreFamilies.DEVOTIONAL_SPIRITUAL to 0.60,
            GenreFamilies.POP_MAINSTREAM to 0.30,
            GenreFamilies.BOLLYWOOD_DESI to 0.30,
            GenreFamilies.ROCK_ALTERNATIVE to 0.30,
            GenreFamilies.ELECTRONIC_EDM to 0.05,
            GenreFamilies.HIPHOP_RAP to 0.05,
            GenreFamilies.INDIAN_REGIONAL_DESI to 0.10
        )
    )

    private data class ArtistMetadata(
        val family: String,
        val subgenres: List<String>,
        val energy: Double,
        val valence: Double,
        val danceability: Double,
        val acousticness: Double,
        val mood: List<String>
    )

    private val ARTIST_GENRE_MAP = mapOf(
        // Electronic / EDM
        "alan walker" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("edm", "melodic_house", "dance_pop"), 0.72, 0.35, 0.65, 0.15, listOf("melancholic", "uplifting")),
        "avicii" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("edm", "progressive_house", "melodic_edm"), 0.78, 0.55, 0.60, 0.20, listOf("uplifting", "energetic")),
        "the chainsmokers" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("dance_pop", "edm", "electropop"), 0.70, 0.45, 0.70, 0.18, listOf("melancholic", "romantic")),
        "marshmello" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("future_bass", "edm", "dance_pop"), 0.75, 0.60, 0.72, 0.15, listOf("happy", "energetic")),
        "martin garrix" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("progressive_house", "edm"), 0.85, 0.50, 0.65, 0.10, listOf("energetic", "uplifting")),
        "calvin harris" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("dance_pop", "house", "edm"), 0.82, 0.65, 0.75, 0.12, listOf("energetic", "happy")),
        "dj snake" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("trap_edm", "dance_pop"), 0.80, 0.50, 0.75, 0.15, listOf("energetic")),
        "kygo" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("tropical_house", "melodic_house"), 0.65, 0.55, 0.68, 0.25, listOf("chill", "uplifting")),
        "alok" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("brazilian_bass", "edm", "house"), 0.80, 0.50, 0.75, 0.12, listOf("energetic")),
        "k-391" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("electro_house", "edm"), 0.78, 0.45, 0.65, 0.15, listOf("uplifting", "melancholic")),
        "illenium" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("future_bass", "melodic_dubstep"), 0.72, 0.30, 0.55, 0.20, listOf("melancholic", "emotional")),
        "zedd" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("electro_house", "dance_pop"), 0.82, 0.60, 0.70, 0.12, listOf("energetic", "uplifting")),
        "tiesto" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("house", "edm"), 0.85, 0.55, 0.75, 0.08, listOf("energetic")),
        "david guetta" to ArtistMetadata(GenreFamilies.ELECTRONIC_EDM, listOf("dance_pop", "edm", "house"), 0.84, 0.65, 0.74, 0.10, listOf("energetic", "happy")),

        // Pop / Electropop
        "the weeknd" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("synthpop", "contemporary_rnb", "pop"), 0.75, 0.50, 0.68, 0.15, listOf("dark", "melancholic", "energetic")),
        "dua lipa" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "nu_disco"), 0.80, 0.70, 0.78, 0.10, listOf("energetic", "happy")),
        "zayn" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("electropop", "pop", "rnb"), 0.68, 0.38, 0.60, 0.22, listOf("melancholic", "emotional")),
        "sia" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("electropop", "pop"), 0.72, 0.52, 0.64, 0.18, listOf("uplifting", "emotional")),
        "passenger" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("indie_folk", "acoustic_pop"), 0.45, 0.40, 0.55, 0.75, listOf("melancholic", "romantic")),
        "rihanna" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("pop", "dance_pop", "rnb"), 0.70, 0.55, 0.70, 0.20, listOf("uplifting")),
        "akon" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("dance_pop", "rnb"), 0.75, 0.65, 0.75, 0.18, listOf("happy", "energetic")),
        "taylor swift" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("pop", "synthpop", "country_pop"), 0.62, 0.50, 0.65, 0.30, listOf("romantic", "melancholic")),
        "ed sheeran" to ArtistMetadata(GenreFamilies.POP_MAINSTREAM, listOf("pop", "acoustic_pop"), 0.58, 0.60, 0.65, 0.45, listOf("romantic", "happy")),

        // Devotional / Spiritual
        "hariharan" to ArtistMetadata(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("bhajan", "chalisa", "ghazal"), 0.40, 0.60, 0.35, 0.80, listOf("serene", "devotional")),
        "gulshan kumar" to ArtistMetadata(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("bhajan", "aarti"), 0.45, 0.65, 0.38, 0.75, listOf("devotional")),
        "hansraj raghuwanshi" to ArtistMetadata(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("shiv_bhajan", "devotional"), 0.55, 0.55, 0.45, 0.65, listOf("devotional")),
        "anup jalota" to ArtistMetadata(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("bhajan"), 0.35, 0.60, 0.30, 0.85, listOf("devotional", "serene")),
        "anuradha paudwal" to ArtistMetadata(GenreFamilies.DEVOTIONAL_SPIRITUAL, listOf("aarti", "bhajan"), 0.40, 0.60, 0.35, 0.80, listOf("devotional")),

        // Indian Regional / Haryanvi / Bhojpuri / Desi
        "mc square" to ArtistMetadata(GenreFamilies.INDIAN_REGIONAL_DESI, listOf("haryanvi_hiphop", "desi_hiphop"), 0.82, 0.70, 0.80, 0.15, listOf("energetic")),
        "khesari lal yadav" to ArtistMetadata(GenreFamilies.INDIAN_REGIONAL_DESI, listOf("bhojpuri"), 0.85, 0.80, 0.85, 0.15, listOf("energetic")),
        "pawan singh" to ArtistMetadata(GenreFamilies.INDIAN_REGIONAL_DESI, listOf("bhojpuri"), 0.82, 0.78, 0.82, 0.18, listOf("energetic")),

        // Bollywood / Desi Filmi
        "arijit singh" to ArtistMetadata(GenreFamilies.BOLLYWOOD_DESI, listOf("bollywood_romantic", "filmi"), 0.52, 0.42, 0.50, 0.55, listOf("romantic", "melancholic")),
        "b praak" to ArtistMetadata(GenreFamilies.BOLLYWOOD_DESI, listOf("punjabi_sad", "bollywood_sad"), 0.60, 0.32, 0.48, 0.50, listOf("sad", "emotional")),
        "jubin nautiyal" to ArtistMetadata(GenreFamilies.BOLLYWOOD_DESI, listOf("bollywood_romantic"), 0.50, 0.45, 0.52, 0.60, listOf("romantic")),

        // Hip-Hop / Rap
        "kendrick lamar" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("conscious_hiphop", "west_coast_rap"), 0.80, 0.50, 0.78, 0.15, listOf("energetic", "dark")),
        "eminem" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("rap", "hardcore_hiphop"), 0.85, 0.45, 0.82, 0.10, listOf("energetic", "dark")),
        "drake" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("hiphop", "trap", "melodic_rap"), 0.70, 0.48, 0.80, 0.18, listOf("chill", "romantic")),
        "travis scott" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("trap", "psychedelic_rap"), 0.82, 0.40, 0.75, 0.12, listOf("dark", "energetic")),
        "j cole" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("hiphop", "conscious_rap"), 0.75, 0.55, 0.72, 0.22, listOf("thoughtful", "uplifting")),
        "post malone" to ArtistMetadata(GenreFamilies.HIPHOP_RAP, listOf("melodic_rap", "pop"), 0.68, 0.48, 0.65, 0.25, listOf("melancholic", "chill")),

        // Chill / Ambient / Lofi
        "brian eno" to ArtistMetadata(GenreFamilies.CHILL_AMBIENT, listOf("ambient", "drone"), 0.18, 0.30, 0.20, 0.90, listOf("serene", "meditative")),
        "lofi girl" to ArtistMetadata(GenreFamilies.CHILL_AMBIENT, listOf("lofi_hiphop", "chill_beats"), 0.28, 0.45, 0.65, 0.80, listOf("chill", "relaxing")),
        "joji" to ArtistMetadata(GenreFamilies.CHILL_AMBIENT, listOf("lofi", "sad_rnb"), 0.40, 0.30, 0.55, 0.70, listOf("melancholic", "dark")),
        "powfu" to ArtistMetadata(GenreFamilies.CHILL_AMBIENT, listOf("lofi_hiphop", "sad_rap"), 0.35, 0.38, 0.60, 0.75, listOf("melancholic", "chill")),
        "keshi" to ArtistMetadata(GenreFamilies.CHILL_AMBIENT, listOf("lofi", "indie_rnb"), 0.42, 0.35, 0.58, 0.65, listOf("melancholic", "romantic")),

        // Rock / Alternative
        "imagine dragons" to ArtistMetadata(GenreFamilies.ROCK_ALTERNATIVE, listOf("alt_rock", "indie_rock"), 0.82, 0.55, 0.65, 0.10, listOf("energetic", "uplifting")),
        "coldplay" to ArtistMetadata(GenreFamilies.ROCK_ALTERNATIVE, listOf("alt_rock", "pop_rock"), 0.65, 0.55, 0.55, 0.35, listOf("uplifting", "emotional")),
        "linkin park" to ArtistMetadata(GenreFamilies.ROCK_ALTERNATIVE, listOf("nu_metal", "alt_rock"), 0.88, 0.40, 0.55, 0.08, listOf("dark", "energetic")),
        "onerepublic" to ArtistMetadata(GenreFamilies.ROCK_ALTERNATIVE, listOf("pop_rock"), 0.75, 0.60, 0.65, 0.20, listOf("uplifting")),

        // Classical / Acoustic
        "ludovico einaudi" to ArtistMetadata(GenreFamilies.CLASSICAL_ACOUSTIC, listOf("contemporary_classical", "neoclassical_piano"), 0.22, 0.35, 0.25, 0.95, listOf("melancholic", "serene")),
        "yiruma" to ArtistMetadata(GenreFamilies.CLASSICAL_ACOUSTIC, listOf("neoclassical_piano", "contemporary_classical"), 0.25, 0.45, 0.30, 0.95, listOf("romantic", "serene"))
    )

    fun extractArtistList(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank()) return emptyList()
        val splitRegex = Regex("[,&/|]|(?:\\s+(?:feat\\.?|ft\\.?|featuring|vs\\.?|with|x)\\s+)", RegexOption.IGNORE_CASE)
        val ignoreTokens = setOf("vevo", "topic", "official", "records")
        return rawArtist.split(splitRegex)
            .map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && !ignoreTokens.contains(it) }
    }

    fun isIndianOrDevotionalProfile(title: String, artist: String): Boolean {
        val profile = buildTrackProfile(title, artist)
        return profile.primaryGenre in listOf(
            GenreFamilies.DEVOTIONAL_SPIRITUAL,
            GenreFamilies.INDIAN_REGIONAL_DESI,
            GenreFamilies.BOLLYWOOD_DESI
        )
    }

    private val TITLE_DEVOTIONAL_REGEX = Regex("(?i)\\b(chalisa|bhajan|aarti|stotra|mantra|satsang|kirtan|shiva|hanuman|krishna|ramayan|durga|ganesh|gayatri|dhun|amritwani)\\b")
    private val TITLE_CHILL_REGEX = Regex("(?i)\\b(lo-?fi|chill\\s*beats|ambient|meditation|sleep\\s*music|calm\\s*piano|study\\s*beats|relaxing\\s*piano)\\b")
    private val TITLE_EDM_REGEX = Regex("(?i)\\b(edm|club\\s*mix|extended\\s*mix|future\\s*bass|dubstep|techno|trance|house\\s*music|progressive\\s*house|festival\\s*mix|synthwave)\\b")
    private val TITLE_REGIONAL_REGEX = Regex("(?i)\\b(bhojpuri|haryanvi|ragni|tupka|casa|chora)\\b")

    private fun clamp01(v: Double): Double = max(0.0, min(1.0, v))

    /**
     * Builds Embeat-style 7-dimensional normalized acoustic vector:
     * [danceability-0.5, energy-0.5, valence-0.5, acousticness-0.5, liveness-0.5, speechiness-0.5, instrumentalness-0.5]
     */
    fun buildAcousticVector(
        danceability: Double = 0.60,
        energy: Double = 0.65,
        valence: Double = 0.50,
        acousticness: Double = 0.25,
        liveness: Double = 0.18,
        speechiness: Double = 0.08,
        instrumentalness: Double = 0.10
    ): DoubleArray {
        return doubleArrayOf(
            clamp01(danceability) - 0.5,
            clamp01(energy) - 0.5,
            clamp01(valence) - 0.5,
            clamp01(acousticness) - 0.5,
            clamp01(liveness) - 0.5,
            clamp01(speechiness) - 0.5,
            clamp01(instrumentalness) - 0.5
        )
    }

    fun computeAcousticSimilarity(vecA: DoubleArray, vecB: DoubleArray): Double {
        if (vecA.size != 7 || vecB.size != 7) return 0.5
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until 7) {
            dot += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        if (normA <= 1e-12 || normB <= 1e-12) return 0.5
        val rawCosine = dot / (sqrt(normA) * sqrt(normB))
        val sim01 = 0.5 * (rawCosine + 1.0)
        return clamp01(sim01)
    }

    fun buildTrackProfile(title: String, artist: String): TrackProfile {
        val cleanArt = TrackNormalizer.cleanArtist(artist)

        var primaryGenre = GenreFamilies.POP_MAINSTREAM
        var subgenres = listOf("pop")
        var moodTags = listOf("neutral")
        var energy = 0.60
        var valence = 0.50
        var danceability = 0.60
        var acousticness = 0.30
        var confidence = 0.50
        var provenance = "default_fallback"

        // 1. Direct artist match in catalog (checking all participating artists)
        val parsedArtists = extractArtistList(artist)
        val matchedKey = parsedArtists.firstNotNullOfOrNull { a ->
            ARTIST_GENRE_MAP.keys.firstOrNull { k -> a.contains(k) || k.contains(a) }
        } ?: ARTIST_GENRE_MAP.keys.firstOrNull { cleanArt.contains(it) || it.contains(cleanArt) }
        if (matchedKey != null) {
            val meta = ARTIST_GENRE_MAP.getValue(matchedKey)
            primaryGenre = meta.family
            subgenres = meta.subgenres
            energy = meta.energy
            valence = meta.valence
            danceability = meta.danceability
            acousticness = meta.acousticness
            moodTags = meta.mood
            confidence = 0.95
            provenance = "catalog_taxonomy:$matchedKey"
        }

        // 2. Strong Title Keyword Cues
        if (TITLE_DEVOTIONAL_REGEX.containsMatchIn(title)) {
            primaryGenre = GenreFamilies.DEVOTIONAL_SPIRITUAL
            subgenres = listOf("devotional", "bhajan")
            energy = 0.42
            valence = 0.60
            acousticness = 0.80
            moodTags = listOf("devotional", "serene")
            confidence = max(confidence, 0.90)
            provenance = "title_cues:${GenreFamilies.DEVOTIONAL_SPIRITUAL}"
        } else if (TITLE_CHILL_REGEX.containsMatchIn(title)) {
            primaryGenre = GenreFamilies.CHILL_AMBIENT
            subgenres = listOf("ambient", "lofi")
            energy = 0.28
            valence = 0.40
            acousticness = 0.85
            moodTags = listOf("chill", "relaxing")
            confidence = max(confidence, 0.85)
            provenance = "title_cues:${GenreFamilies.CHILL_AMBIENT}"
        } else if (TITLE_EDM_REGEX.containsMatchIn(title)) {
            primaryGenre = GenreFamilies.ELECTRONIC_EDM
            subgenres = listOf("edm", "electronic")
            energy = 0.82
            valence = 0.55
            acousticness = 0.10
            moodTags = listOf("energetic", "uplifting")
            confidence = max(confidence, 0.85)
            provenance = "title_cues:${GenreFamilies.ELECTRONIC_EDM}"
        } else if (TITLE_REGIONAL_REGEX.containsMatchIn(title)) {
            primaryGenre = GenreFamilies.INDIAN_REGIONAL_DESI
            subgenres = listOf("indian_regional")
            energy = 0.80
            valence = 0.70
            acousticness = 0.20
            moodTags = listOf("energetic")
            confidence = max(confidence, 0.85)
            provenance = "title_cues:${GenreFamilies.INDIAN_REGIONAL_DESI}"
        }

        // 3. Audio profile adjustments for variants
        val mod = TrackNormalizer.detectModifier(title)
        if (mod != null) {
            if (mod in listOf("slowed", "reverb", "acoustic", "lofi", "chill beats")) {
                energy = max(0.2, energy - 0.25)
                acousticness = min(0.9, acousticness + 0.35)
                moodTags = (moodTags + listOf("melancholic", "chill")).distinct()
            } else if (mod in listOf("sped up", "nightcore", "remix", "club mix")) {
                energy = min(0.95, energy + 0.20)
                danceability = min(0.95, danceability + 0.15)
                moodTags = (moodTags + listOf("energetic")).distinct()
            }
        }

        val instrumentalness = if (subgenres.contains("edm") || subgenres.contains("ambient")) 0.35 else 0.05
        val acousticVector = buildAcousticVector(
            danceability = danceability,
            energy = energy,
            valence = valence,
            acousticness = acousticness,
            liveness = 0.18,
            speechiness = 0.08,
            instrumentalness = instrumentalness
        )

        return TrackProfile(
            title = title,
            artist = artist,
            primaryGenre = primaryGenre,
            subgenres = subgenres,
            moodTags = moodTags,
            energy = energy,
            valence = valence,
            danceability = danceability,
            acousticness = acousticness,
            acousticVector = acousticVector,
            confidence = confidence,
            provenance = provenance
        )
    }

    /**
     * Binary eligibility gate applied BEFORE scoring.
     */
    fun checkEligibilityGate(seedProfile: TrackProfile?, candidateProfile: TrackProfile): Pair<Boolean, String?> {
        if (seedProfile == null) return Pair(true, null)

        val seedFam = seedProfile.primaryGenre
        val candFam = candidateProfile.primaryGenre

        val famMatrix = GENRE_COMPATIBILITY_MATRIX[seedFam]
        val compatScore = famMatrix?.get(candFam) ?: 0.50

        if (compatScore <= 0.0) {
            return Pair(false, "Incompatible genre family: $candFam is disallowed for $seedFam seed")
        }

        // High-Energy Gate (e.g. EDM seed)
        if (seedProfile.energy >= 0.70 && candidateProfile.energy <= 0.30) {
            return Pair(false, "Energy mismatch: candidate is too low energy (${candidateProfile.energy}) for high-energy seed (${seedProfile.energy})")
        }

        // Chill / Ambient Gate
        if (seedProfile.energy <= 0.35 && candidateProfile.energy >= 0.80) {
            return Pair(false, "Energy mismatch: candidate is too intense (${candidateProfile.energy}) for ambient seed (${seedProfile.energy})")
        }

        return Pair(true, null)
    }

    /**
     * 0-100 Weighted Point Scoring:
     * - Acoustic similarity: 40 pts
     * - Genre & subgenre compatibility: 25 pts
     * - Mood & energy alignment: 20 pts
     * - Artist affinity & preference: 15 pts
     * - Penalties: Recent (-15), Alternate (-25), Repeated artist (-8 to -40)
     */
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
        val (parsedArt, _) = TrackNormalizer.parseArtistAndTitle(candidate.title, candidate.artist)
        val cArtist = TrackNormalizer.cleanArtist(parsedArt)
        val cKey = TrackNormalizer.extractCanonicalKey(candidate.title, candidate.artist).lowercase(Locale.ROOT)

        var acousticPoints = 20.0
        if (seedProfile != null) {
            val sim = computeAcousticSimilarity(seedProfile.acousticVector, candProfile.acousticVector)
            acousticPoints = sim * 40.0
        }

        var genrePoints = 12.5
        if (seedProfile != null) {
            val seedFam = seedProfile.primaryGenre
            val candFam = candProfile.primaryGenre
            val famMatrix = GENRE_COMPATIBILITY_MATRIX[seedFam]
            val famRatio = famMatrix?.get(candFam) ?: 0.50

            val sharedSubgenres = seedProfile.subgenres.intersect(candProfile.subgenres.toSet())
            val subRatio = if (sharedSubgenres.isNotEmpty()) 1.0 else 0.5
            genrePoints = (famRatio * 0.75 + subRatio * 0.25) * 25.0
        }

        var moodEnergyPoints = 10.0
        if (seedProfile != null) {
            val energyDist = Math.abs(seedProfile.energy - candProfile.energy)
            val valenceDist = Math.abs(seedProfile.valence - candProfile.valence)
            val energyMatch = 1.0 - (energyDist + valenceDist) / 2.0

            val sharedMood = seedProfile.moodTags.intersect(candProfile.moodTags.toSet())
            val moodMatch = if (sharedMood.isNotEmpty()) 1.0 else 0.50
            moodEnergyPoints = (energyMatch * 0.65 + moodMatch * 0.35) * 20.0
        }

        var prefPoints = 3.0
        if (likedArtists.contains(cArtist)) {
            prefPoints = 15.0
        } else if (seedArtists.contains(cArtist) || candidate.source == "seed_artist") {
            prefPoints = 13.0
        } else if (candidate.source == "watch_next") {
            prefPoints = 11.0
        } else if (candidate.source == "related_search") {
            prefPoints = 9.0
        }

        if (likedTrackKeys.contains(cKey)) {
            prefPoints = min(15.0, prefPoints + 3.0)
        }

        var penalties = 0.0
        if (skippedArtists.contains(cArtist)) {
            penalties += 15.0
        }
        if (recentKeys.contains(cKey)) {
            penalties += 15.0
        }
        if (TrackNormalizer.detectModifier(candidate.title) != null) {
            penalties += 25.0
        }

        val candArtists = extractArtistList(candidate.artist)
        var maxSaturation = 0
        for (ca in candArtists) {
            val count = selectedSoFar.count { s ->
                val sArtists = extractArtistList(s.artist)
                sArtists.contains(ca)
            }
            if (count > maxSaturation) {
                maxSaturation = count
            }
        }
        if (maxSaturation == 1) {
            penalties += 8.0
        } else if (maxSaturation == 2) {
            penalties += 20.0
        } else if (maxSaturation >= 3) {
            penalties += 50.0
        }

        val totalRaw = (acousticPoints + genrePoints + moodEnergyPoints + prefPoints) - penalties
        val totalScore = clamp01(totalRaw / 100.0) * 100.0

        val primaryExplanation = when {
            likedArtists.contains(cArtist) -> "Favorite artist: ${candidate.artist}"
            seedArtists.contains(cArtist) -> "More from ${candidate.artist}"
            candidate.source == "watch_next" -> "Related via algorithmic mix"
            candidate.source == "related_search" -> "Similar style: ${candProfile.primaryGenre}"
            else -> "Acoustic match (${acousticPoints.toInt()} pts)"
        }

        return ScoreBreakdown(
            acousticSimilarityPoints = acousticPoints,
            genreCompatibilityPoints = genrePoints,
            moodEnergyPoints = moodEnergyPoints,
            preferencePoints = prefPoints,
            penalties = penalties,
            totalScore = totalScore,
            primaryExplanation = primaryExplanation
        )
    }
}
