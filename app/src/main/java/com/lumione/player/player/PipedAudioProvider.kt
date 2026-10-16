package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Piped API fallback audio resolver for LumiOne.
 * Uses public Piped instances to resolve YouTube audio streams when yt-dlp fails.
 *
 * Piped proxies stream URLs through its own servers, which avoids the
 * googlevideo CDN header/IP-binding issues that cause HTTP 403 with yt-dlp URLs.
 *
 * Enforces:
 * - Multiple instance rotation for resilience
 * - Audio-only stream selection (highest bitrate m4a/opus)
 * - Strict TrackMatcher validation
 * - No cookies, no session tokens, no credentials
 */
class PipedAudioProvider(
    private val instances: List<String> = DEFAULT_INSTANCES
) : AudioProvider {

    override val providerName: String = "Piped"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "PipedAudioProvider"
        private const val TIMEOUT_MS = 8000

        val DEFAULT_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.lunar.icu",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.private.coffee"
        )
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        val videoId = track.youtubeVideoId.ifBlank { track.videoId }
        if (videoId.isBlank()) {
            return@withContext AudioStreamResult.Unavailable("Missing video ID for Piped fallback.")
        }

        for (instance in instances) {
            try {
                Log.i(TAG, "Trying Piped instance: $instance for videoId=$videoId")
                val result = tryInstance(instance, videoId, track)
                if (result is AudioStreamResult.Success) {
                    Log.i(TAG, "Piped SUCCESS via $instance for '${track.title}'")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $instance failed: ${e.message}")
            }
        }

        return@withContext AudioStreamResult.Unavailable("All Piped instances failed for '${track.title}'.")
    }

    private fun tryInstance(instanceUrl: String, videoId: String, track: Track): AudioStreamResult {
        val apiUrl = "${instanceUrl.trimEnd('/')}/streams/$videoId"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LumiOne/2.0 (Android)")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }

        try {
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                Log.w(TAG, "Piped HTTP $statusCode from $instanceUrl")
                return AudioStreamResult.Unavailable("Piped HTTP $statusCode")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            // Validate track metadata
            val candidateTitle = json.optString("title", "")
            val candidateArtist = json.optString("uploader", "")

            if (candidateTitle.isNotBlank()) {
                val isMatch = TrackMatcher.isReliableMatch(
                    queryTitle = track.title,
                    queryArtist = track.artist,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateArtist
                )
                if (!isMatch) {
                    Log.w(TAG, "TrackMatcher REJECTED Piped candidate: '${candidateTitle}' by '$candidateArtist'")
                    return AudioStreamResult.Unavailable("Piped candidate did not match.")
                }
            }

            // Extract best audio stream
            val audioStreams = json.optJSONArray("audioStreams")
            if (audioStreams == null || audioStreams.length() == 0) {
                return AudioStreamResult.Unavailable("No audio streams in Piped response.")
            }

            // Find highest bitrate audio stream, preferring m4a/mp4 for ExoPlayer compatibility
            var bestUrl: String? = null
            var bestBitrate = -1
            var bestFormat = ""

            for (i in 0 until audioStreams.length()) {
                val stream = audioStreams.optJSONObject(i) ?: continue
                val url = stream.optString("url", "").trim()
                if (url.isBlank()) continue

                val bitrate = stream.optInt("bitrate", 0)
                val mimeType = stream.optString("mimeType", "").lowercase()
                val format = stream.optString("format", "").lowercase()
                val codec = stream.optString("codec", "").lowercase()

                // Prefer m4a/mp4 for best ExoPlayer compatibility, then webm/opus
                val formatScore = when {
                    mimeType.contains("audio/mp4") || format == "m4a" -> 2
                    mimeType.contains("audio/webm") || format == "webm" || codec.contains("opus") -> 1
                    mimeType.startsWith("audio/") -> 0
                    else -> -1
                }

                if (formatScore >= 0) {
                    val effectiveScore = bitrate + (formatScore * 1000000)
                    if (effectiveScore > bestBitrate) {
                        bestBitrate = effectiveScore
                        bestUrl = url
                        bestFormat = "$mimeType ($format)"
                    }
                }
            }

            if (bestUrl.isNullOrBlank()) {
                return AudioStreamResult.Unavailable("No compatible audio format in Piped response.")
            }

            Log.i(TAG, "Selected Piped audio stream: format=$bestFormat bitrate=$bestBitrate")
            // Piped proxy URLs don't need special headers — they're proxied through the instance
            return AudioStreamResult.Success(streamUrl = bestUrl)
        } finally {
            conn.disconnect()
        }
    }
}
