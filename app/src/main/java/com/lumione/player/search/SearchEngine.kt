package com.lumione.player.search

import kotlinx.coroutines.*
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
    val providerId: String = videoId
) {
    val trackId: String get() = videoId
}

/**
 * Authenticated Search Engine for LumiOne Player.
 * Sends verified Firebase ID token to LumiOne backend to retrieve music metadata.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val BACKEND_URL = "https://lumione.onrender.com"
    }

    suspend fun search(query: String, idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        if (idToken.isNullOrBlank()) {
            throw SearchAuthException("Sign in to search music.")
        }

        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
        val url = "$BACKEND_URL/api/search?q=$encodedQuery"
        val response = getJsonAuthenticated(url, idToken, timeoutMs = 18000)
        return@withContext parseBackendResults(response)
    }

    suspend fun getTrending(idToken: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        if (idToken.isNullOrBlank()) {
            throw SearchAuthException("Sign in to explore trending music.")
        }

        val url = "$BACKEND_URL/api/trending"
        val response = getJsonAuthenticated(url, idToken, timeoutMs = 18000)
        return@withContext parseBackendResults(response)
    }

    internal fun parseBackendResults(jsonStr: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", item.optString("providerId"))
                if (id.isBlank()) continue
                val title = item.optString("title", "Unknown Title")
                val artist = item.optString("artist", "Unknown Artist")
                val durationSec = item.optLong("duration", 0L)
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
                        providerId = id
                    )
                )
            }
        } catch (e: Exception) {
            // ignore JSON parse errors
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
