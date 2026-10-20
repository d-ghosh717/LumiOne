package com.lumione.player.search

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    val youtubeVideoId: String = videoId
) {
    val trackId: String get() = videoId
}

/**
 * Authenticated Search Engine for LumiOne Player with automatic fallback.
 * Sends verified Firebase ID token to LumiOne backend to retrieve music metadata.
 * If backend search is unavailable or returns no results, seamlessly searches YouTube metadata locally.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "SearchEngine"
        private const val BACKEND_URL = "https://lumione.onrender.com"

        private val NON_MUSIC_REGEX = Regex(
            "(?i)\\b(gameplay|walkthrough|playthrough|let's play|lets play|gta\\s*[5v]|gta\\s*online|fortnite|roblox|minecraft|free\\s*fire|pubg|call\\s*of\\s*duty|valorant|apex\\s*legends|asmr|slime|satisfying|mukbang|unboxing|review|reacting|reaction|vlog|podcast|speedrun|challenge|tiktok\\s*compilation|livestream|live\\s*stream|shorts|breaking\\s*news)\\b"
        )

        fun isMusicTrack(title: String, artist: String, durationMs: Long): Boolean {
            val combined = "$title $artist"
            if (NON_MUSIC_REGEX.containsMatchIn(combined)) {
                return false
            }
            // Check duration if available: normal songs are between 40s and 15 minutes (900s)
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

        // 1. Try Authenticated Backend Search First
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$BACKEND_URL/api/search?q=$encodedQuery"
            val response = getJsonAuthenticated(url, idToken, timeoutMs = 15000)
            val backendResults = parseBackendResults(response).filter { isMusicTrack(it.title, it.artist, it.durationMs) }
            if (backendResults.isNotEmpty()) {
                Log.i(TAG, "Backend search succeeded with ${backendResults.size} music results for '$trimmed'")
                return@withContext backendResults
            }
        } catch (e: SearchAuthException) {
            throw e // propagate auth exception for token refresh handling
        } catch (e: Exception) {
            Log.w(TAG, "Backend search query failed: ${e.message}. Attempting JioSaavn / YouTube fallback.")
        }

        // 2. Fallback: Search JioSaavn metadata directly (100% music catalog)
        val saavnResults = searchJioSaavn(trimmed)
        if (saavnResults.isNotEmpty()) {
            Log.i(TAG, "JioSaavn search succeeded with ${saavnResults.size} results for '$trimmed'")
            return@withContext saavnResults
        }

        // 3. Fallback: Search YouTube metadata directly via embedded engine with music filter
        val ytResults = searchLocalYouTube("$trimmed audio").filter { isMusicTrack(it.title, it.artist, it.durationMs) }
        if (ytResults.isNotEmpty()) {
            return@withContext ytResults
        }

        return@withContext emptyList()
    }

    suspend fun getTrending(idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!idToken.isNullOrBlank()) {
            try {
                val url = "$BACKEND_URL/api/trending"
                val response = getJsonAuthenticated(url, idToken, timeoutMs = 15000)
                val results = parseBackendResults(response).filter { isMusicTrack(it.title, it.artist, it.durationMs) }
                if (results.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${results.size} trending music tracks from backend")
                    return@withContext results
                }
            } catch (e: SearchAuthException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch from backend failed: ${e.message}. Using native music discovery.")
            }
        }

        // Native music discovery: JioSaavn official charts
        val chartMusic = getTrendingMusic()
        if (chartMusic.isNotEmpty()) {
            Log.i(TAG, "Loaded ${chartMusic.size} trending music tracks from native chart discovery")
            return@withContext chartMusic
        }

        val ytTrending = searchLocalYouTube("Top Music Hits 2026").filter { isMusicTrack(it.title, it.artist, it.durationMs) }
        if (ytTrending.isNotEmpty()) return@withContext ytTrending
        return@withContext searchJioSaavn("Top Hits")
    }

    /**
     * Direct music discovery using official curated music chart playlists (Trending Today & English Top Hits).
     * Provides 100% genuine song tracks, verified artists, and 500x500 album art without auth requirement.
     */
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

            // Interleave tracks for a balanced mix of global and regional hits
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
            // ytsearch returns entries
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
                        youtubeVideoId = id
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
        }
        return results
    }

    private fun getJsonAuthenticated(urlStr: String, idToken: String, timeoutMs: Int = 10000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $idToken")
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

    fun cancel() = ioScope.cancel()
}
