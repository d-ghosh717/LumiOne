package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Secondary Invidious fallback audio resolver for Android playback.
 * Used only when primary local yt-dlp resolver fails.
 *
 * Enforces:
 * - Direct HTTP GET to {BASE_URL}/api/v1/videos/{youtubeVideoId}
 * - Configurable BASE_URL
 * - Short timeout (5000ms) with no infinite retries
 * - Defensive JSON schema parsing (adaptiveFormats / formatStreams)
 * - Strict TrackMatcher validation against candidate title & author
 * - No cookies, no session tokens, no credentials, and no anti-bot bypasses
 */
class InvidiousAudioProvider(
    private val instances: List<String> = DEFAULT_INSTANCES,
    private val fetcher: InvidiousFetcher = DefaultInvidiousFetcher()
) : AudioProvider {

    override val providerName: String = "Invidious"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "InvidiousAudioProvider"
        private const val DEFAULT_TIMEOUT_MS = 5000

        val DEFAULT_INSTANCES = listOf(
            "https://invidious.nerdvpn.de",
            "https://inv.nadeko.net",
            "https://invidious.privacyredirect.com",
            "https://invidious.protokolla.fi",
            "https://iv.datura.network"
        )
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        val videoId = track.youtubeVideoId.ifBlank { track.videoId }
        if (videoId.isBlank()) {
            Log.w(TAG, "No video ID provided for Invidious fallback: '${track.title}'")
            return@withContext AudioStreamResult.Unavailable("Missing track video ID for Invidious fallback.")
        }

        for (baseUrl in instances) {
            val result = tryInstance(baseUrl.trimEnd('/'), videoId, track)
            if (result is AudioStreamResult.Success) {
                return@withContext result
            }
        }

        return@withContext AudioStreamResult.Unavailable("All Invidious instances failed for '${track.title}'.")
    }

    private suspend fun tryInstance(cleanedBaseUrl: String, videoId: String, track: Track): AudioStreamResult {
        try {
            Log.i(TAG, "Attempting Invidious fallback for '$videoId' ('${track.title}') via $cleanedBaseUrl")
            val jsonStr = fetcher.fetchVideoDetails(cleanedBaseUrl, videoId, DEFAULT_TIMEOUT_MS)
            if (jsonStr.isNullOrBlank()) {
                Log.w(TAG, "Invidious response empty or unavailable for videoId: $videoId via $cleanedBaseUrl")
                return AudioStreamResult.Unavailable("Invidious stream details unavailable.")
            }

            val root = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                Log.w(TAG, "Malformed Invidious JSON response for videoId: $videoId: ${e.message}")
                return AudioStreamResult.Unavailable("Malformed Invidious JSON response.")
            }

            // Extract candidate metadata defensively
            val candidateTitle = root.optString("title")
                .ifBlank { root.optString("name", track.title) }
            val candidateAuthor = root.optString("author")
                .ifBlank { root.optString("uploader", "") }
                .ifBlank { root.optString("authorName", track.artist) }

            // Strict Track Matching
            val isMatch = TrackMatcher.isReliableMatch(
                queryTitle = track.title,
                queryArtist = track.artist,
                candidateTitle = candidateTitle,
                candidateArtist = candidateAuthor
            )

            if (!isMatch) {
                Log.w(
                    TAG,
                    "Strict TrackMatcher REJECTED Invidious candidate: " +
                        "query='${track.title}' by '${track.artist}', " +
                        "candidate='$candidateTitle' by '$candidateAuthor'"
                )
                return AudioStreamResult.Unavailable(
                    "Invidious candidate metadata does not match requested track."
                )
            }

            // Find usable audio format stream
            val streamUrl = extractPlayableStreamUrl(root)
            if (streamUrl.isNullOrBlank()) {
                Log.w(TAG, "No compatible audio stream URL found in Invidious payload for videoId: $videoId")
                return AudioStreamResult.Unavailable("No playable audio stream format found in Invidious response.")
            }

            Log.i(TAG, "Successfully resolved Invidious audio fallback stream for '${track.title}' ($videoId) via $cleanedBaseUrl")
            return AudioStreamResult.Success(streamUrl = streamUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Invidious instance $cleanedBaseUrl failed: ${e.message}")
            return AudioStreamResult.Unavailable("Invidious fallback resolution error: ${e.message}")
        }
    }

    internal fun extractPlayableStreamUrl(root: JSONObject): String? {
        val adaptiveFormats = root.optJSONArray("adaptiveFormats")
        val audioUrls = mutableListOf<String>()

        if (adaptiveFormats != null) {
            for (i in 0 until adaptiveFormats.length()) {
                val format = adaptiveFormats.optJSONObject(i) ?: continue
                val url = format.optString("url").trim()
                if (url.isBlank()) continue

                val type = format.optString("type").lowercase()
                val container = format.optString("container").lowercase()
                val audioQuality = format.optString("audioQuality")
                val itag = format.optString("itag")

                // Audio-only formats (mp4a / opus)
                if (type.startsWith("audio/") || container == "m4a" || container == "webm" ||
                    audioQuality.isNotBlank() || itag in listOf("140", "251", "171", "249", "250")
                ) {
                    audioUrls.add(url)
                }
            }
        }

        if (audioUrls.isNotEmpty()) {
            return audioUrls.first()
        }

        // Fallback to formatStreams or formats array
        val formatStreams = root.optJSONArray("formatStreams") ?: root.optJSONArray("formats")
        if (formatStreams != null) {
            for (i in 0 until formatStreams.length()) {
                val format = formatStreams.optJSONObject(i) ?: continue
                val url = format.optString("url").trim()
                if (url.isNotBlank()) {
                    return url
                }
            }
        }

        return null
    }
}

/**
 * Fetcher interface for Invidious video metadata.
 */
interface InvidiousFetcher {
    suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String?
}

/**
 * Default HTTP fetcher for Invidious.
 */
class DefaultInvidiousFetcher : InvidiousFetcher {
    override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        val urlStr = "$baseUrl/api/v1/videos/$videoId"
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) LumiOne/2.0")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
            }

            val statusCode = conn.responseCode
            if (statusCode in 200..299) {
                return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w("DefaultInvidiousFetcher", "Invidious HTTP $statusCode for videoId: $videoId")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.w("DefaultInvidiousFetcher", "Invidious network error: ${e.message}")
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }
}
