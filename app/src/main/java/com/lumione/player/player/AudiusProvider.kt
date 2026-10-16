package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Open decentralized audio provider using Audius API for indie, remix, and cover tracks.
 */
class AudiusProvider : AudioProvider {

    override val providerName: String = "Audius"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "AudiusProvider"
        private val DISCOVERY_NODES = listOf(
            "https://discoveryprovider.audius.co",
            "https://audius-discovery-1.cultur3stake.com",
            "https://audius-metadata-5.figment.io"
        )
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = TrackMatcher.cleanTitle(track.title)
            val cleanArtist = TrackMatcher.cleanArtist(track.artist)
            val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle

            for (node in DISCOVERY_NODES) {
                try {
                    val urlStr = "$node/v1/tracks/search?query=${URLEncoder.encode(query, "UTF-8")}&app_name=LumiOne"
                    val candidates = queryAudius(urlStr)
                    if (candidates.isEmpty()) continue

                    for (candidate in candidates) {
                        if (!candidate.isStreamable) {
                            Log.d(TAG, "Candidate '${candidate.title}' is not streamable on Audius")
                            continue
                        }

                        val matches = TrackMatcher.isReliableMatch(
                            queryTitle = track.title,
                            queryArtist = track.artist,
                            candidateTitle = candidate.title,
                            candidateArtist = candidate.artistName
                        )

                        if (matches) {
                            val streamUrl = "$node/v1/tracks/${candidate.id}/stream?app_name=LumiOne"
                            Log.i(TAG, "Matched '${track.title}' -> Audius '${candidate.title}' by '${candidate.artistName}' ($streamUrl)")
                            return@withContext AudioStreamResult.Success(
                                streamUrl = streamUrl
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Discovery node $node failed: ${e.message}")
                }
            }

            Log.d(TAG, "No streamable match found on Audius for '${track.title}' by '${track.artist}'")
            return@withContext AudioStreamResult.Unavailable(
                "Audius: No streamable match found for '${track.title}' by '${track.artist}'."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving stream on Audius for '${track.title}': ${e.message}", e)
            return@withContext AudioStreamResult.Error(e)
        }
    }

    private fun queryAudius(urlStr: String): List<AudiusTrackCandidate> {
        val candidates = mutableListOf<AudiusTrackCandidate>()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) LumiOne/2.0")
            connectTimeout = 8000
            readTimeout = 8000
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return emptyList()
        }

        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONObject(jsonStr)
        val array = root.optJSONArray("data") ?: return emptyList()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = item.optString("title")
            val userObj = item.optJSONObject("user")
            val artistName = userObj?.optString("name") ?: ""
            val isStreamable = item.optBoolean("is_streamable", true)
            val durationSec = item.optLong("duration", 0L)

            if (id.isNotBlank() && title.isNotBlank()) {
                candidates.add(
                    AudiusTrackCandidate(
                        id = id,
                        title = title,
                        artistName = artistName,
                        isStreamable = isStreamable,
                        durationSec = durationSec
                    )
                )
            }
        }
        return candidates
    }

    private data class AudiusTrackCandidate(
        val id: String,
        val title: String,
        val artistName: String,
        val isStreamable: Boolean,
        val durationSec: Long
    )
}
