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
            val backendResults = parseBackendResults(response)
            if (backendResults.isNotEmpty()) {
                Log.i(TAG, "Backend search succeeded with ${backendResults.size} results for '$trimmed'")
                return@withContext backendResults
            }
        } catch (e: SearchAuthException) {
            throw e // propagate auth exception for token refresh handling
        } catch (e: Exception) {
            Log.w(TAG, "Backend search query failed: ${e.message}. Attempting local YouTube metadata fallback.")
        }

        // 2. Fallback: Search YouTube metadata directly via embedded engine
        return@withContext searchLocalYouTube(trimmed)
    }

    suspend fun getTrending(idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (idToken.isNullOrBlank()) {
            throw SearchAuthException("Sign in to explore trending music.")
        }

        try {
            val url = "$BACKEND_URL/api/trending"
            val response = getJsonAuthenticated(url, idToken, timeoutMs = 15000)
            val results = parseBackendResults(response)
            if (results.isNotEmpty()) {
                return@withContext results
            }
        } catch (e: SearchAuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trending fetch from backend failed: ${e.message}. Fallback to top hits.")
        }

        return@withContext searchLocalYouTube("Top Hits 2026")
    }

    private suspend fun searchLocalYouTube(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val request = YoutubeDLRequest("ytsearch10:$query").apply {
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
