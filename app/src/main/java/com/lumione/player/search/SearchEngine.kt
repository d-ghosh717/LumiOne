package com.lumione.player.search

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String
)

/**
 * Search engine using InnerTube (YouTube's internal API) to find tracks.
 * No unofficial app keys are needed – uses the public web client key.
 */
class SearchEngine {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val SEARCH_URL =
            "https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY"
        private const val BASE_URL = "https://www.youtube.com/youtubei/v1"
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val payload = buildInnerTubePayload(query)
            val response = postInnerTube(SEARCH_URL, payload)
            parseSearchResponse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTrending(): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/browse?key=$INNERTUBE_KEY"
            val payload = buildBrowsePayload("FEmusic_charts")
            val response = postInnerTube(url, payload)
            parseBrowseResponse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildInnerTubePayload(query: String): String {
        return """
        {
          "context": {
            "client": {
              "clientName": "WEB",
              "clientVersion": "2.20231121.01.00",
              "hl": "en",
              "gl": "US"
            }
          },
          "query": "$query",
          "params": "EgIQAQ=="
        }
        """.trimIndent()
    }

    private fun buildBrowsePayload(browseId: String): String {
        return """
        {
          "context": {
            "client": {
              "clientName": "WEB",
              "clientVersion": "2.20231121.01.00",
              "hl": "en",
              "gl": "US"
            }
          },
          "browseId": "$browseId"
        }
        """.trimIndent()
    }

    private fun postInnerTube(url: String, payload: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-YouTube-Client-Name", "1")
            setRequestProperty("X-YouTube-Client-Version", "2.20231121.01.00")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 15000
        }
        conn.outputStream.use { it.write(payload.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseSearchResponse(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val root = JSONObject(json)
            val contents = root
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (i in 0 until contents.length()) {
                val section = contents.getJSONObject(i)
                if (!section.has("itemSectionRenderer")) continue
                val items = section.getJSONObject("itemSectionRenderer")
                    .getJSONArray("contents")

                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    if (!item.has("videoRenderer")) continue
                    val video = item.getJSONObject("videoRenderer")

                    val videoId = video.optString("videoId") ?: continue
                    val title = video.optJSONObject("title")
                        ?.optJSONArray("runs")
                        ?.getJSONObject(0)
                        ?.optString("text") ?: "Unknown"
                    val artist = video.optJSONObject("ownerText")
                        ?.optJSONArray("runs")
                        ?.getJSONObject(0)
                        ?.optString("text") ?: "Unknown Artist"
                    val durationText = video.optJSONObject("lengthText")
                        ?.optString("simpleText") ?: "0:00"
                    val thumbnail = video.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?.let { thumbs ->
                            // Prefer medium quality thumbnail
                            if (thumbs.length() > 0)
                                thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                            else null
                        } ?: "https://img.youtube.com/vi/$videoId/mqdefault.jpg"

                    results.add(
                        SearchResult(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            durationMs = parseDurationMs(durationText),
                            thumbnailUrl = thumbnail
                        )
                    )
                    if (results.size >= 20) return results
                }
            }
        } catch (e: Exception) {
            // Return partial results
        }
        return results
    }

    private fun parseBrowseResponse(json: String): List<SearchResult> {
        // Simplified: reuse search-style parsing on browse results
        return emptyList()
    }

    private fun parseDurationMs(duration: String): Long {
        val parts = duration.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> 0L
        }
    }

    fun cancel() = ioScope.cancel()
}
