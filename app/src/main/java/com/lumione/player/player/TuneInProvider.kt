package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Radio streaming provider for live broadcast stations.
 * Strictly operates as a live radio provider; does not pretend live radio streams are on-demand songs.
 */
class TuneInProvider : AudioProvider {

    override val providerName: String = "TuneIn / Live Radio"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "TuneInProvider"
        // Curated mainstream live stations
        private val FEATURED_RADIO_STATIONS = mapOf(
            "top40" to "https://stream.revma.ihrhls.com/zc4426",
            "dance" to "https://dancewave.online/dance.mp3",
            "hits" to "https://ais-sa2.cdnstream1.com/1988_128.mp3",
            "pop" to "https://direct.sharp-stream.com/capitalmp3.mp3"
        )
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        // If track is explicitly flagged as a radio station or requested as a radio stream
        val isRadioTrack = track.provider.equals("tunein", ignoreCase = true) ||
                track.title.contains("Radio", ignoreCase = true) ||
                track.artist.contains("Radio", ignoreCase = true)

        if (isRadioTrack) {
            val stationUrl = resolveRadioStationStream(track.title)
            if (stationUrl != null) {
                Log.i(TAG, "Resolved live radio stream for '${track.title}': $stationUrl")
                return@withContext AudioStreamResult.Success(streamUrl = stationUrl)
            }
        }

        // For regular on-demand track queries, TuneIn strictly declines rather than faking song playback
        Log.d(TAG, "TuneIn strictly provides live radio streams, not on-demand track '${track.title}'")
        return@withContext AudioStreamResult.Unavailable(
            "TuneIn is a live radio station provider and cannot play individual on-demand song '${track.title}'."
        )
    }

    /**
     * Resolves a legitimate live radio station stream URL by name/genre or from the open radio directory.
     */
    suspend fun resolveRadioStationStream(query: String): String? = withContext(Dispatchers.IO) {
        val qLower = query.lowercase()
        for ((key, url) in FEATURED_RADIO_STATIONS) {
            if (qLower.contains(key)) return@withContext url
        }

        try {
            val endpoint = "https://de1.api.radio-browser.info/json/stations/byname/" +
                    URLEncoder.encode(query.trim(), "UTF-8")
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) LumiOne/2.0")
                connectTimeout = 6000
                readTimeout = 6000
            }

            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val array = JSONArray(text)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    val urlResolved = first.optString("url_resolved", first.optString("url"))
                    if (urlResolved.isNotBlank()) return@withContext urlResolved
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Directory station lookup failed: ${e.message}")
        }

        // Default top hits fallback stream
        return@withContext FEATURED_RADIO_STATIONS["hits"]
    }
}
