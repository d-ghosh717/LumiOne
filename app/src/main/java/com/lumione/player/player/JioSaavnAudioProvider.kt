package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

interface JioSaavnFetcher {
    suspend fun searchSongs(query: String): String?
}

class DefaultJioSaavnFetcher : JioSaavnFetcher {
    override suspend fun searchSongs(query: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&p=1&n=5&q=$encodedQuery"
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w("JioSaavnFetcher", "HTTP error ${connection.responseCode} querying JioSaavn")
                null
            }
        } catch (e: Exception) {
            Log.w("JioSaavnFetcher", "Network failure querying JioSaavn: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Audio streaming provider backed by JioSaavn's public CDN audio infrastructure.
 * Resolves mainstream Indian and international music tracks into direct 320kbps MP4/AAC streams
 * with zero authentication, no cookies, no bot-checks, and no rate limits.
 */
class JioSaavnAudioProvider(
    private val fetcher: JioSaavnFetcher = DefaultJioSaavnFetcher()
) : AudioProvider {

    override val providerName: String = "JioSaavn"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "JioSaavnAudioProvider"
        private const val DES_KEY = "38346591"

        /**
         * Decrypts encrypted_media_url from JioSaavn API using DES-ECB.
         */
        fun decryptUrl(encryptedMediaUrl: String): String? {
            return try {
                val keySpec = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                val decodedBytes = decodeBase64(encryptedMediaUrl)
                val decryptedBytes = cipher.doFinal(decodedBytes)
                val rawUrl = String(decryptedBytes, Charsets.UTF_8)
                // Prefer 320kbps high fidelity stream if available
                rawUrl.replace(Regex("_(96|160)\\.mp4"), "_320.mp4")
            } catch (e: Exception) {
                Log.e(TAG, "DES Decryption failed: ${e.message}")
                null
            }
        }

        private fun decodeBase64(input: String): ByteArray {
            return try {
                android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(input)
            }
        }
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        val cleanTitle = TrackMatcher.cleanTitle(track.title)
        val cleanArtist = TrackMatcher.cleanArtist(track.artist)
        val searchQuery = if (cleanArtist.isNotBlank() && !cleanTitle.contains(cleanArtist)) {
            "$cleanTitle $cleanArtist".trim()
        } else {
            cleanTitle
        }

        if (searchQuery.isBlank()) {
            return@withContext AudioStreamResult.Unavailable("Missing title for JioSaavn stream resolution.")
        }

        try {
            Log.i(TAG, "Resolving stream via JioSaavn for '$searchQuery' (original: '${track.title}')")
            val jsonResponse = fetcher.searchSongs(searchQuery)
            if (jsonResponse.isNullOrBlank()) {
                return@withContext AudioStreamResult.Unavailable("JioSaavn search returned empty response for '$searchQuery'.")
            }

            val json = JSONObject(jsonResponse)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return@withContext AudioStreamResult.Unavailable("No songs found on JioSaavn for '$searchQuery'.")
            }

            for (i in 0 until results.length()) {
                val songObj = results.getJSONObject(i)
                val candidateTitle = songObj.optString("song", songObj.optString("title", ""))
                val candidateArtist = songObj.optString("singers", songObj.optString("primary_artists", ""))
                val encryptedUrl = songObj.optString("encrypted_media_url", "")

                if (encryptedUrl.isBlank()) continue

                val isMatch = TrackMatcher.isReliableMatch(
                    queryTitle = track.title,
                    queryArtist = track.artist,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateArtist
                )

                if (isMatch) {
                    val streamUrl = decryptUrl(encryptedUrl)
                    if (!streamUrl.isNullOrBlank()) {
                        Log.i(TAG, "Successfully resolved 320k JioSaavn stream for '${track.title}' -> $candidateTitle")
                        return@withContext AudioStreamResult.Success(
                            streamUrl = streamUrl,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        )
                    }
                }
            }

            // Top candidate fuzzy fallback if strict match rejected minor title formatting
            val topSong = results.getJSONObject(0)
            val topEncryptedUrl = topSong.optString("encrypted_media_url", "")
            if (topEncryptedUrl.isNotBlank()) {
                val topTitle = topSong.optString("song", topSong.optString("title", ""))
                val cleanTop = TrackMatcher.cleanTitle(topTitle)
                if (cleanTop.contains(cleanTitle, ignoreCase = true) || cleanTitle.contains(cleanTop, ignoreCase = true)) {
                    val streamUrl = decryptUrl(topEncryptedUrl)
                    if (!streamUrl.isNullOrBlank()) {
                        Log.i(TAG, "Resolved top JioSaavn candidate for '${track.title}' -> $topTitle")
                        return@withContext AudioStreamResult.Success(
                            streamUrl = streamUrl,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        )
                    }
                }
            }

            return@withContext AudioStreamResult.Unavailable("No matching candidate with playable audio found on JioSaavn for '$searchQuery'.")
        } catch (e: Exception) {
            Log.e(TAG, "JioSaavn resolution error for '${track.title}': ${e.message}", e)
            return@withContext AudioStreamResult.Unavailable("JioSaavn error: ${e.message}")
        }
    }
}
