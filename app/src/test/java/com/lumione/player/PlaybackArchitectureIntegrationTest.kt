package com.lumione.player

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lumione.player.player.*
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlaybackArchitectureIntegrationTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var compositeProvider: CompositeAudioProvider
    private lateinit var queueManager: QueueManager

    private val sampleExtracted = mapOf(
        "60ItHLz5WEA" to YouTubeExtractedInfo(
            streamUrl = "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "Alan Walker - Faded (Official Music Video)",
            artist = "Alan Walker",
            durationSec = 212L
        ),
        "4NRXx6U8ABQ" to YouTubeExtractedInfo(
            streamUrl = "https://rr3---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "The Weeknd - Blinding Lights (Official Video)",
            artist = "The Weeknd",
            durationSec = 260L
        ),
        "dqt8Z1k0oWQ" to YouTubeExtractedInfo(
            streamUrl = "https://rr1---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "The Weeknd - Starboy (Official Music Video) ft. Daft Punk",
            artist = "The Weeknd",
            durationSec = 230L
        ),
        "2Vv-BfVoq4g" to YouTubeExtractedInfo(
            streamUrl = "https://rr4---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "Ed Sheeran - Perfect (Official Music Video)",
            artist = "Ed Sheeran",
            durationSec = 263L
        ),
        "7wtfhZwyrcc" to YouTubeExtractedInfo(
            streamUrl = "https://rr5---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "Imagine Dragons - Believer (Official Music Video)",
            artist = "Imagine Dragons",
            durationSec = 217L
        ),
        "IcrbM1l_BoI" to YouTubeExtractedInfo(
            streamUrl = "https://rr6---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251",
            title = "Avicii - Wake Me Up (Official Video)",
            artist = "Avicii",
            durationSec = 273L
        )
    )

    private val testTracks = listOf(
        Track(
            videoId = "60ItHLz5WEA",
            title = "Faded",
            artist = "Alan Walker"
        ),
        Track(
            videoId = "4NRXx6U8ABQ",
            title = "Blinding Lights",
            artist = "The Weeknd"
        ),
        Track(
            videoId = "dqt8Z1k0oWQ",
            title = "Starboy",
            artist = "The Weeknd"
        ),
        Track(
            videoId = "2Vv-BfVoq4g",
            title = "Perfect",
            artist = "Ed Sheeran"
        ),
        Track(
            videoId = "7wtfhZwyrcc",
            title = "Believer",
            artist = "Imagine Dragons"
        ),
        Track(
            videoId = "IcrbM1l_BoI",
            title = "Wake Me Up",
            artist = "Avicii"
        )
    )

    @Before
    fun setup() {
        val mockYtExtractor = object : YouTubeStreamExtractor {
            override suspend fun extractStream(videoId: String): YouTubeExtractedInfo? {
                return sampleExtracted[videoId]
            }
        }
        val ytProvider = YouTubeAudioProvider(extractor = mockYtExtractor)
        compositeProvider = CompositeAudioProvider(youtubeAudioProvider = ytProvider)
        queueManager = QueueManager()
    }

    @Test
    fun testSixTargetTracksFullResolutionPipeline() = runBlocking {
        println("==================================================")
        println("INTEGRATION TEST: 6 MAINSTREAM TARGET TRACKS")
        println("==================================================")

        for (track in testTracks) {
            println("\nTesting Track: '${track.artist} — ${track.title}' (videoId=${track.videoId})")

            // 1. YouTube Audio Resolution
            val ytRes = compositeProvider.youtubeAudioProvider.resolveStreamUrl(track)
            assertTrue("YouTube provider must resolve '${track.title}'", ytRes is AudioStreamResult.Success)
            val streamUrl = (ytRes as AudioStreamResult.Success).streamUrl
            println("  YouTube Stream: $streamUrl")

            // 2. Composite Engine Resolution
            val compositeRes = compositeProvider.resolveStreamUrl(track)
            assertTrue("Composite provider must resolve '${track.title}'", compositeRes is AudioStreamResult.Success)
            assertEquals(streamUrl, (compositeRes as AudioStreamResult.Success).streamUrl)
            println("  Composite Resolution: SUCCESS")
        }
        println("==================================================")
    }

    @Test
    fun testWhenYtDlpSucceedsInvidiousIsNotCalled() = runBlocking {
        var invidiousCalled = false

        val mockYtExtractor = object : YouTubeStreamExtractor {
            override suspend fun extractStream(videoId: String): YouTubeExtractedInfo {
                return YouTubeExtractedInfo(
                    streamUrl = "https://rr1---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
                    title = "Alan Walker - Faded",
                    artist = "Alan Walker",
                    durationSec = 213L
                )
            }
        }
        val mockInvidiousFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String? {
                invidiousCalled = true
                return null
            }
        }

        val ytProvider = YouTubeAudioProvider(extractor = mockYtExtractor)
        val invidiousProvider = InvidiousAudioProvider(fetcher = mockInvidiousFetcher)
        val provider = CompositeAudioProvider(
            youtubeAudioProvider = ytProvider,
            invidiousAudioProvider = invidiousProvider
        )

        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue(result is AudioStreamResult.Success)
        assertFalse("Invidious MUST NOT be called when yt-dlp succeeds", invidiousCalled)
    }

    @Test
    fun testWhenYtDlpFailsInvidiousFallbackIsCalled() = runBlocking {
        var invidiousCalled = false

        val failingYtExtractor = object : YouTubeStreamExtractor {
            override suspend fun extractStream(videoId: String): YouTubeExtractedInfo? {
                return null // yt-dlp failure simulation
            }
        }
        val mockInvidiousFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String {
                invidiousCalled = true
                return """
                    {
                      "title": "Alan Walker - Faded",
                      "author": "Alan Walker",
                      "lengthSeconds": 213,
                      "adaptiveFormats": [
                        {
                          "type": "audio/webm; codecs=\"opus\"",
                          "url": "https://invidious.fallback.stream/audio.webm",
                          "itag": "251"
                        }
                      ]
                    }
                """.trimIndent()
            }
        }

        val ytProvider = YouTubeAudioProvider(extractor = failingYtExtractor)
        val invidiousProvider = InvidiousAudioProvider(fetcher = mockInvidiousFetcher)
        val provider = CompositeAudioProvider(
            youtubeAudioProvider = ytProvider,
            invidiousAudioProvider = invidiousProvider
        )

        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue("Invidious fallback MUST be attempted when yt-dlp fails", invidiousCalled)
        assertTrue("Composite provider should succeed via Invidious fallback", result is AudioStreamResult.Success)
        assertEquals("https://invidious.fallback.stream/audio.webm", (result as AudioStreamResult.Success).streamUrl)
    }

    @Test
    fun testWhenBothYtDlpAndInvidiousFailReturnUnavailable() = runBlocking {
        val failingYtExtractor = object : YouTubeStreamExtractor {
            override suspend fun extractStream(videoId: String): YouTubeExtractedInfo? = null
        }
        val failingInvidiousFetcher = object : InvidiousFetcher {
            override suspend fun fetchVideoDetails(baseUrl: String, videoId: String, timeoutMs: Int): String? = null
        }

        val provider = CompositeAudioProvider(
            youtubeAudioProvider = YouTubeAudioProvider(extractor = failingYtExtractor),
            invidiousAudioProvider = InvidiousAudioProvider(fetcher = failingInvidiousFetcher)
        )

        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = provider.resolveStreamUrl(track)

        assertTrue(result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testStrictRejectionInCompositeProvider() = runBlocking {
        val mockRejectionExtractor = object : YouTubeStreamExtractor {
            override suspend fun extractStream(videoId: String): YouTubeExtractedInfo {
                return YouTubeExtractedInfo(
                    streamUrl = "https://mock.stream/remix.webm",
                    title = "Alan Walker - Faded (OneUpped Remix)",
                    artist = "OneUp",
                    durationSec = 200L
                )
            }
        }
        val rejectingProvider = CompositeAudioProvider(
            youtubeAudioProvider = YouTubeAudioProvider(extractor = mockRejectionExtractor)
        )

        val track = Track(videoId = "60ItHLz5WEA", title = "Faded", artist = "Alan Walker")
        val result = rejectingProvider.resolveStreamUrl(track)
        assertTrue("CompositeProvider must decline remix/cover candidates", result is AudioStreamResult.Unavailable)
    }

    @Test
    fun testQueuePlaybackSimulation() {
        queueManager.setQueue(testTracks, 0)
        assertEquals(6, queueManager.queue.value?.size)
        assertEquals("Faded", queueManager.currentTrack()?.title)

        val track2 = queueManager.nextTrack()
        assertEquals("Blinding Lights", track2?.title)

        val track3 = queueManager.nextTrack()
        assertEquals("Starboy", track3?.title)

        val track4 = queueManager.nextTrack()
        assertEquals("Perfect", track4?.title)

        val prevTrack = queueManager.previousTrack()
        assertEquals("Starboy", prevTrack?.title)
    }
}
