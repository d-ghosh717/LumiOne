package com.lumione.player.search

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class SearchAuthException(message: String) : Exception(message)

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val streamUrl: String = "",
    val playCount: Long = 0,
    val favoriteCount: Long = 0,
    val repostCount: Long = 0,
    val provider: String = "lumione",
    val providerId: String = videoId,
    val youtubeVideoId: String = videoId,
    val recommendationReason: String = ""
) {
    val trackId: String get() = videoId
}

/**
 * Normalization, canonical key extraction, and deduplication engine for music tracks.
 */
object TrackNormalizer {
    private val PUBLISHING_TAGS_REGEX = Regex(
        "(?i)\\b(official\\s*(music\\s*)?video|official\\s*audio|official\\s*lyric\\s*video|official\\s*visualizer|lyrics?|visualizer|hd|4k|remaster(ed)?(\\s*\\d{4})?|explicit|audio|video|original mix)\\b"
    )
    private val BRACKETED_FEAT_REGEX = Regex(
        "(?i)\\[[^\\]]*(feat\\.?|ft\\.?|featuring)[^\\]]*\\]|\\([^)]*(feat\\.?|ft\\.?|featuring)[^)]*\\)"
    )
    private val STANDALONE_FEAT_REGEX = Regex(
        "(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*$"
    )
    private val MODIFIERS_REGEX = Regex(
        "(?i)\\b(slowed|reverb|sped\\s*up|speed\\s*up|nightcore|daycore|lo-?fi|lo\\s*fi|chill\\s*beats|remix|rework|bootleg|flip|vip|mashup|cover|acoustic|piano\\s*cover|guitar\\s*cover|violin\\s*cover|orchestral?|instrumental(\\s*version)?|karaoke|live(\\s*in|\\s*at|\\s*session)?|bass\\s*boosted|8d(\\s*audio)?|1\\s*hour|10\\s*hours?|loop)\\b"
    )

    private val DERIVATIVE_MODIFIERS_REGEX = Regex(
        "(?i)\\b(slowed|reverb|sped\\s*up|speed\\s*up|nightcore|daycore|lo-?fi|lo\\s*fi|chill\\s*beats|bass\\s*boosted|8d(\\s*audio)?|1\\s*hour|10\\s*hours?|loop)\\b"
    )

    fun isDerivativeModifier(title: String): Boolean {
        return DERIVATIVE_MODIFIERS_REGEX.containsMatchIn(title)
    }

    private val TITLE_SEPARATOR_REGEX = Regex("\\s+[-—–|:]\\s+")

    fun cleanTitle(title: String): String {
        return title
            .replace(BRACKETED_FEAT_REGEX, " ")
            .replace(STANDALONE_FEAT_REGEX, " ")
            .replace(PUBLISHING_TAGS_REGEX, " ")
            .replace(Regex("[\\[\\](){}\"']"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun cleanArtist(artist: String): String {
        return artist
            .replace(BRACKETED_FEAT_REGEX, " ")
            .replace(STANDALONE_FEAT_REGEX, " ")
            .replace(Regex("(?i)\\b(vevo|topic|records|music|official|channel)\\b"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun extractCoreTitle(title: String): String {
        val cleaned = cleanTitle(title)
        return cleaned.replace(MODIFIERS_REGEX, " ").replace(Regex("\\s+"), " ").trim()
    }

    fun detectModifier(title: String): String? {
        val match = MODIFIERS_REGEX.find(title)
        return match?.value?.lowercase(Locale.ROOT)
    }

    /**
     * Resolves the real performing artist and song title from raw YouTube metadata.
     * In third-party uploads, the channel is often a curator (e.g. "S o u n d s ."), NOT the artist.
     */
    fun parseArtistAndTitle(rawTitle: String, channelName: String): Pair<String, String> {
        var detectedArtist = channelName.trim()
        var detectedTitle = rawTitle.trim()

        if (TITLE_SEPARATOR_REGEX.containsMatchIn(rawTitle)) {
            val parts = rawTitle.split(TITLE_SEPARATOR_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val part0 = parts[0]
                val part1 = parts.subList(1, parts.size).joinToString(" - ")

                val part0HasModifier = detectModifier(part0) != null
                val part1HasModifier = detectModifier(part1) != null

                if (part0HasModifier && !part1HasModifier) {
                    detectedTitle = part0
                    detectedArtist = part1
                } else {
                    detectedArtist = part0
                    detectedTitle = part1
                }
            }
        } else if (channelName.endsWith("- Topic", ignoreCase = true)) {
            detectedArtist = channelName.replace("- Topic", "", ignoreCase = true).trim()
        }

        return Pair(detectedArtist, detectedTitle)
    }

    /**
     * Unique key identifying the underlying song composition and primary artist.
     * e.g. "LONOWN - worry (Slowed)" -> "lonown:::worry"
     * e.g. "Alan Walker - Faded" -> "alanwalker:::faded"
     * e.g. "ZHU - Faded" -> "zhu:::faded"
     */
    fun extractCanonicalKey(title: String, artist: String): String {
        val (parsedArtist, parsedTitle) = parseArtistAndTitle(title, artist)
        val cArtist = cleanArtist(parsedArtist)
        var cCoreTitle = extractCoreTitle(parsedTitle)
        if (cArtist.isNotEmpty() && cCoreTitle.contains(cArtist)) {
            cCoreTitle = cCoreTitle.replace(cArtist, " ").replace(Regex("\\s+"), " ").trim()
        }
        if (cCoreTitle.isEmpty()) cCoreTitle = cleanTitle(parsedTitle)
        return "$cArtist:::$cCoreTitle"
    }

    /**
     * Unique key identifying the specific version / recording.
     * e.g. "LONOWN - worry (Slowed)" -> "lonown:::worry:::slowed"
     * e.g. "LONOWN - worry" -> "lonown:::worry:::original"
     */
    fun extractRecordingKey(title: String, artist: String): String {
        val canonicalKey = extractCanonicalKey(title, artist)
        val modifier = detectModifier(title) ?: "original"
        return "$canonicalKey:::$modifier"
    }

    fun deduplicateAndFilter(
        candidates: List<SearchResult>,
        allowAlternateVersions: Boolean = false,
        maxPerArtist: Int = 2,
        seedKeysToExclude: Set<String> = emptySet()
    ): List<SearchResult> {
        val clusters = mutableMapOf<String, MutableList<SearchResult>>()

        for (item in candidates) {
            if (!SearchEngine.isMusicTrack(item.title, item.artist, item.durationMs)) continue
            if (!allowAlternateVersions && isDerivativeModifier(item.title)) continue
            val canonicalKey = extractCanonicalKey(item.title, item.artist)
            if (seedKeysToExclude.contains(canonicalKey)) continue

            clusters.getOrPut(canonicalKey) { mutableListOf() }.add(item)
        }

        val results = mutableListOf<SearchResult>()
        val artistCountMap = mutableMapOf<String, Int>()

        for ((_, variants) in clusters) {
            variants.sortWith(Comparator { a, b ->
                val aAlt = detectModifier(a.title) != null
                val bAlt = detectModifier(b.title) != null
                if (aAlt != bAlt) return@Comparator if (aAlt) 1 else -1

                if (a.provider == "saavn" && b.provider != "saavn") return@Comparator -1
                if (b.provider == "saavn" && a.provider != "saavn") return@Comparator 1

                val aDurDiff = Math.abs((a.durationMs / 1000L) - 210)
                val bDurDiff = Math.abs((b.durationMs / 1000L) - 210)
                aDurDiff.compareTo(bDurDiff)
            })

            val bestTrack = variants.first()
            val (parsedArtist, _) = parseArtistAndTitle(bestTrack.title, bestTrack.artist)
            val artistKey = cleanArtist(parsedArtist)
            val currentCount = artistCountMap[artistKey] ?: 0
            if (currentCount < maxPerArtist) {
                results.add(bestTrack)
                artistCountMap[artistKey] = currentCount + 1
            }

            if (allowAlternateVersions && variants.size > 1) {
                val seenModifiers = mutableSetOf(detectModifier(bestTrack.title) ?: "original")
                for (i in 1 until variants.size) {
                    val variant = variants[i]
                    val mod = detectModifier(variant.title) ?: "original"
                    if (!seenModifiers.contains(mod)) {
                        seenModifiers.add(mod)
                        val altCount = artistCountMap[artistKey] ?: 0
                        if (altCount < maxPerArtist) {
                            results.add(variant)
                            artistCountMap[artistKey] = altCount + 1
                        }
                    }
                }
            }
        }

        return results
    }

    /**
     * Deduplicates search or discovery results for playback queue insertion.
     * - Guarantees no duplicate uploads of the selected track appear in upcoming tracks.
     * - Enforces 1 version per song in the upcoming queue.
     * - Preserves distinct songs even if they share titles (e.g. Alan Walker - Faded vs ZHU - Faded).
     * - Prunes duplicate videoIds and identical recordings.
     */
    fun deduplicateForQueue(
        candidates: List<SearchResult>,
        selectedTrackKey: String = "",
        maxPerArtist: Int = 3
    ): List<SearchResult> {
        val seenVideoIds = mutableSetOf<String>()
        val seenSongKeys = mutableSetOf<String>()
        if (selectedTrackKey.isNotBlank()) {
            seenSongKeys.add(selectedTrackKey)
        }

        val artistCountMap = mutableMapOf<String, Int>()
        val filtered = mutableListOf<SearchResult>()

        for (item in candidates) {
            if (!SearchEngine.isMusicTrack(item.title, item.artist, item.durationMs)) continue
            if (seenVideoIds.contains(item.videoId)) continue

            val canonicalKey = extractCanonicalKey(item.title, item.artist)
            if (seenSongKeys.contains(canonicalKey)) continue

            val (parsedArtist, _) = parseArtistAndTitle(item.title, item.artist)
            val artistKey = cleanArtist(parsedArtist)
            val count = artistCountMap[artistKey] ?: 0
            if (count >= maxPerArtist) continue

            seenVideoIds.add(item.videoId)
            seenSongKeys.add(canonicalKey)
            artistCountMap[artistKey] = count + 1
            filtered.add(item)
        }

        return filtered
    }
}

/**
 * Authenticated Search & Recommendation Engine for LumiOne Player with automatic fallback.
 * Sends verified Firebase ID token to LumiOne backend to retrieve music metadata and recommendations.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "SearchEngine"
        private const val BACKEND_URL = "https://lumione.onrender.com"

        private val NON_MUSIC_REGEX = Regex(
            "(?i)\\b(gameplay|walkthrough|playthrough|let's play|lets play|gta\\s*[5v]|gta\\s*online|fortnite|roblox|minecraft|free\\s*fire|pubg|call\\s*of\\s*duty|cod|valorant|apex\\s*legends|speedrun|cutscenes?|game\\s*trailer|asmr|mukbang|slime|satisfying|unboxing|prank|comedy video|funny video|funny\\s*moments|puzzle|riddle|challenge|diy|crafts|life\\s*hacks|vlog|daily vlog|reaction to|reacting|roast|news live|live stream|livestream|full movie|full episode|episode \\d+|season \\d+|tier list|audiobook|podcast|interview|shorts|meme|compilation|sfx|sound effects?|10 hour|1 hour loop|24/7|karaoke backing track)\\b"
        )

        fun isMusicTrack(title: String, artist: String, durationMs: Long): Boolean {
            if (title.isBlank()) return false
            val combined = "$title $artist"
            if (NON_MUSIC_REGEX.containsMatchIn(combined)) {
                return false
            }
            if (durationMs > 0) {
                val durationSec = durationMs / 1000L
                if (durationSec < 35 || durationSec > 900) {
                    return false
                }
            }
            return true
        }
    }

    suspend fun search(query: String, idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        if (idToken.isNullOrBlank()) {
            throw SearchAuthException("Sign in to search music.")
        }

        val userRequestsModifier = TrackNormalizer.detectModifier(trimmed) != null

        // 1. Try Authenticated Backend Search First
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$BACKEND_URL/api/search?q=$encodedQuery"
            val response = getJsonAuthenticated(url, idToken, timeoutMs = 15000)
            val backendResults = parseBackendResults(response).filter { isMusicTrack(it.title, it.artist, it.durationMs) }
            val cleanResults = TrackNormalizer.deduplicateAndFilter(
                backendResults,
                allowAlternateVersions = userRequestsModifier,
                maxPerArtist = 3
            )
            if (cleanResults.isNotEmpty()) {
                Log.i(TAG, "Backend search succeeded with ${cleanResults.size} music results for '$trimmed'")
                return@withContext cleanResults
            }
        } catch (e: SearchAuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backend search query failed: ${e.message}. Attempting JioSaavn / YouTube fallback.")
        }

        // 2. Fallback: Search JioSaavn metadata directly (100% music catalog)
        val saavnResults = searchJioSaavn(trimmed)
        if (saavnResults.isNotEmpty()) {
            val cleanSaavn = TrackNormalizer.deduplicateAndFilter(
                saavnResults,
                allowAlternateVersions = userRequestsModifier,
                maxPerArtist = 3
            )
            Log.i(TAG, "JioSaavn search succeeded with ${cleanSaavn.size} results for '$trimmed'")
            return@withContext cleanSaavn
        }

        // 3. Fallback: Search YouTube metadata directly via embedded engine with music filter
        val ytResults = searchLocalYouTube("$trimmed audio").filter { isMusicTrack(it.title, it.artist, it.durationMs) }
        val cleanYt = TrackNormalizer.deduplicateAndFilter(
            ytResults,
            allowAlternateVersions = userRequestsModifier,
            maxPerArtist = 3
        )
        if (cleanYt.isNotEmpty()) {
            return@withContext cleanYt
        }

        return@withContext emptyList()
    }

    suspend fun getTrending(idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!idToken.isNullOrBlank()) {
            try {
                val url = "$BACKEND_URL/api/trending"
                val response = getJsonAuthenticated(url, idToken, timeoutMs = 15000)
                val results = parseBackendResults(response).filter { isMusicTrack(it.title, it.artist, it.durationMs) }
                val clean = TrackNormalizer.deduplicateAndFilter(results, allowAlternateVersions = false)
                if (clean.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${clean.size} trending music tracks from backend")
                    return@withContext clean
                }
            } catch (e: SearchAuthException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch from backend failed: ${e.message}. Using native music discovery.")
            }
        }

        val chartMusic = getTrendingMusic()
        if (chartMusic.isNotEmpty()) {
            val clean = TrackNormalizer.deduplicateAndFilter(chartMusic, allowAlternateVersions = false)
            Log.i(TAG, "Loaded ${clean.size} trending music tracks from native chart discovery")
            return@withContext clean
        }

        val ytTrending = searchLocalYouTube("Top Music Hits 2026").filter { isMusicTrack(it.title, it.artist, it.durationMs) }
        val cleanYt = TrackNormalizer.deduplicateAndFilter(ytTrending, allowAlternateVersions = false)
        if (cleanYt.isNotEmpty()) return@withContext cleanYt

        return@withContext TrackNormalizer.deduplicateAndFilter(searchJioSaavn("Top Hits"), allowAlternateVersions = false)
    }

    suspend fun getRecommendations(
        idToken: String?,
        seedArtists: List<String> = emptyList(),
        likedArtists: List<String> = emptyList(),
        topPlayedArtists: List<String> = emptyList(),
        likedTrackKeys: List<String> = emptyList(),
        recentTrackKeys: List<String> = emptyList(),
        skippedArtists: List<String> = emptyList(),
        limit: Int = 20
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        // 1. Try Backend Recommendation Service
        try {
            val payload = JSONObject().apply {
                put("seedArtists", JSONArray(seedArtists))
                put("likedArtists", JSONArray(likedArtists))
                put("topPlayedArtists", JSONArray(topPlayedArtists))
                put("likedTrackKeys", JSONArray(likedTrackKeys))
                put("recentTrackKeys", JSONArray(recentTrackKeys))
                put("skippedArtists", JSONArray(skippedArtists))
                put("limit", limit)
            }

            val url = "$BACKEND_URL/api/recommendations"
            val response = postJson(url, payload.toString(), idToken, timeoutMs = 15000)
            val backendResults = parseBackendResults(response)
            if (backendResults.isNotEmpty()) {
                Log.i(TAG, "Loaded ${backendResults.size} recommendations from backend")
                return@withContext backendResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend recommendation request failed: ${e.message}. Using native offline recommender.")
        }

        // 2. Local Fallback Recommender
        val candidatePool = mutableListOf<SearchResult>()
        val targetArtists = (seedArtists + topPlayedArtists + likedArtists).distinct().take(3)
        for (artist in targetArtists) {
            try {
                val saavnTracks = searchJioSaavn(artist)
                if (saavnTracks.isNotEmpty()) {
                    candidatePool.addAll(saavnTracks.take(6))
                }
            } catch (_: Exception) {}
        }
        val chartMusic = getTrendingMusic()
        candidatePool.addAll(chartMusic)

        return@withContext rankRecommendationsLocally(
            candidates = candidatePool,
            seedArtists = seedArtists,
            likedArtists = likedArtists,
            topPlayedArtists = topPlayedArtists,
            likedTrackKeys = likedTrackKeys,
            recentTrackKeys = recentTrackKeys,
            skippedArtists = skippedArtists,
            limit = limit
        )
    }

    fun rankRecommendationsLocally(
        candidates: List<SearchResult>,
        seedArtists: List<String> = emptyList(),
        likedArtists: List<String> = emptyList(),
        topPlayedArtists: List<String> = emptyList(),
        likedTrackKeys: List<String> = emptyList(),
        recentTrackKeys: List<String> = emptyList(),
        skippedArtists: List<String> = emptyList(),
        limit: Int = 20
    ): List<SearchResult> {
        val cleanCandidates = TrackNormalizer.deduplicateAndFilter(
            candidates,
            allowAlternateVersions = false,
            maxPerArtist = 2,
            seedKeysToExclude = recentTrackKeys.toSet()
        )

        val cleanLikedArtists = likedArtists.map { TrackNormalizer.cleanArtist(it) }
        val cleanSeedArtists = seedArtists.map { TrackNormalizer.cleanArtist(it) }
        val cleanTopArtists = topPlayedArtists.map { TrackNormalizer.cleanArtist(it) }
        val cleanSkippedArtists = skippedArtists.map { TrackNormalizer.cleanArtist(it) }
        val likedKeysSet = likedTrackKeys.map { it.lowercase() }.toSet()

        val pool = cleanCandidates.toMutableList()
        val selected = mutableListOf<SearchResult>()

        while (pool.isNotEmpty() && selected.size < limit) {
            var bestIndex = -1
            var bestScore = -Double.MAX_VALUE

            for (i in pool.indices) {
                val candidate = pool[i]
                var score = 5.0
                val (parsedArtist, _) = TrackNormalizer.parseArtistAndTitle(candidate.title, candidate.artist)
                val cArtist = TrackNormalizer.cleanArtist(parsedArtist)
                val cKey = TrackNormalizer.extractCanonicalKey(candidate.title, candidate.artist).lowercase()

                if (cleanLikedArtists.contains(cArtist) || cleanTopArtists.contains(cArtist)) {
                    score += 3.5
                } else if (cleanSeedArtists.contains(cArtist)) {
                    score += 2.5
                } else {
                    score += 2.0 // Discovery bonus
                }

                if (likedKeysSet.contains(cKey)) {
                    score += 2.0
                }

                if (cleanSkippedArtists.contains(cArtist)) {
                    score -= 2.5
                }

                if (TrackNormalizer.detectModifier(candidate.title) != null) {
                    score -= 5.0
                }

                val countOfThisArtist = selected.count { TrackNormalizer.cleanArtist(it.artist) == cArtist }
                if (countOfThisArtist == 1) {
                    score -= 3.0
                } else if (countOfThisArtist >= 2) {
                    score -= 10.0
                }

                if (candidate.provider == "saavn") {
                    score += 1.0
                }

                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }

            if (bestIndex >= 0 && bestScore > 0) {
                selected.add(pool.removeAt(bestIndex))
            } else {
                break
            }
        }

        return selected
    }

    suspend fun getTrendingMusic(): List<SearchResult> = withContext(Dispatchers.IO) {
        val trendingSongs = mutableListOf<SearchResult>()
        try {
            val chartUrls = listOf(
                "https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=110858205&_format=json&_marker=0&ctx=web6dot0", // Trending Today
                "https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=1081991857&_format=json&_marker=0&ctx=web6dot0" // English Hit Songs
            )

            val lists = mutableListOf<List<SearchResult>>()
            for (endpoint in chartUrls) {
                val tracks = fetchChartPlaylist(endpoint)
                if (tracks.isNotEmpty()) {
                    lists.add(tracks)
                }
            }

            val maxLen = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxLen) {
                for (list in lists) {
                    if (i < list.size) {
                        trendingSongs.add(list[i])
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch native trending music charts: ${e.message}")
        }
        return@withContext trendingSongs.take(30)
    }

    private fun fetchChartPlaylist(endpoint: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            if (connection.responseCode in 200..299) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val songs = root.optJSONArray("songs")
                if (songs != null) {
                    for (i in 0 until songs.length()) {
                        val item = songs.getJSONObject(i)
                        val id = item.optString("id", "")
                        val title = item.optString("song", item.optString("title", ""))
                        val artist = item.optString("primary_artists", item.optString("singers", ""))
                        val duration = item.optLong("duration", 0L)
                        var image = item.optString("image", "")
                        if (image.contains("150x150")) {
                            image = image.replace("150x150", "500x500")
                        }

                        if (id.isNotBlank() && title.isNotBlank() && isMusicTrack(title, artist, duration * 1000L)) {
                            results.add(
                                SearchResult(
                                    videoId = id,
                                    title = title,
                                    artist = artist.ifBlank { "Unknown Artist" },
                                    durationMs = duration * 1000L,
                                    thumbnailUrl = image,
                                    provider = "saavn",
                                    providerId = id,
                                    youtubeVideoId = ""
                                )
                            )
                        }
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching chart playlist from $endpoint: ${e.message}")
        }
        return results
    }

    private suspend fun searchLocalYouTube(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val request = YoutubeDLRequest("ytsearch10:$query").apply {
                addOption("--extractor-args", "youtube:player_client=android")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--geo-bypass")
            }

            val info = YoutubeDL.getInstance().getInfo(request)
            val id = info.id ?: ""
            val title = info.title ?: ""
            val uploader = info.uploader ?: ""
            val durationSec = info.duration.toLong()

            if (id.isNotBlank() && id != "ytsearch10:$query") {
                results.add(
                    SearchResult(
                        videoId = id,
                        title = title,
                        artist = uploader,
                        durationMs = durationSec * 1000L,
                        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        provider = "lumione",
                        providerId = id
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local YouTube search failed: ${e.message}")
        }
        return@withContext results
    }

    suspend fun searchJioSaavn(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&p=1&n=15&q=$encodedQuery"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            if (connection.responseCode in 200..299) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val items = root.optJSONArray("results")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val id = item.optString("id", "")
                        val title = item.optString("song", item.optString("title", ""))
                        val artist = item.optString("singers", item.optString("primary_artists", ""))
                        val duration = item.optLong("duration", 0L)
                        var image = item.optString("image", "")
                        if (image.contains("150x150")) {
                            image = image.replace("150x150", "500x500")
                        }
                        if (id.isNotBlank() && title.isNotBlank()) {
                            results.add(
                                SearchResult(
                                    videoId = id,
                                    title = title,
                                    artist = artist,
                                    durationMs = duration * 1000L,
                                    thumbnailUrl = image,
                                    provider = "saavn",
                                    providerId = id,
                                    youtubeVideoId = ""
                                )
                            )
                        }
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn fallback search failed: ${e.message}")
        }
        return@withContext results
    }

    internal fun parseBackendResults(jsonStr: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val trimmed = jsonStr.trim()
            val array: JSONArray? = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("results")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("tracks")
                    ?: root.optJSONArray("items")
            }

            if (array == null) return emptyList()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("youtubeVideoId")
                    .ifBlank { item.optString("id") }
                    .ifBlank { item.optString("videoId") }
                    .ifBlank { item.optString("providerId") }
                if (id.isBlank()) continue

                val title = item.optString("title")
                    .ifBlank { item.optString("name", "Unknown Title") }
                val artist = item.optString("artist")
                    .ifBlank { item.optString("uploader") }
                    .ifBlank { item.optString("channel", "Unknown Artist") }
                val durationSec = item.optLong("duration", item.optLong("durationSec", 0L))
                val thumbnail = item.optString("thumbnail")
                val provider = item.optString("provider", "lumione")
                val reason = item.optString("recommendationReason", "")
                val thumb = if (thumbnail.isNotBlank() && !thumbnail.contains("invidious")) {
                    thumbnail
                } else {
                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                }

                results.add(
                    SearchResult(
                        videoId = id,
                        title = title,
                        artist = artist,
                        durationMs = durationSec * 1000L,
                        thumbnailUrl = thumb,
                        provider = provider,
                        providerId = id,
                        youtubeVideoId = id,
                        recommendationReason = reason
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
        }
        return results
    }

    private fun getJsonAuthenticated(urlStr: String, idToken: String?, timeoutMs: Int = 10000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (!idToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $idToken")
            }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) LumiOne/2.0")
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw SearchAuthException("Unauthorized (HTTP $responseCode): $errorStream")
        }

        if (responseCode !in 200..299) {
            val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Backend error (HTTP $responseCode): $errorStream")
        }

        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun postJson(urlStr: String, bodyStr: String, idToken: String?, timeoutMs: Int = 10000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!idToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $idToken")
            }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) LumiOne/2.0")
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use {
            it.write(bodyStr)
            it.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Backend POST error (HTTP $responseCode): $errorStream")
        }

        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun cancel() = ioScope.cancel()
}
