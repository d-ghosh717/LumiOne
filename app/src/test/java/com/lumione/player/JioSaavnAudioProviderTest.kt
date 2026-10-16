package com.lumione.player

import com.lumione.player.player.AudioStreamResult
import com.lumione.player.player.JioSaavnAudioProvider
import com.lumione.player.player.JioSaavnFetcher
import com.lumione.player.queue.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JioSaavnAudioProviderTest {

    private class MockJioSaavnFetcher(private val jsonResponse: String?) : JioSaavnFetcher {
        override suspend fun searchSongs(query: String): String? = jsonResponse
    }

    @Test
    fun testDesDecryption() {
        // Valid encrypted URL for "Nazm Nazm"
        val encrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyfS3a24sXBbfDtlRrUqw4sAML4GkKJ3Od04V9Dj+tNa5YcmT3Ug+J+Bw7tS9a8Gtq"
        val decrypted = JioSaavnAudioProvider.decryptUrl(encrypted)

        assertNotNull(decrypted)
        assertTrue("Decrypted URL should point to saavncdn", decrypted!!.contains("saavncdn.com"))
        assertTrue("Decrypted URL should prefer 320k quality", decrypted.endsWith("_320.mp4"))
    }

    @Test
    fun testSuccessfulStreamResolution() = runBlocking {
        val mockJson = """
            {
                "results": [
                    {
                        "id": "12345",
                        "song": "Nazm Nazm",
                        "singers": "Arko",
                        "encrypted_media_url": "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyfS3a24sXBbfDtlRrUqw4sAML4GkKJ3Od04V9Dj+tNa5YcmT3Ug+J+Bw7tS9a8Gtq"
                    }
                ]
            }
        """.trimIndent()

        val provider = JioSaavnAudioProvider(fetcher = MockJioSaavnFetcher(mockJson))
        val track = Track(
            videoId = "sampleId",
            title = "Nazm Nazm",
            artist = "Arko"
        )

        val result = provider.resolveStreamUrl(track)
        assertTrue("Expected AudioStreamResult.Success, got $result", result is AudioStreamResult.Success)
        val success = result as AudioStreamResult.Success
        assertTrue(success.streamUrl.contains("saavncdn.com"))
    }

    @Test
    fun testNoResultsReturnsUnavailable() = runBlocking {
        val mockJson = """{"results": []}"""
        val provider = JioSaavnAudioProvider(fetcher = MockJioSaavnFetcher(mockJson))
        val track = Track(
            videoId = "sampleId",
            title = "NonExistentTrack999",
            artist = "Unknown"
        )

        val result = provider.resolveStreamUrl(track)
        assertTrue(result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testEmptyResponseReturnsUnavailable() = runBlocking {
        val provider = JioSaavnAudioProvider(fetcher = MockJioSaavnFetcher(null))
        val track = Track(
            videoId = "sampleId",
            title = "Faded",
            artist = "Alan Walker"
        )

        val result = provider.resolveStreamUrl(track)
        assertTrue(result is AudioStreamResult.Unavailable)
    }
}
