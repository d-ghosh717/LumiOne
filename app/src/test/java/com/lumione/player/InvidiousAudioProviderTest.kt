package com.lumione.player

import com.lumione.player.player.AudioStreamResult
import com.lumione.player.player.InvidiousAudioProvider
import com.lumione.player.player.InvidiousFetcher
import com.lumione.player.queue.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InvidiousAudioProviderTest {

    private val validFadedJson = """
        {
          "title": "Alan Walker - Faded",
          "author": "Alan Walker",
          "lengthSeconds": 213,
          "adaptiveFormats": [
            {
              "type": "audio/webm; codecs=\"opus\"",
              "url": "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251&source=invidious",
              "itag": "251",
              "bitrate": "130000",
              "audioQuality": "AUDIO_QUALITY_MEDIUM"
            },
            {
              "type": "audio/mp4; codecs=\"mp4a.40.2\"",
              "url": "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?itag=140&source=invidious",
              "itag": "140",
              "bitrate": "128000"
            }
          ]
        }
    """.trimIndent()

    private val mismatchRemixJson = """
        {
          "title": "Alan Walker - Faded (OneUpped Remix)",
          "author": "OneUp Music",
          "lengthSeconds": 190,
          "adaptiveFormats": [
            {
              "type": "audio/webm; codecs=\"opus\"",
              "url": "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
              "itag": "251"
            }
          ]
        }
    """.trimIndent()

    private val noAudioFormatsJson = """
        {
          "title": "Alan Walker - Faded",
          "author": "Alan Walker",
          "lengthSeconds": 213,
          "adaptiveFormats": []
        }
    """.trimIndent()

    @Test
    fun testValidInvidiousResponseResolvesSuccessfully() = runBlocking {
        val mockFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String {
                assertEquals("https://invidious.nerdvpn.de", baseUrl)
                assertEquals("60ItHLz5WEA", videoId)
                return validFadedJson
            }
        }

        val provider = InvidiousAudioProvider(fetcher = mockFetcher)
        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Valid Invidious response must resolve to Success", result is AudioStreamResult.Success)
        val success = result as AudioStreamResult.Success
        assertEquals("https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251&source=invidious", success.streamUrl)
    }

    @Test
    fun testInvidiousMetadataMismatchRejectsCandidate() = runBlocking {
        val mockFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String {
                return mismatchRemixJson
            }
        }

        val provider = InvidiousAudioProvider(fetcher = mockFetcher)
        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Mismatched Invidious metadata must be rejected", result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testMalformedJsonFailsCleanly() = runBlocking {
        val mockFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String {
                return "{ not-valid-json ]"
            }
        }

        val provider = InvidiousAudioProvider(fetcher = mockFetcher)
        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Malformed JSON must return Unavailable", result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testMissingAudioFormatsFailsCleanly() = runBlocking {
        val mockFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String {
                return noAudioFormatsJson
            }
        }

        val provider = InvidiousAudioProvider(fetcher = mockFetcher)
        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Missing audio formats must return Unavailable", result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testNetworkErrorOrNullResponseFailsCleanly() = runBlocking {
        val mockFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String? {
                return null
            }
        }

        val provider = InvidiousAudioProvider(fetcher = mockFetcher)
        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Null response must return Unavailable", result is AudioStreamResult.Unavailable)
    }
}
