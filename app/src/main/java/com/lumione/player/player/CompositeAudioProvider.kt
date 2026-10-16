package com.lumione.player.player

import android.util.Log
import com.lumione.player.queue.Track

/**
 * Orchestrates multi-provider audio stream resolution in strict priority order:
 * 1. TuneIn (Radio mode only)
 * 2. YouTube (Primary high-fidelity audio stream resolver via native yt-dlp with android player_client)
 * 3. JioSaavn (Fast, reliable 320kbps CDN audio stream resolver for mainstream tracks)
 * 4. Piped (Proxied YouTube streams via Piped API instances)
 * 5. Invidious (Secondary fallback resolver for YouTube video IDs)
 * 6. Audius (Decentralized indie / community fallback)
 * 7. Clean Unavailable state if no provider holds a legitimate matching stream.
 */
class CompositeAudioProvider(
    val youtubeAudioProvider: YouTubeAudioProvider = YouTubeAudioProvider(),
    val jioSaavnAudioProvider: JioSaavnAudioProvider = JioSaavnAudioProvider(),
    val pipedAudioProvider: PipedAudioProvider = PipedAudioProvider(),
    val invidiousAudioProvider: InvidiousAudioProvider = InvidiousAudioProvider(),
    val tuneInProvider: TuneInProvider = TuneInProvider(),
    val audiusProvider: AudiusProvider = AudiusProvider()
) : AudioProvider {

    override val providerName: String = "LumiOne Hybrid Engine"
    override val isAvailable: Boolean = true

    companion object {
        private const val TAG = "CompositeAudioProvider"
        private const val LOG_TAG = "AudioProvider"
    }

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult {
        Log.i(TAG, "Resolving playback stream for: '${track.title}' by '${track.artist}' (provider=${track.provider})")

        // 1. Radio check
        if (track.provider.equals("tunein", ignoreCase = true) ||
            track.title.contains("Radio", ignoreCase = true) ||
            track.artist.contains("Radio", ignoreCase = true)
        ) {
            val radioResult = tuneInProvider.resolveStreamUrl(track)
            if (radioResult is AudioStreamResult.Success) {
                Log.i(TAG, "Successfully resolved via TuneIn radio: ${radioResult.streamUrl}")
                return radioResult
            }
        }

        // 2. Primary On-Demand: YouTube Audio Resolver (local yt-dlp with android client)
        try {
            val ytResult = youtubeAudioProvider.resolveStreamUrl(track)
            if (ytResult is AudioStreamResult.Success) {
                Log.i(LOG_TAG, "yt-dlp SUCCESS")
                return ytResult
            }
            val failReason = (ytResult as? AudioStreamResult.Unavailable)?.reason ?: "Unknown yt-dlp failure"
            Log.w(LOG_TAG, "yt-dlp FAILED: $failReason")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "yt-dlp FAILED: ${e.message}")
        }

        // 3. Fast Mainstream Fallback: JioSaavn (320kbps unblocked CDN streams)
        try {
            Log.i(LOG_TAG, "Trying JioSaavn fallback for '${track.title}'")
            val saavnResult = jioSaavnAudioProvider.resolveStreamUrl(track)
            if (saavnResult is AudioStreamResult.Success) {
                Log.i(TAG, "Resolved on-demand stream via JioSaavn fallback for '${track.title}'")
                return saavnResult
            }
            val saavnReason = (saavnResult as? AudioStreamResult.Unavailable)?.reason ?: "JioSaavn returned no match"
            Log.w(LOG_TAG, "JioSaavn failed: $saavnReason")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "JioSaavn failed: ${e.message}")
        }

        // 4. Fallback: Piped API (proxied streams)
        try {
            Log.i(LOG_TAG, "Trying Piped fallback")
            val pipedResult = pipedAudioProvider.resolveStreamUrl(track)
            if (pipedResult is AudioStreamResult.Success) {
                Log.i(TAG, "Resolved on-demand stream via Piped fallback for '${track.title}'")
                return pipedResult
            }
            val pipedReason = (pipedResult as? AudioStreamResult.Unavailable)?.reason ?: "Piped returned no playable format"
            Log.w(LOG_TAG, "Piped failed: $pipedReason")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Piped failed: ${e.message}")
        }

        // 5. Fallback: Invidious
        try {
            Log.i(LOG_TAG, "Trying Invidious fallback")
            val invidiousResult = invidiousAudioProvider.resolveStreamUrl(track)
            if (invidiousResult is AudioStreamResult.Success) {
                Log.i(TAG, "Resolved on-demand stream via Invidious fallback for '${track.title}'")
                return invidiousResult
            }
            val invReason = (invidiousResult as? AudioStreamResult.Unavailable)?.reason ?: "Invidious fallback returned no playable format"
            Log.w(LOG_TAG, "Invidious failed: $invReason")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Invidious failed: ${e.message}")
        }

        // 6. Tertiary Fallback: Audius
        try {
            val audiusResult = audiusProvider.resolveStreamUrl(track)
            if (audiusResult is AudioStreamResult.Success) {
                Log.i(TAG, "Resolved stream via Audius fallback for '${track.title}'")
                return audiusResult
            }
        } catch (e: Exception) {
            Log.d(TAG, "Audius fallback check skipped: ${e.message}")
        }

        // 7. Clean Unavailable State
        Log.w(LOG_TAG, "Playback unavailable")
        return AudioStreamResult.Unavailable(
            "Unable to start playback: Track '${track.title}' is currently unavailable."
        )
    }
}
