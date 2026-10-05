package com.lumione.player.search

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val videoId: String,
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
}

/**
 * YouTube Search Engine for LumiOne Player.
 * Retrieves real YouTube video IDs and metadata via backend / Invidious instances.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val BACKEND_URL = "https://lumione.onrender.com"
        private val INVIDIOUS_FALLBACKS = listOf(
            "https://invidious.f5.si",
            "https://invidious.nerdvpn.de",
            "https://inv.thepixora.com",
            "https://yt.chocolatemoo53.com"
        )
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try Backend API Search first
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$BACKEND_URL/api/search?q=$encodedQuery"
            val response = getJson(url, timeoutMs = 8000)
            val parsed = parseBackendResults(response)
            if (parsed.isNotEmpty()) return@withContext parsed
        } catch (e: Exception) {
            // Fall back to direct Invidious search
        }

        // 2. Direct Invidious Instance Fallback
        for (instance in INVIDIOUS_FALLBACKS) {
            try {
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val url = "$instance/api/v1/search?q=$encodedQuery&type=video&fields=videoId,title,author,lengthSeconds,videoThumbnails"
                val response = getJson(url, timeoutMs = 6000)
                val parsed = parseInvidiousDirect(response)
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                continue
            }
        }

        emptyList()
    }

    suspend fun getTrending(): List<SearchResult> = withContext(Dispatchers.IO) {
        // 1. Try Backend Trending
        try {
            val url = "$BACKEND_URL/api/trending"
            val response = getJson(url, timeoutMs = 7000)
            val parsed = parseBackendResults(response)
            if (parsed.isNotEmpty()) return@withContext parsed
        } catch (e: Exception) {
            // Fall through
        }

        // 2. Fallback: Search top popular songs
        search("Top Hit Songs 2026")
    }

    internal fun parseBackendResults(jsonStr: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val title = item.optString("title", "Unknown Title")
                val artist = item.optString("artist", "Unknown Artist")
                val durationSec = item.optLong("duration", 0L)
                val thumbnail = item.optString("thumbnail")
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
                        thumbnailUrl = thumb
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return results
    }

    internal fun parseInvidiousDirect(jsonStr: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("videoId")
                if (id.isBlank()) continue
                val title = item.optString("title", "Unknown Title")
                val artist = item.optString("author", "Unknown Artist")
                val durationSec = item.optLong("lengthSeconds", 0L)

                val thumbs = item.optJSONArray("videoThumbnails")
                var thumbUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                if (thumbs != null && thumbs.length() > 0) {
                    val first = thumbs.optJSONObject(0)
                    if (first != null && first.has("url")) {
                        thumbUrl = first.optString("url")
                    }
                }

                results.add(
                    SearchResult(
                        videoId = id,
                        title = title,
                        artist = artist,
                        durationMs = durationSec * 1000L,
                        thumbnailUrl = thumbUrl
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return results
    }

    private fun getJson(urlStr: String, timeoutMs: Int = 10000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) LumiOne/1.0")
            instanceFollowRedirects = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun cancel() = ioScope.cancel()
}
