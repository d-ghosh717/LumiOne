package com.lumione.player

import com.lumione.player.player.*
import com.lumione.player.queue.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioProviderTest {

    @Test
    fun testDefaultProviderIsComposite() {
        val provider = AudioProviderRegistry.getProvider()
        assertTrue(provider.isAvailable)
        assertEquals("LumiOne Hybrid Engine", provider.providerName)
    }

    @Test
    fun testTuneInRadioProvider() = runBlocking {
        val tuneIn = TuneInProvider()
        assertTrue(tuneIn.isAvailable)

        // Radio query succeeds
        val radioTrack = Track(
            videoId = "radio_top40",
            title = "Top40 Radio Live",
            artist = "Mainstream Radio",
            provider = "tunein"
        )
        val radioResult = tuneIn.resolveStreamUrl(radioTrack)
        assertTrue(radioResult is AudioStreamResult.Success)
        assertTrue((radioResult as AudioStreamResult.Success).streamUrl.isNotBlank())

        // Non-radio on-demand track is gracefully declined
        val songTrack = Track(
            videoId = "song_123",
            title = "Blinding Lights",
            artist = "The Weeknd"
        )
        val songResult = tuneIn.resolveStreamUrl(songTrack)
        assertTrue(songResult is AudioStreamResult.Unavailable)
    }

    @Test
    fun testUnavailableTrackResolution() = runBlocking {
        val provider = CompositeAudioProvider()
        val nonExistentTrack = Track(
            videoId = "fake_id_9999",
            title = "NonExistentTrackTitle_XYZ_9999",
            artist = "NonExistentArtist_ABC_8888"
        )

        val result = provider.resolveStreamUrl(nonExistentTrack)
        assertTrue(result is AudioStreamResult.Unavailable)
        val unavailable = result as AudioStreamResult.Unavailable
        assertTrue(unavailable.reason.contains("unavailable", ignoreCase = true) || unavailable.reason.contains("Unable", ignoreCase = true))
    }

    @Test
    fun testCustomProviderRegistration() = runBlocking {
        val mockProvider = object : AudioProvider {
            override val providerName: String = "MockAudio"
            override val isAvailable: Boolean = true

            override suspend fun resolveStreamUrl(track: Track): AudioStreamResult {
                return AudioStreamResult.Success(streamUrl = "https://mock.audio.stream/${track.videoId}.m4a")
            }
        }

        AudioProviderRegistry.registerProvider(mockProvider)
        assertEquals("MockAudio", AudioProviderRegistry.getProvider().providerName)

        val track = Track(
            videoId = "track_123",
            title = "Test Song",
            artist = "Test Artist"
        )
        val result = AudioProviderRegistry.getProvider().resolveStreamUrl(track)
        assertTrue(result is AudioStreamResult.Success)
        assertEquals("https://mock.audio.stream/track_123.m4a", (result as AudioStreamResult.Success).streamUrl)

        // Reset to default CompositeAudioProvider
        AudioProviderRegistry.registerProvider(CompositeAudioProvider())
    }
}
