package com.lumione.player.search

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val videoId: String, // Track ID (Audius track ID string/hash)
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val streamUrl: String = "",
    val playCount: Long = 0,
    val favoriteCount: Long = 0,
    val repostCount: Long = 0
) {
    val trackId: String get() = videoId

    fun getEffectiveStreamUrl(): String {
        if (streamUrl.isNotBlank()) return streamUrl
        return "https://api.audius.co/v1/tracks/$videoId/stream?app_name=LumiOne"
    }
}

/**
 * Search engine using official Audius API to find and stream tracks.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val API_BASE = "https://api.audius.co/v1"
        private const val APP_NAME = "LumiOne"
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$API_BASE/tracks/search?query=$encodedQuery&app_name=$APP_NAME&limit=20"
            val response = getJson(url)
            val parsed = parseAudiusTracks(response)
            rankSearchResults(parsed, trimmed)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTrending(): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/tracks/trending?app_name=$APP_NAME&limit=20"
            val response = getJson(url)
            parseAudiusTracks(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun rankSearchResults(tracks: List<SearchResult>, query: String): List<SearchResult> {
        val qLower = query.lowercase().trim()
        val qTokens = qLower.split(Regex("\\s+")).filter { it.isNotBlank() }

        return tracks
            .distinctBy { it.videoId }
            .sortedWith(
                compareByDescending<SearchResult> { track ->
                    var score = 0L
                    val titleLower = track.title.lowercase()
                    val artistLower = track.artist.lowercase()

                    // 1. Exact title match
                    if (titleLower == qLower) {
                        score += 100_000
                    } else if (titleLower.startsWith(qLower)) {
                        score += 50_000
                    } else if (titleLower.contains(qLower)) {
                        score += 25_000
                    }

                    // 2. Exact or partial artist match
                    if (artistLower == qLower) {
                        score += 40_000
                    } else if (artistLower.contains(qLower)) {
                        score += 20_000
                    }

                    // 3. Token matches
                    val matchedTokens = qTokens.count { token ->
                        titleLower.contains(token) || artistLower.contains(token)
                    }
                    score += matchedTokens * 5_000

                    // 4. Popularity score boost
                    val popularityBonus = (track.playCount / 10).coerceAtMost(10_000) +
                            (track.favoriteCount * 20).coerceAtMost(10_000) +
                            (track.repostCount * 25).coerceAtMost(10_000)
                    score += popularityBonus

                    score
                }.thenByDescending { it.playCount }
            )
    }

    private fun getJson(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LumiOne/1.0 (Android)")
            instanceFollowRedirects = true
            connectTimeout = 10000
            readTimeout = 15000
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    internal fun parseAudiusTracks(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val root = JSONObject(json)
            val data = root.optJSONArray("data") ?: return emptyList()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue

                val id = when {
                    item.has("id") && !item.isNull("id") -> item.optString("id")
                    item.has("track_id") -> item.optLong("track_id").toString()
                    else -> continue
                }
                if (id.isBlank()) continue

                val title = item.optString("title", "Untitled Track").trim()
                val userObj = item.optJSONObject("user")
                val artist = userObj?.optString("name", "Unknown Artist")?.trim() ?: "Unknown Artist"

                val durationSec = item.optLong("duration", 0L)
                val durationMs = durationSec * 1000L

                val artworkObj = item.optJSONObject("artwork")
                val thumbnail = when {
                    artworkObj?.has("480x480") == true -> artworkObj.optString("480x480")
                    artworkObj?.has("150x150") == true -> artworkObj.optString("150x150")
                    artworkObj?.has("1000x1000") == true -> artworkObj.optString("1000x1000")
                    else -> ""
                }

                val streamUrl = "$API_BASE/tracks/$id/stream?app_name=$APP_NAME"
                val playCount = item.optLong("play_count", 0L)
                val favoriteCount = item.optLong("favorite_count", 0L)
                val repostCount = item.optLong("repost_count", 0L)

                results.add(
                    SearchResult(
                        videoId = id,
                        title = if (title.isNotBlank()) title else "Untitled Track",
                        artist = if (artist.isNotBlank()) artist else "Unknown Artist",
                        durationMs = durationMs,
                        thumbnailUrl = thumbnail,
                        streamUrl = streamUrl,
                        playCount = playCount,
                        favoriteCount = favoriteCount,
                        repostCount = repostCount
                    )
                )
            }
        } catch (e: Exception) {
            // Return partial or empty results gracefully
        }
        return results
    }

    fun cancel() = ioScope.cancel()
}
