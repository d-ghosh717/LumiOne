package com.lumione.player

import com.lumione.player.data.FavoritesManager
import com.lumione.player.data.HistoryManager
import com.lumione.player.queue.Track
import com.lumione.player.search.SearchEngine
import com.lumione.player.search.SearchResult
import com.lumione.player.search.TrackNormalizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecommendationEngineTest {

    private lateinit var searchEngine: SearchEngine
    private lateinit var fakePrefsHistory: FakeSharedPreferences
    private lateinit var fakePrefsFavorites: FakeSharedPreferences
    private lateinit var historyManager: HistoryManager
    private lateinit var favoritesManager: FavoritesManager

    @Before
    fun setup() {
        searchEngine = SearchEngine()
        fakePrefsHistory = FakeSharedPreferences()
        fakePrefsFavorites = FakeSharedPreferences()
        historyManager = HistoryManager(fakePrefsHistory)
        favoritesManager = FavoritesManager(fakePrefsFavorites)
    }

    @Test
    fun testSeedSongDoesNotResultInRowFilledWithAlternateVersions() {
        // Candidate pool containing original song + slowed, sped up, lo-fi, remix variants
        val candidates = listOf(
            SearchResult(videoId = "1", title = "Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "2", title = "Faded (Slowed + Reverb)", artist = "Alan Walker", durationMs = 260000L, thumbnailUrl = ""),
            SearchResult(videoId = "3", title = "Alan Walker - Faded (Sped Up)", artist = "RemixChannel", durationMs = 170000L, thumbnailUrl = ""),
            SearchResult(videoId = "4", title = "Faded (Lo-Fi Chill Beats)", artist = "LofiGuy", durationMs = 190000L, thumbnailUrl = ""),
            SearchResult(videoId = "5", title = "Faded (Acoustic Remix)", artist = "Alan Walker", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "6", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "7", title = "Starboy", artist = "The Weeknd", durationMs = 230000L, thumbnailUrl = ""),
            SearchResult(videoId = "8", title = "Alone", artist = "Marshmello", durationMs = 210000L, thumbnailUrl = ""),
            SearchResult(videoId = "9", title = "Something Just Like This", artist = "The Chainsmokers", durationMs = 247000L, thumbnailUrl = "")
        )

        // Seeded with Faded
        val seedKey = TrackNormalizer.extractCanonicalKey("Faded", "Alan Walker")
        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Alan Walker"),
            recentTrackKeys = listOf(seedKey),
            limit = 10
        )

        // Count how many versions of Faded are in the result
        val fadedVersions = recommendations.filter {
            TrackNormalizer.extractCoreTitle(it.title) == "faded"
        }

        // Must NOT be filled with slowed/sped-up/lo-fi versions of Faded!
        // Alternate versions must be eliminated; at most 1 representative canonical version could appear if not excluded
        assertTrue(
            "Expected alternate derivative versions of Faded to be suppressed, but found ${fadedVersions.size}",
            fadedVersions.isEmpty() || fadedVersions.none { TrackNormalizer.detectModifier(it.title) != null }
        )

        // Diverse songs from other artists must be recommended
        val artists = recommendations.map { TrackNormalizer.cleanArtist(it.artist) }.toSet()
        assertTrue("Expected multiple distinct artists", artists.size >= 2)
    }

    @Test
    fun testArtistAffinityAndArtistDiversityConstraint() {
        // User loves The Weeknd, but recommendation row must NOT be saturated by 1 artist alone
        val candidates = listOf(
            SearchResult(videoId = "w1", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "w2", title = "Save Your Tears", artist = "The Weeknd", durationMs = 215000L, thumbnailUrl = ""),
            SearchResult(videoId = "w3", title = "Starboy", artist = "The Weeknd", durationMs = 230000L, thumbnailUrl = ""),
            SearchResult(videoId = "w4", title = "Can't Feel My Face", artist = "The Weeknd", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "w5", title = "In The Night", artist = "The Weeknd", durationMs = 235000L, thumbnailUrl = ""),
            SearchResult(videoId = "d1", title = "Levitating", artist = "Dua Lipa", durationMs = 203000L, thumbnailUrl = ""),
            SearchResult(videoId = "d2", title = "Don't Start Now", artist = "Dua Lipa", durationMs = 183000L, thumbnailUrl = ""),
            SearchResult(videoId = "h1", title = "As It Was", artist = "Harry Styles", durationMs = 167000L, thumbnailUrl = ""),
            SearchResult(videoId = "p1", title = "Circles", artist = "Post Malone", durationMs = 215000L, thumbnailUrl = "")
        )

        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            likedArtists = listOf("The Weeknd"),
            limit = 10
        )

        // Weeknd tracks must not exceed diversity limit (max 2 per artist in a row)
        val weekndCount = recommendations.count { TrackNormalizer.cleanArtist(it.artist) == "the weeknd" }
        assertTrue("Max 2 tracks per artist allowed, found $weekndCount", weekndCount <= 2)

        // Must include diverse artists matching listener taste
        val recommendedArtists = recommendations.map { TrackNormalizer.cleanArtist(it.artist) }
        assertTrue("Dua Lipa should be included", recommendedArtists.contains("dua lipa"))
        assertTrue("Harry Styles or Post Malone should be included", recommendedArtists.contains("harry styles") || recommendedArtists.contains("post malone"))
    }

    @Test
    fun testNonMusicResultsFilteredStrictly() {
        assertFalse(SearchEngine.isMusicTrack("GTA 5 Heist Gameplay Live Walkthrough", "Gamer", 2400000L))
        assertFalse(SearchEngine.isMusicTrack("Minecraft 100 Days Hardcore Survival", "Crafter", 1800000L))
        assertFalse(SearchEngine.isMusicTrack("Fortnite Season 5 Battle Pass Gameplay", "ProGamer", 600000L))
        assertFalse(SearchEngine.isMusicTrack("Slime ASMR Most Satisfying Video", "Relax Slime", 600000L))
        assertFalse(SearchEngine.isMusicTrack("Mukbang Eating 10000 Calories", "Foodie", 1200000L))
        assertFalse(SearchEngine.isMusicTrack("Reacting to Crazy TikToks", "Reactor", 450000L))
        assertFalse(SearchEngine.isMusicTrack("Daily Vlog Day in NYC", "Vlogger", 700000L))
        assertFalse(SearchEngine.isMusicTrack("10 Hour Study Ambient Stream", "RelaxZone", 36000000L))
        assertFalse(SearchEngine.isMusicTrack("Short meme clip", "MemeGuy", 15000L)) // < 35s

        // Legitimate music must pass
        assertTrue(SearchEngine.isMusicTrack("Blinding Lights", "The Weeknd", 200000L))
        assertTrue(SearchEngine.isMusicTrack("Faded", "Alan Walker", 213000L))
        assertTrue(SearchEngine.isMusicTrack("Kesariya (From Brahmastra)", "Arijit Singh", 268000L))
    }

    @Test
    fun testDuplicateTracksNotRepeatedInSameRow() {
        val candidates = listOf(
            SearchResult(videoId = "1", title = "Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "2", title = "Faded [Official Audio]", artist = "Alan Walker VEVO", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "3", title = "Alan Walker - Faded (Official Music Video)", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "4", title = "Believer", artist = "Imagine Dragons", durationMs = 204000L, thumbnailUrl = ""),
            SearchResult(videoId = "5", title = "Believer [Official Audio]", artist = "Imagine Dragons", durationMs = 204000L, thumbnailUrl = "")
        )

        val deduplicated = TrackNormalizer.deduplicateAndFilter(candidates)

        // Exactly 2 tracks should remain (1 for Faded, 1 for Believer)
        assertEquals(2, deduplicated.size)
        val titles = deduplicated.map { TrackNormalizer.extractCoreTitle(it.title) }
        assertTrue(titles.contains("faded"))
        assertTrue(titles.contains("believer"))
    }

    @Test
    fun testMissingMetadataDoesNotCrashPipeline() {
        val candidatesWithGaps = listOf(
            SearchResult(videoId = "bad1", title = "", artist = "", durationMs = 0L, thumbnailUrl = ""),
            SearchResult(videoId = "bad2", title = "   ", artist = "   ", durationMs = -100L, thumbnailUrl = ""),
            SearchResult(videoId = "good1", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200000L, thumbnailUrl = "")
        )

        // Should not throw any exception
        val results = searchEngine.rankRecommendationsLocally(
            candidates = candidatesWithGaps,
            limit = 5
        )

        assertEquals(1, results.size)
        assertEquals("good1", results[0].videoId)
    }

    @Test
    fun testColdStartRecommendationsForNewUser() {
        val chartCandidates = listOf(
            SearchResult(videoId = "c1", title = "Song A", artist = "Artist 1", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "c2", title = "Song B", artist = "Artist 2", durationMs = 210000L, thumbnailUrl = ""),
            SearchResult(videoId = "c3", title = "Song C", artist = "Artist 3", durationMs = 220000L, thumbnailUrl = "")
        )

        // Cold start with empty signals
        val results = searchEngine.rankRecommendationsLocally(
            candidates = chartCandidates,
            limit = 5
        )

        assertEquals(3, results.size)
        assertEquals("Song A", results[0].title)
        assertEquals("Song B", results[1].title)
        assertEquals("Song C", results[2].title)
    }

    @Test
    fun testListeningSignalTrackingAndTopArtistCalculation() {
        val track1 = Track(videoId = "t1", title = "Midnight City", artist = "M83", durationMs = 240000L)
        val track2 = Track(videoId = "t2", title = "Starboy", artist = "The Weeknd", durationMs = 230000L)
        val track3 = Track(videoId = "t3", title = "Bad Song", artist = "SpamArtist", durationMs = 180000L)

        // Play track1 twice and complete once
        historyManager.recordTrack(track1)
        historyManager.recordTrack(track1)
        historyManager.recordCompletion("t1")

        // Play track2 once and complete once
        historyManager.recordTrack(track2)
        historyManager.recordCompletion("t2")

        // Play track3 once and skip twice
        historyManager.recordTrack(track3)
        historyManager.recordSkip("t3")
        historyManager.recordSkip("t3")

        val topArtists = historyManager.getTopArtists(5)
        assertEquals("M83", topArtists[0])
        assertEquals("The Weeknd", topArtists[1])

        val skippedArtists = historyManager.getSkippedArtists()
        assertTrue("SpamArtist should be in skipped list", skippedArtists.contains("SpamArtist"))
    }
}
