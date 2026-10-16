package com.lumione.player.player

import androidx.media3.common.MediaItem
import com.lumione.player.queue.Track

/**
 * Result representing the resolution of a playable audio stream.
 */
sealed class AudioStreamResult {
    /**
     * Successfully resolved a playable audio stream.
     * @param streamUrl The direct HTTP/HLS audio stream URL suitable for ExoPlayer.
     * @param mediaItem Optional preconfigured Media3 MediaItem with headers/metadata.
     */
    data class Success(
        val streamUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val mediaItem: MediaItem? = null
    ) : AudioStreamResult()

    /**
     * Audio stream is unavailable from the current provider for legitimate architectural reasons
     * (e.g. metadata-only provider, unconfigured credentials, licensing restrictions).
     */
    data class Unavailable(
        val reason: String
    ) : AudioStreamResult()

    /**
     * An error occurred while attempting to resolve the audio stream.
     */
    data class Error(
        val throwable: Throwable
    ) : AudioStreamResult()
}

/**
 * Pluggable abstraction for resolving audio streams.
 * Allows LumiOne to switch audio streaming providers (e.g. Spotify, Apple Music, Audius, custom HLS backend)
 * without rewriting PlaybackService or the QueueManager.
 */
interface AudioProvider {
    /**
     * Human-readable identifier for this audio provider (e.g. "Audius", "Custom HLS", "Unavailable").
     */
    val providerName: String

    /**
     * Indicates whether this provider is currently capable of delivering audio streams.
     */
    val isAvailable: Boolean

    /**
     * Resolves a playable stream URL or MediaItem for the given track.
     */
    suspend fun resolveStreamUrl(track: Track): AudioStreamResult
}

/**
 * Default provider used when no streaming backend is connected.
 * Explicitly signals that audio streaming requires a dedicated audio provider.
 */
class UnavailableAudioProvider(
    override val providerName: String = "None",
    private val defaultReason: String = "No audio streaming provider is currently configured."
) : AudioProvider {
    override val isAvailable: Boolean = false

    override suspend fun resolveStreamUrl(track: Track): AudioStreamResult {
        return AudioStreamResult.Unavailable(
            "Audio stream unavailable: $defaultReason"
        )
    }
}

/**
 * Global registry / factory for the active AudioProvider.
 */
object AudioProviderRegistry {
    @Volatile
    private var activeProvider: AudioProvider = CompositeAudioProvider()

    fun getProvider(): AudioProvider = activeProvider

    fun registerProvider(provider: AudioProvider) {
        activeProvider = provider
    }
}
