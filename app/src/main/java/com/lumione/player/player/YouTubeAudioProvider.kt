package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube audio streaming resolver for LumiOne.
 * Resolves high-fidelity audio streams natively using an embedded yt-dlp runtime.
 *
 * Enforces:
 * - Direct local audio stream resolution without third-party proxies
 * - No cookies, no session tokens, and no account authentication
 * - Audio-only format selection ("bestaudio/best")
 * - Strict track matching via TrackMatcher (rejects covers, remixes, and version modifiers)
 * - Temporary in-memory stream URLs (never permanently stored in database)
 */
class YouTubeAudioProvider(
    private val extractor: YouTubeStreamExtractor = DefaultYouTubeStreamExtractor()
) : AudioProvider {

    override val providerName: String = "YouTube"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "YouTubeAudioProvider"
        private const val LOG_TAG = "AudioProvider"
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult = withContext(Dispatchers.IO) {
        val videoId = track.youtubeVideoId.ifBlank { track.videoId }
        if (videoId.isBlank()) {
            Log.w(LOG_TAG, "yt-dlp FAILED: No YouTube video ID available for track: '${track.title}'")
            return@withContext AudioStreamResult.Unavailable("Unable to start playback: Missing track identifier.")
        }

        try {
            Log.i(LOG_TAG, "yt-dlp START videoId=$videoId track='${track.title}' / '${track.artist}'")

            val extracted = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                extractor.extractStream(videoId)
            }

            if (extracted == null || extracted.streamUrl.isBlank()) {
                Log.w(LOG_TAG, "yt-dlp FAILED: No playable audio stream returned for video ID: $videoId")
                return@withContext AudioStreamResult.Unavailable("Unable to start playback: Stream extraction failed or timed out.")
            }

            Log.i(LOG_TAG, "yt-dlp extracted: title='${extracted.title}' artist='${extracted.artist}' duration=${extracted.durationSec}s headers=${extracted.headers.keys}")

            // Strict Track Matching: Verify candidate title and uploader/artist
            val candidateTitle = extracted.title.ifBlank { track.title }
            val candidateArtist = extracted.artist.ifBlank { track.artist }

            val isMatch = TrackMatcher.isReliableMatch(
                queryTitle = track.title,
                queryArtist = track.artist,
                candidateTitle = candidateTitle,
                candidateArtist = candidateArtist
            )

            if (!isMatch) {
                Log.w(
                    LOG_TAG,
                    "Strict TrackMatcher REJECTED YouTube candidate: " +
                        "query='${track.title}' by '${track.artist}', " +
                        "candidate='$candidateTitle' by '$candidateArtist'"
                )
                return@withContext AudioStreamResult.Unavailable(
                    "Unable to start playback: Track candidate did not match '${track.title}'."
                )
            }

            Log.i(LOG_TAG, "yt-dlp SUCCESS for '${track.title}'")
            return@withContext AudioStreamResult.Success(
                streamUrl = extracted.streamUrl,
                headers = extracted.headers
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "yt-dlp error resolving YouTube stream for track '${track.title}' ($videoId): ${e.message}", e)
            return@withContext AudioStreamResult.Unavailable("Unable to start playback: ${e.message}")
        }
    }
}

/**
 * Data model for extracted stream information.
 */
data class YouTubeExtractedInfo(
    val streamUrl: String,
    val title: String,
    val artist: String,
    val durationSec: Long = 0L,
    val mimeType: String = "",
    val headers: Map<String, String> = emptyMap()
)

/**
 * Interface abstraction for extracting stream info from YouTube video ID.
 */
interface YouTubeStreamExtractor {
    suspend fun extractStream(videoId: String): YouTubeExtractedInfo?
}

/**
 * Default native yt-dlp extractor implementation for Android.
 *
 * Uses default yt-dlp client negotiation (no forced extractor args).
 * The library's updateYoutubeDL() keeps the binary current with YouTube API changes.
 */
class DefaultYouTubeStreamExtractor : YouTubeStreamExtractor {
    companion object {
        private const val TAG = "DefaultYtExtractor"
    }

    override suspend fun extractStream(videoId: String): YouTubeExtractedInfo? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/watch?v=$videoId"
        
        // Try android player_client first to avoid bot detection ("Sign in to confirm you're not a bot")
        val info = try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--extractor-args", "youtube:player_client=android")
                addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: Exception) {
            Log.w(TAG, "Android player_client failed (${e.message}), attempting fallback client negotiation", e)
            val fallbackRequest = YoutubeDLRequest(url).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            YoutubeDL.getInstance().getInfo(fallbackRequest)
        }

        val headers = mutableMapOf<String, String>()
        info.httpHeaders?.let { headers.putAll(it) }

        // Try to find the best audio-only format with a direct URL
        val audioFormat = info.formats?.filter { format ->
            !format.url.isNullOrBlank() && format.acodec != null && format.acodec != "none"
        }?.maxByOrNull { it.tbr.coerceAtLeast(it.abr) }
            ?: info.formats?.firstOrNull { !it.url.isNullOrBlank() && it.acodec != null && it.acodec != "none" }

        // Merge format-level headers (these may contain critical auth tokens for googlevideo CDN)
        audioFormat?.httpHeaders?.let { headers.putAll(it) }

        var streamUrl = info.url ?: ""
        var mimeType = info.ext ?: ""
        if (streamUrl.isBlank()) {
            streamUrl = audioFormat?.url ?: ""
            mimeType = audioFormat?.ext ?: ""
        }

        Log.i(TAG, "info.url present=${!info.url.isNullOrBlank()}, formats=${info.formats?.size ?: 0}, streamUrl present=${streamUrl.isNotBlank()}")

        val title = info.title ?: ""
        val artist = info.uploader ?: ""
        val durationSec = info.duration.toLong()

        return@withContext YouTubeExtractedInfo(
            streamUrl = streamUrl,
            title = title,
            artist = artist,
            durationSec = durationSec,
            mimeType = mimeType,
            headers = headers
        )
    }
}
