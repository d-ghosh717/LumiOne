package com.lumione.player

import com.lumione.player.player.*
import com.lumione.player.queue.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubeAudioProviderTest {

    private val sampleDatabase = mapOf(
        "60ItHLz5WEA" to YouTubeExtractedInfo(
            streamUrl = "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "Alan Walker - Faded (Official Music Video)",
            artist = "Alan Walker",
            durationSec = 212L
        ),
        "4NRXx6U8ABQ" to YouTubeExtractedInfo(
            streamUrl = "https://rr3---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "The Weeknd - Blinding Lights (Official Video)",
            artist = "The Weeknd",
            durationSec = 260L
        ),
        "dqt8Z1k0oWQ" to YouTubeExtractedInfo(
            streamUrl = "https://rr1---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "The Weeknd - Starboy (Official Music Video) ft. Daft Punk",
            artist = "The Weeknd",
            durationSec = 230L
        ),
        "2Vv-BfVoq4g" to YouTubeExtractedInfo(
            streamUrl = "https://rr4---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "Ed Sheeran - Perfect (Official Music Video)",
            artist = "Ed Sheeran",
            durationSec = 263L
        ),
        "7wtfhZwyrcc" to YouTubeExtractedInfo(
            streamUrl = "https://rr5---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "Imagine Dragons - Believer (Official Music Video)",
            artist = "Imagine Dragons",
            durationSec = 217L
        ),
        "IcrbM1l_BoI" to YouTubeExtractedInfo(
            streamUrl = "https://rr6---sn-4g5edn6e.googlevideo.com/videoplayback?expire=1710000&itag=251&audio_only=1",
            title = "Avicii - Wake Me Up (Official Video)",
            artist = "Avicii",
            durationSec = 273L
        ),
        // Modifier / non-matching candidates
        "remix_faded" to YouTubeExtractedInfo(
            streamUrl = "https://rr2---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
            title = "Alan Walker - Faded (OneUpped Remix)",
            artist = "OneUp Music",
            durationSec = 190L
        ),
        "cover_perfect" to YouTubeExtractedInfo(
            streamUrl = "https://rr4---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
            title = "Ed Sheeran - Perfect - Violin Cover",
            artist = "ViolinCovers",
            durationSec = 240L
        ),
        "cover_believer" to YouTubeExtractedInfo(
            streamUrl = "https://rr5---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
            title = "Imagine Dragons - Believer Guitar Cover",
            artist = "GuitarGuy",
            durationSec = 200L
        ),
        "remix_wakemeup" to YouTubeExtractedInfo(
            streamUrl = "https://rr6---sn-4g5edn6e.googlevideo.com/videoplayback?itag=251",
            title = "Avicii - Wake Me Up (Club Remix)",
            artist = "DJ Remixes",
            durationSec = 310L
        )
    )

    private val mockExtractor = object : YouTubeStreamExtractor {
        override suspend fun extractStream(videoId: String): YouTubeExtractedInfo? {
            return sampleDatabase[videoId]
        }
    }

    @Test
    fun testSixTargetTracksResolveSuccessfully() = runBlocking {
        val provider = YouTubeAudioProvider(extractor = mockExtractor)

        val targetTracks = listOf(
            Track("60ItHLz5WEA", "Faded", "Alan Walker"),
            Track("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            Track("dqt8Z1k0oWQ", "Starboy", "The Weeknd"),
            Track("2Vv-BfVoq4g", "Perfect", "Ed Sheeran"),
            Track("7wtfhZwyrcc", "Believer", "Imagine Dragons"),
            Track("IcrbM1l_BoI", "Wake Me Up", "Avicii")
        )

        for (track in targetTracks) {
            val result = provider.resolveStreamUrl(track)
            assertTrue("Track '${track.title}' must resolve successfully", result is AudioStreamResult.Success)
            val success = result as AudioStreamResult.Success
            assertTrue(success.streamUrl.isNotBlank())
            assertTrue(success.streamUrl.startsWith("https://"))
        }
    }

    @Test
    fun testStrictTrackMatcherRejectsModifiersAndCovers() = runBlocking {
        val provider = YouTubeAudioProvider(extractor = mockExtractor)

        // 1. Faded (OneUpped Remix)
        val fadedOneUpped = Track(videoId = "remix_faded", title = "Faded", artist = "Alan Walker")
        val fadedResult = provider.resolveStreamUrl(fadedOneUpped)
        assertTrue("Must reject OneUpped Remix", fadedResult is AudioStreamResult.Unavailable)

        // 2. Perfect Violin Cover
        val perfectCover = Track(videoId = "cover_perfect", title = "Perfect", artist = "Ed Sheeran")
        val perfectResult = provider.resolveStreamUrl(perfectCover)
        assertTrue("Must reject Violin Cover", perfectResult is AudioStreamResult.Unavailable)

        // 3. Believer Guitar Cover
        val believerCover = Track(videoId = "cover_believer", title = "Believer", artist = "Imagine Dragons")
        val believerResult = provider.resolveStreamUrl(believerCover)
        assertTrue("Must reject Guitar Cover", believerResult is AudioStreamResult.Unavailable)

        // 4. Wake Me Up Club Remix
        val wakeMeUpRemix = Track(videoId = "remix_wakemeup", title = "Wake Me Up", artist = "Avicii")
        val wakeMeUpResult = provider.resolveStreamUrl(wakeMeUpRemix)
        assertTrue("Must reject Club Remix", wakeMeUpResult is AudioStreamResult.Unavailable)
    }

    @Test
    fun testEmptyVideoIdReturnsUnavailable() = runBlocking {
        val provider = YouTubeAudioProvider(extractor = mockExtractor)
        val emptyTrack = Track(videoId = "", title = "Empty", artist = "Empty", youtubeVideoId = "")
        val result = provider.resolveStreamUrl(emptyTrack)
        assertTrue(result is AudioStreamResult.Unavailable)
    }
}
