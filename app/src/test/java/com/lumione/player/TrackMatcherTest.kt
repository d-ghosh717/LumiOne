package com.lumione.player

import com.lumione.player.player.TrackMatcher
import org.junit.Assert.*
import org.junit.Test

class TrackMatcherTest {

    @Test
    fun testTitlePublishingCleaning() {
        assertEquals("faded", TrackMatcher.cleanTitle("Faded (Official Music Video)"))
        assertEquals("blinding lights", TrackMatcher.cleanTitle("Blinding Lights [Official Audio]"))
        assertEquals("perfect", TrackMatcher.cleanTitle("Perfect (Official Lyric Video)"))
        assertEquals("believer", TrackMatcher.cleanTitle("Believer (Visualizer)"))
        assertEquals("wake me up", TrackMatcher.cleanTitle("Wake Me Up [HD]"))
    }

    @Test
    fun testArtistCleaning() {
        assertEquals("alan walker", TrackMatcher.cleanArtist("Alan Walker VEVO"))
        assertEquals("the weeknd", TrackMatcher.cleanArtist("The Weeknd - Topic"))
        assertEquals("ed sheeran", TrackMatcher.cleanArtist("Ed Sheeran feat. Beyonce"))
    }

    @Test
    fun testExactLegitimateMatchAccepted() {
        assertTrue(
            "Exact original track must match",
            TrackMatcher.isReliableMatch(
                queryTitle = "Faded",
                queryArtist = "Alan Walker",
                candidateTitle = "Faded",
                candidateArtist = "Alan Walker"
            )
        )

        assertTrue(
            "Query with artist prefix must match candidate",
            TrackMatcher.isReliableMatch(
                queryTitle = "Alan Walker - Faded",
                queryArtist = "Alan Walker",
                candidateTitle = "Alan Walker - Faded (Official Music Video)",
                candidateArtist = "Alan Walker"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsRemix() {
        assertFalse(
            "Original track must NOT match a Remix",
            TrackMatcher.isReliableMatch(
                queryTitle = "Wake Me Up",
                queryArtist = "Avicii",
                candidateTitle = "Avicii - Wake Me Up (Aurelios Remix)",
                candidateArtist = "Aurelios"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsCover() {
        assertFalse(
            "Original track must NOT match a Cover",
            TrackMatcher.isReliableMatch(
                queryTitle = "Perfect",
                queryArtist = "Ed Sheeran",
                candidateTitle = "Ed Sheeran - Perfect - Cover (Violin)",
                candidateArtist = "Claudia The Violinist"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsLive() {
        assertFalse(
            "Original track must NOT match a Live version",
            TrackMatcher.isReliableMatch(
                queryTitle = "Believer",
                queryArtist = "Imagine Dragons",
                candidateTitle = "Believer (Live in Vegas)",
                candidateArtist = "Imagine Dragons"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsAcoustic() {
        assertFalse(
            "Original track must NOT match an Acoustic version",
            TrackMatcher.isReliableMatch(
                queryTitle = "Perfect",
                queryArtist = "Ed Sheeran",
                candidateTitle = "Perfect (Acoustic)",
                candidateArtist = "Ed Sheeran"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsInstrumental() {
        assertFalse(
            "Original track must NOT match an Instrumental",
            TrackMatcher.isReliableMatch(
                queryTitle = "Faded",
                queryArtist = "Alan Walker",
                candidateTitle = "Faded (Instrumental Version)",
                candidateArtist = "Alan Walker"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsSpedUp() {
        assertFalse(
            "Original track must NOT match a Sped-Up version",
            TrackMatcher.isReliableMatch(
                queryTitle = "Blinding Lights",
                queryArtist = "The Weeknd",
                candidateTitle = "Blinding Lights (Sped Up)",
                candidateArtist = "The Weeknd"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsSlowed() {
        assertFalse(
            "Original track must NOT match a Slowed + Reverb version",
            TrackMatcher.isReliableMatch(
                queryTitle = "Believer",
                queryArtist = "Imagine Dragons",
                candidateTitle = "Believer (Slowed + Reverb)",
                candidateArtist = "Imagine Dragons"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsMashup() {
        assertFalse(
            "Original track must NOT match a Mashup",
            TrackMatcher.isReliableMatch(
                queryTitle = "Wake Me Up",
                queryArtist = "Avicii",
                candidateTitle = "Wake Me Up vs Levels (Mashup)",
                candidateArtist = "DJ Mash"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsCustomParentheticalModifiers() {
        assertFalse(
            "Original 'Faded' must NOT match 'Faded (OneUpped)'",
            TrackMatcher.isReliableMatch(
                queryTitle = "Faded",
                queryArtist = "Alan Walker",
                candidateTitle = "Alan Walker - Faded (OneUpped)",
                candidateArtist = "OneUp"
            )
        )
    }

    @Test
    fun testOriginalNotEqualsInstrumentCovers() {
        assertFalse(
            "Original 'Believer' must NOT match 'Guitar Cover'",
            TrackMatcher.isReliableMatch(
                queryTitle = "Believer",
                queryArtist = "Imagine Dragons",
                candidateTitle = "Believer - Imagine Dragons - Guitar Cover",
                candidateArtist = "GuitarGuy"
            )
        )
    }
}
