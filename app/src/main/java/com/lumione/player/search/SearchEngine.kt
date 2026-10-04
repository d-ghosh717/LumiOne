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
    val streamUrl: String = ""
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
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$API_BASE/tracks/search?query=$encodedQuery&app_name=$APP_NAME"
            val response = getJson(url)
            parseAudiusTracks(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTrending(): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/tracks/trending?app_name=$APP_NAME&limit=25"
            val response = getJson(url)
            parseAudiusTracks(response)
        } catch (e: Exception) {
            emptyList()
        }
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

                results.add(
                    SearchResult(
                        videoId = id,
                        title = if (title.isNotBlank()) title else "Untitled Track",
                        artist = if (artist.isNotBlank()) artist else "Unknown Artist",
                        durationMs = durationMs,
                        thumbnailUrl = thumbnail,
                        streamUrl = streamUrl
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
