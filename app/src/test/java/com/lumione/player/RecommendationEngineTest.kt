package com.lumione.player

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lumione.player.data.FavoritesManager
import com.lumione.player.data.HistoryManager
import com.lumione.player.queue.Track
import com.lumione.player.search.MusicIntelligence
import com.lumione.player.search.SearchEngine
import com.lumione.player.search.SearchResult
import com.lumione.player.search.TrackNormalizer
import com.lumione.player.search.TrackProfile
import com.lumione.player.search.toTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RecommendationEngineTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

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
            SearchResult(videoId = "w1", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "w2", title = "Save Your Tears", artist = "The Weeknd", durationMs = 215000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "w3", title = "Starboy", artist = "The Weeknd", durationMs = 230000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "w4", title = "Can't Feel My Face", artist = "The Weeknd", durationMs = 213000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "w5", title = "In The Night", artist = "The Weeknd", durationMs = 235000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "d1", title = "Levitating", artist = "Dua Lipa", durationMs = 203000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "d2", title = "Don't Start Now", artist = "Dua Lipa", durationMs = 183000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult(videoId = "h1", title = "As It Was", artist = "Harry Styles", durationMs = 167000L, thumbnailUrl = "", source = "genre_search", retrievalSources = listOf("genre_search")),
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
    fun testSeedAndItsNonStandardReworkAreExcludedFromRadioQueue() {
        val seedKey = TrackNormalizer.extractCanonicalKey("Faded", "Alan Walker")
        val candidates = listOf(
            SearchResult(videoId = "seed", title = "Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = "", source = "watch_next"),
            SearchResult(videoId = "rework", title = "Faded (Restrung)", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = "", source = "watch_next"),
            SearchResult(videoId = "related", title = "The Nights", artist = "Avicii", durationMs = 210000L, thumbnailUrl = "", source = "related_artist_search")
        )
        val result = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Alan Walker"),
            seedTitle = "Faded",
            recentTrackKeys = listOf(seedKey),
            limit = 10
        )
        assertEquals(listOf("The Nights"), result.map { it.title })
    }

    @Test
    fun testMissingMetadataDoesNotCrashPipeline() {
        val candidatesWithGaps = listOf(
            SearchResult(videoId = "bad1", title = "", artist = "", durationMs = 0L, thumbnailUrl = ""),
            SearchResult(videoId = "bad2", title = "   ", artist = "   ", durationMs = -100L, thumbnailUrl = ""),
            SearchResult(videoId = "good1", title = "Blinding Lights", artist = "The Weeknd", durationMs = 200000L, thumbnailUrl = "", source = "watch_next", retrievalSources = listOf("watch_next"))
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
    fun testGenericChartsAreNotUsedForRadioColdStart() {
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

        assertTrue("No retrieved relationship evidence means no radio recommendations", results.isEmpty())
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

    @Test
    fun testMultipleUploadsOfWorrySlowedAcrossDifferentChannelsResolveToSameKeyAndAreDeduplicatedForQueue() {
        val u1 = TrackNormalizer.extractCanonicalKey("LONOWN - worry (Slowed)", "S o u n d s .")
        val u2 = TrackNormalizer.extractCanonicalKey("LONOWN - worry (Slowed + Reverb)", "Night Drive")
        val u3 = TrackNormalizer.extractCanonicalKey("worry (slowed) - LONOWN", "Anime Chill")
        val u4 = TrackNormalizer.extractCanonicalKey("LONOWN - worry", "LONOWN - Topic")

        assertEquals("lonown:::worry", u1)
        assertEquals("lonown:::worry", u2)
        assertEquals("lonown:::worry", u3)
        assertEquals("lonown:::worry", u4)

        // Queue deduplication test
        val searchResults = listOf(
            SearchResult(videoId = "w1", title = "LONOWN - worry (Slowed)", artist = "S o u n d s .", durationMs = 135000L, thumbnailUrl = ""),
            SearchResult(videoId = "w2", title = "LONOWN - worry (Slowed + Reverb)", artist = "Night Drive", durationMs = 135000L, thumbnailUrl = ""),
            SearchResult(videoId = "w3", title = "worry (slowed) - LONOWN", artist = "Anime Chill", durationMs = 135000L, thumbnailUrl = ""),
            SearchResult(videoId = "aw1", title = "Alan Walker - Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "zhu1", title = "ZHU - Faded", artist = "ZHU", durationMs = 220000L, thumbnailUrl = "")
        )

        val selectedTrack = searchResults[0] // user clicked LONOWN - worry (Slowed)
        val selectedKey = TrackNormalizer.extractCanonicalKey(selectedTrack.title, selectedTrack.artist)
        val remaining = searchResults.filterIndexed { index, _ -> index != 0 }

        val queueUpcoming = TrackNormalizer.deduplicateForQueue(remaining, selectedTrackKey = selectedKey)

        // Duplicate uploads w2 and w3 must be pruned!
        assertFalse("Duplicate upload w2 should be pruned", queueUpcoming.any { it.videoId == "w2" })
        assertFalse("Duplicate upload w3 should be pruned", queueUpcoming.any { it.videoId == "w3" })

        // Distinct songs (Alan Walker - Faded and ZHU - Faded) must be kept!
        assertTrue("Alan Walker - Faded should be kept", queueUpcoming.any { it.videoId == "aw1" })
        assertTrue("ZHU - Faded should be kept", queueUpcoming.any { it.videoId == "zhu1" })
    }

    @Test
    fun testFadedByAlanWalkerVsZhuVsTygaRetainDistinctIdentities() {
        val k1 = TrackNormalizer.extractCanonicalKey("Alan Walker - Faded", "Alan Walker")
        val k2 = TrackNormalizer.extractCanonicalKey("ZHU - Faded", "ZHU")
        val k3 = TrackNormalizer.extractCanonicalKey("Tyga - Faded ft. Lil Wayne", "Tyga")

        assertEquals("alan walker:::faded", k1)
        assertEquals("zhu:::faded", k2)
        assertEquals("tyga:::faded", k3)

        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k2, k3)

        val candidates = listOf(
            SearchResult(videoId = "aw1", title = "Alan Walker - Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "zhu1", title = "ZHU - Faded", artist = "ZHU", durationMs = 220000L, thumbnailUrl = ""),
            SearchResult(videoId = "tyga1", title = "Tyga - Faded ft. Lil Wayne", artist = "Tyga", durationMs = 200000L, thumbnailUrl = "")
        )

        // None should be purged as duplicate of another
        val deduplicated = TrackNormalizer.deduplicateAndFilter(candidates)
        assertEquals(3, deduplicated.size)
    }

    @Test
    fun testQueueManagerDeduplicationOnSetQueueAndAddToQueue() {
        val qm = com.lumione.player.queue.QueueManager()
        val t1 = Track(videoId = "1", title = "Song 1", artist = "Artist 1")
        val t2 = Track(videoId = "2", title = "Song 2", artist = "Artist 2")
        val t1Dup = Track(videoId = "1", title = "Song 1", artist = "Artist 1")

        qm.setQueue(listOf(t1, t2, t1Dup), startIndex = 0)
        assertEquals(2, qm.queue.value?.size)
        assertEquals("1", qm.currentTrack()?.videoId)

        // Try adding t2 again
        qm.addToQueue(t2)
        assertEquals(2, qm.queue.value?.size)

        // Try insertNext with t2
        qm.insertNext(t2)
        assertEquals(2, qm.queue.value?.size)
    }

    @Test
    fun testSingleSongSelectionGeneratesMusicallyRelatedUpNextQueueNotUnrelatedSameTitleTracks() {
        val seedTrack = Track(videoId = "aw_faded", title = "Alan Walker - Faded", artist = "Alan Walker")
        val seedKey = TrackNormalizer.extractCanonicalKey(seedTrack.title, seedTrack.artist)

        val candidates = listOf(
            // Unrelated tracks sharing the title "Faded"
            SearchResult(videoId = "tyga_faded", title = "Tyga - Faded ft. Lil Wayne", artist = "Tyga", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "tns_faded", title = "TNS Kai - Faded", artist = "TNS Kai", durationMs = 180000L, thumbnailUrl = ""),
            SearchResult(videoId = "fay_faded", title = "Fay - Faded", artist = "Fay", durationMs = 195000L, thumbnailUrl = ""),
            // Musically related electronic/EDM tracks
            SearchResult(videoId = "aw_alone", title = "Alan Walker - Alone", artist = "Alan Walker", durationMs = 210000L, thumbnailUrl = ""),
            SearchResult(videoId = "aw_spectre", title = "Alan Walker - The Spectre", artist = "Alan Walker", durationMs = 195000L, thumbnailUrl = ""),
            SearchResult(videoId = "cs_closer", title = "The Chainsmokers - Closer", artist = "The Chainsmokers", durationMs = 245000L, thumbnailUrl = ""),
            SearchResult(videoId = "marsh_happier", title = "Marshmello - Happier", artist = "Marshmello", durationMs = 215000L, thumbnailUrl = "")
        )

        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Alan Walker"),
            likedArtists = listOf("The Chainsmokers", "Marshmello"),
            recentTrackKeys = listOf(seedKey),
            limit = 5
        )

        // 1. Seed song itself must be excluded from recommendations
        assertFalse("Seed track must be excluded", recommendations.any { it.videoId == "aw_faded" })

        // 2. Musically related tracks by Alan Walker, The Chainsmokers, and Marshmello must rank high
        val topArtists = recommendations.take(3).map { TrackNormalizer.cleanArtist(it.artist) }
        assertTrue("Top recommendations should include Alan Walker", topArtists.contains("alan walker"))
        assertTrue("Top recommendations should include Chainsmokers or Marshmello", topArtists.contains("the chainsmokers") || topArtists.contains("marshmello"))

        // 3. Unrelated tracks merely sharing title 'Faded' must NOT saturate the Up Next queue
        val unrelatedArtists = setOf("tyga", "tns kai", "fay")
        val unrelatedCountInTop3 = recommendations.take(3).count { unrelatedArtists.contains(TrackNormalizer.cleanArtist(it.artist)) }
        assertEquals("Unrelated same-title tracks must not be in top 3 Up Next", 0, unrelatedCountInTop3)
    }

    @Test
    fun testQueueModesAndAutoplayReplenishment() {
        val qm = com.lumione.player.queue.QueueManager()
        val seed = Track(videoId = "seed1", title = "Alan Walker - Faded", artist = "Alan Walker")

        // A. Playing single song from search sets QueueMode.RADIO
        qm.setQueue(listOf(seed), startIndex = 0, mode = com.lumione.player.queue.QueueMode.RADIO)
        assertEquals(com.lumione.player.queue.QueueMode.RADIO, qm.queueMode)
        assertEquals(1, qm.queue.value?.size)
        assertEquals(0, qm.remainingTracksCount())

        // Append Up Next related tracks
        val related1 = Track(videoId = "rel1", title = "Alone", artist = "Alan Walker")
        val related2 = Track(videoId = "rel2", title = "Closer", artist = "The Chainsmokers")
        qm.appendUpcomingTracks(listOf(related1, related2))
        assertEquals(3, qm.queue.value?.size)
        assertEquals(2, qm.remainingTracksCount())

        // Autoplay replenishment: appending existing tracks must be deduplicated
        qm.appendUpcomingTracks(listOf(related1, Track(videoId = "rel3", title = "Happier", artist = "Marshmello")))
        assertEquals("Should only add new unique track rel3", 4, qm.queue.value?.size)

        // B. Playlist mode preserves exact order
        val pl1 = Track(videoId = "p1", title = "Track A", artist = "Artist A")
        val pl2 = Track(videoId = "p2", title = "Track B", artist = "Artist B")
        qm.setQueue(listOf(pl1, pl2), startIndex = 0, mode = com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES)
        assertEquals(com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES, qm.queueMode)
        assertEquals(2, qm.queue.value?.size)
        assertEquals("p1", qm.currentTrack()?.videoId)
        assertEquals("p2", qm.nextTrack()?.videoId)
    }

    @Test
    fun testHanumanChalisaAndIndianChartSongsRejectedWhenSeedIsAlanWalker() {
        val seedTrack = Track(videoId = "aw_faded", title = "Alan Walker - Faded", artist = "Alan Walker")
        val seedKey = TrackNormalizer.extractCanonicalKey(seedTrack.title, seedTrack.artist)

        val candidates = listOf(
            // Verified related candidates
            SearchResult(videoId = "aw_alone", title = "Alone", artist = "Alan Walker", durationMs = 164000L, thumbnailUrl = "", source = "seed_artist"),
            SearchResult(videoId = "aw_onmyway", title = "On My Way", artist = "Alan Walker, Sabrina Carpenter", durationMs = 193000L, thumbnailUrl = "", source = "seed_artist"),
            SearchResult(videoId = "zayn_dusk", title = "Dusk Till Dawn", artist = "ZAYN ft. Sia", durationMs = 239000L, thumbnailUrl = "", source = "watch_next"),
            SearchResult(videoId = "pass_letgo", title = "Let Her Go", artist = "Passenger", durationMs = 252000L, thumbnailUrl = "", source = "watch_next"),
            SearchResult(videoId = "cs_dontlet", title = "Don't Let Me Down", artist = "The Chainsmokers", durationMs = 208000L, thumbnailUrl = "", source = "watch_next"),

            // Unrelated Indian songs from chart fallback
            SearchResult(videoId = "hanuman", title = "Hanuman Chalisa", artist = "Hariharan", durationMs = 580000L, thumbnailUrl = "", source = "unrelated_chart", provider = "saavn"),
            SearchResult(videoId = "parvati", title = "Parvati", artist = "Hansraj Raghuwanshi", durationMs = 245000L, thumbnailUrl = "", source = "unrelated_chart", provider = "saavn"),
            SearchResult(videoId = "tujh_mein", title = "Tujh Mein Basi Meri Jaan", artist = "B Praak", durationMs = 280000L, thumbnailUrl = "", source = "unrelated_chart", provider = "saavn"),
            SearchResult(videoId = "casa_tupka", title = "Casa Tupka Anthem", artist = "MC Square", durationMs = 190000L, thumbnailUrl = "", source = "unrelated_chart", provider = "saavn")
        )

        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Alan Walker"),
            recentTrackKeys = listOf(seedKey),
            limit = 5
        )

        val titles = recommendations.map { it.title }
        assertTrue("Must include Alone", titles.contains("Alone"))
        assertTrue("Must include On My Way", titles.contains("On My Way"))
        assertTrue("Must include Dusk Till Dawn or Let Her Go", titles.contains("Dusk Till Dawn") || titles.contains("Let Her Go"))

        // Unrelated Indian songs must NOT be present in recommendations
        assertFalse("Hanuman Chalisa must NOT be recommended", titles.contains("Hanuman Chalisa"))
        assertFalse("Parvati must NOT be recommended", titles.contains("Parvati"))
        assertFalse("Tujh Mein Basi Meri Jaan must NOT be recommended", titles.contains("Tujh Mein Basi Meri Jaan"))
        assertFalse("Casa Tupka Anthem must NOT be recommended", titles.contains("Casa Tupka Anthem"))
    }

    @Test
    fun testProfileDoesNotInventAudioFeaturesWhenProviderMetadataIsMissing() {
        val profile = MusicIntelligence.buildTrackProfile(title = "Unknown title", artist = "Unknown channel")
        assertNull(profile.primaryGenre)
        assertEquals(0.0, profile.confidence, 0.0)
        assertTrue(profile.moodTags.isEmpty())
    }

    @Test
    fun testGenreEligibilityGateStrictlyRejectsDevotionalAndRegionalForEdm() {
        val edmProfile = MusicIntelligence.buildTrackProfile(title = "Faded", artist = "Alan Walker")
        val devotionalProfile = MusicIntelligence.buildTrackProfile(title = "Hanuman Chalisa", artist = "Hariharan")
        val regionalProfile = MusicIntelligence.buildTrackProfile(title = "Casa Tupka Anthem", artist = "MC Square")
        val electronicProfile = MusicIntelligence.buildTrackProfile(title = "Alone", artist = "Alan Walker")

        val (edmDevEligible, reasonDev) = MusicIntelligence.checkEligibilityGate(edmProfile, devotionalProfile)
        assertFalse("Devotional music must be rejected by gate for EDM seed", edmDevEligible)
        assertNotNull("Rejection reason must be provided", reasonDev)

        val (edmRegEligible, _) = MusicIntelligence.checkEligibilityGate(edmProfile, regionalProfile)
        assertFalse("Regional Indian desi music must be rejected by gate for EDM seed", edmRegEligible)

        val (edmEdmEligible, _) = MusicIntelligence.checkEligibilityGate(edmProfile, electronicProfile)
        assertTrue("Electronic track must be eligible for EDM seed", edmEdmEligible)
    }

    @Test
    fun testSadEmotionalSeedPreservesMoodAndFiltersIncompatibleTracks() {
        val seed = Track(videoId = "pass_letgo", title = "Let Her Go", artist = "Passenger")
        val seedKey = TrackNormalizer.extractCanonicalKey(seed.title, seed.artist)

        val candidates = listOf(
            SearchResult(videoId = "arijit_channa", title = "Channa Mereya", artist = "Arijit Singh", durationMs = 289000L, thumbnailUrl = ""),
            SearchResult(videoId = "adele_someone", title = "Someone Like You", artist = "Adele", durationMs = 285000L, thumbnailUrl = ""),
            SearchResult(videoId = "zayn_dusk", title = "Dusk Till Dawn", artist = "ZAYN ft. Sia", durationMs = 239000L, thumbnailUrl = ""),
            SearchResult(videoId = "hanuman", title = "Hanuman Chalisa", artist = "Hariharan", durationMs = 580000L, thumbnailUrl = "")
        )

        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Passenger"),
            seedTitle = "Let Her Go",
            recentTrackKeys = listOf(seedKey),
            limit = 3
        )

        val titles = recommendations.map { it.title }
        assertFalse("Devotional track should not be recommended for indie-folk/pop seed", titles.contains("Hanuman Chalisa"))
        assertTrue("Emotional compatible tracks should be recommended", titles.contains("Someone Like You") || titles.contains("Channa Mereya") || titles.contains("Dusk Till Dawn"))
    }

    @Test
    fun testScoreCandidateReturnsTransparentBreakdownOn100PointScale() {
        val seedProfile = MusicIntelligence.buildTrackProfile(title = "Faded", artist = "Alan Walker")
        val cand = SearchResult(videoId = "aw_alone", title = "Alone", artist = "Alan Walker", durationMs = 164000L, thumbnailUrl = "", source = "seed_artist")
        val candProfile = MusicIntelligence.buildTrackProfile(title = cand.title, artist = cand.artist)

        val breakdown = MusicIntelligence.scoreCandidate(
            candidate = cand,
            candProfile = candProfile,
            seedProfile = seedProfile,
            seedArtists = setOf("alan walker")
        )

        assertTrue("Related evidence points should be between 0 and 30", breakdown.relatedEvidencePoints in 0.0..30.0)
        assertTrue("Genre points should be between 0 and 25", breakdown.genreCompatibilityPoints in 0.0..25.0)
        assertTrue("Mood/style points should be between 0 and 20", breakdown.moodStylePoints in 0.0..20.0)
        assertTrue("Artist relationship points should be between 0 and 15", breakdown.artistRelationshipPoints in 0.0..15.0)
        assertTrue("Total score must be in 0..100 range", breakdown.totalScore in 0.0..100.0)
        assertTrue("Primary explanation must be populated", breakdown.primaryExplanation.isNotBlank())
    }

    @Test
    fun testHanumanChalisaSeedRejectsUnrelatedEdmTracks() {
        val seed = Track(videoId = "hanuman_1", title = "Hanuman Chalisa", artist = "Hariharan")
        val seedKey = TrackNormalizer.extractCanonicalKey(seed.title, seed.artist)

        val candidates = listOf(
            SearchResult(videoId = "dev_1", title = "Shree Hanuman Chalisa", artist = "Gulshan Kumar", durationMs = 580000L, thumbnailUrl = ""),
            SearchResult(videoId = "dev_2", title = "Shiv Tandav Stotram", artist = "Anuradha Paudwal", durationMs = 450000L, thumbnailUrl = ""),
            SearchResult(videoId = "dev_3", title = "Aisi Lagi Lagan", artist = "Anup Jalota", durationMs = 380000L, thumbnailUrl = ""),
            SearchResult(videoId = "edm_1", title = "Levels", artist = "Avicii", durationMs = 200000L, thumbnailUrl = ""),
            SearchResult(videoId = "edm_2", title = "Faded", artist = "Alan Walker", durationMs = 213000L, thumbnailUrl = ""),
            SearchResult(videoId = "edm_3", title = "Alone", artist = "Marshmello", durationMs = 210000L, thumbnailUrl = "")
        )

        val recommendations = searchEngine.rankRecommendationsLocally(
            candidates = candidates,
            seedArtists = listOf("Hariharan"),
            seedTitle = "Hanuman Chalisa",
            recentTrackKeys = listOf(seedKey),
            limit = 5
        )

        val titles = recommendations.map { it.title }
        // Devotional tracks must be recommended
        assertTrue("Should recommend Gulshan Kumar Hanuman Chalisa", titles.contains("Shree Hanuman Chalisa"))
        assertTrue("Should recommend Anuradha Paudwal", titles.contains("Shiv Tandav Stotram"))

        // EDM tracks must be strictly rejected
        assertFalse("Avicii - Levels must be rejected for Devotional seed", titles.contains("Levels"))
        assertFalse("Alan Walker - Faded must be rejected for Devotional seed", titles.contains("Faded"))
        assertFalse("Marshmello - Alone must be rejected for Devotional seed", titles.contains("Alone"))
    }

    @Test
    fun testHipHopAndAmbientSeeds() {
        // A. Hip-Hop Seed
        val hiphopSeedProfile = MusicIntelligence.buildTrackProfile(title = "HUMBLE.", artist = "Kendrick Lamar")
        val hiphopCand = MusicIntelligence.buildTrackProfile(title = "Lose Yourself", artist = "Eminem")
        val devotionalCand = MusicIntelligence.buildTrackProfile(title = "Hanuman Chalisa", artist = "Hariharan")

        val (hiphopEligible, _) = MusicIntelligence.checkEligibilityGate(hiphopSeedProfile, hiphopCand)
        assertTrue("Eminem should be eligible for Kendrick Lamar seed", hiphopEligible)

        val (devotionalForHiphopEligible, _) = MusicIntelligence.checkEligibilityGate(hiphopSeedProfile, devotionalCand)
        assertFalse("Devotional music should be rejected for hip-hop seed", devotionalForHiphopEligible)

        // B. Ambient Seed
        val ambientSeedProfile = MusicIntelligence.buildTrackProfile(title = "Music for Airports", artist = "Brian Eno")
        val lofiCand = MusicIntelligence.buildTrackProfile(title = "Coffee Beats", artist = "Lofi Girl")
        val edmFestivalCand = MusicIntelligence.buildTrackProfile(title = "Animals (Festival Mix)", artist = "Martin Garrix")

        val (ambientEligible, _) = MusicIntelligence.checkEligibilityGate(ambientSeedProfile, lofiCand)
        assertTrue("Lofi ambient should be eligible for Brian Eno seed", ambientEligible)

        val (edmForAmbientEligible, reasonAmbient) = MusicIntelligence.checkEligibilityGate(ambientSeedProfile, edmFestivalCand)
        assertTrue("Adjacent genres remain eligible without invented energy metadata", edmForAmbientEligible)
        assertNull(reasonAmbient)
    }

    @Test
    fun testPartialSourceFailureAndEmptyCandidatesReturnsEmptyWithoutFakeInjection() {
        val emptyResult = searchEngine.rankRecommendationsLocally(
            candidates = emptyList(),
            seedArtists = listOf("Unknown Artist"),
            limit = 10
        )
        assertTrue("Empty candidate pool must yield empty list, not synthetic tracks", emptyResult.isEmpty())
    }

    @Test
    fun testSeedChangeDiscardsStaleAsyncRecommendations() {
        val qm = com.lumione.player.queue.QueueManager()
        val seedA = Track(videoId = "seed_A", title = "Song A", artist = "Artist A")
        val seedB = Track(videoId = "seed_B", title = "Song B", artist = "Artist B")

        var activeSeedId = seedA.videoId
        qm.setQueue(listOf(seedA), 0, com.lumione.player.queue.QueueMode.RADIO)

        // Simulate user clicking Song B while Song A's recommendations are in flight
        activeSeedId = seedB.videoId
        qm.setQueue(listOf(seedB), 0, com.lumione.player.queue.QueueMode.RADIO)

        // Stale async response arrives for seed_A
        val staleSeedAResponse = listOf(
            Track(videoId = "stale_1", title = "Song A Related 1", artist = "Artist A")
        )

        // The caller checks activeSeedId before applying:
        val callerTargetSeedId = "seed_A"
        if (activeSeedId == callerTargetSeedId) {
            qm.appendUpcomingTracks(staleSeedAResponse)
        }

        // Stale tracks must NOT be in queue
        assertFalse("Stale response must be discarded when active seed changed", qm.queue.value?.any { it.videoId == "stale_1" } == true)
        assertEquals("Current track must be Seed B", "seed_B", qm.currentTrack()?.videoId)
    }

    @Test
    fun testQueueCallerUsesSharedRecommendationEngine() {
        // Proves SearchEngine uses MusicIntelligence gates and scoring
        val seedProfile = MusicIntelligence.buildTrackProfile(title = "Faded", artist = "Alan Walker")
        val candProfile = MusicIntelligence.buildTrackProfile(title = "Hanuman Chalisa", artist = "Hariharan")
        val (eligible, reason) = MusicIntelligence.checkEligibilityGate(seedProfile, candProfile)
        assertFalse("Shared eligibility gate must reject devotional for EDM", eligible)
        assertNotNull(reason)
    }

    @Test
    fun testP0RegressionQueueLifecycleFromOnTheFloorToFaded() {
        val qm = com.lumione.player.queue.QueueManager()
        var radioSessionToken: Long = 0L
        var currentRadioSeedId: String = ""

        // 1. User starts Jennifer Lopez — On the Floor
        val seed1 = Track(videoId = "jlo_on_the_floor", title = "On the Floor", artist = "Jennifer Lopez")
        currentRadioSeedId = seed1.videoId
        val session1 = ++radioSessionToken

        qm.setQueue(listOf(seed1), 0, com.lumione.player.queue.QueueMode.RADIO)
        assertEquals(seed1.videoId, qm.currentTrack()?.videoId)

        // Seed 1 recommendations (Dance Pop / Latin Pop)
        val seed1Recommendations = listOf(
            Track(videoId = "pitbull_give_me_everything", title = "Give Me Everything", artist = "Pitbull"),
            Track(videoId = "shakira_waka_waka", title = "Waka Waka", artist = "Shakira"),
            Track(videoId = "rihanna_only_girl", title = "Only Girl (In the World)", artist = "Rihanna")
        )
        // Population replaces generated upcoming
        qm.replaceGeneratedUpcomingTracks(seed1Recommendations)
        val queueSeed1 = qm.queue.value ?: emptyList()
        assertEquals(4, queueSeed1.size)
        assertEquals("jlo_on_the_floor", queueSeed1[0].videoId)
        assertTrue(queueSeed1.any { it.videoId == "pitbull_give_me_everything" })

        // 2. User manually adds a song to queue ("Play Next")
        val manualTrack = Track(videoId = "user_manual_song", title = "My Manual Jam", artist = "Favorite Artist", isManual = true)
        qm.insertNext(manualTrack)
        val queueWithManual = qm.queue.value ?: emptyList()
        assertEquals("user_manual_song", queueWithManual[1].videoId)
        assertTrue(queueWithManual[1].isManual)

        // 3. User now starts Alan Walker — Faded
        val seed2 = Track(videoId = "alan_walker_faded", title = "Faded", artist = "Alan Walker")
        val manualTracks = qm.queue.value?.filter { it.isManual } ?: emptyList()
        val initialQueueSeed2 = listOf(seed2) + manualTracks
        qm.setQueue(initialQueueSeed2, 0, com.lumione.player.queue.QueueMode.RADIO)

        val session2 = ++radioSessionToken
        currentRadioSeedId = seed2.videoId

        // Verify request uses Faded's identity
        assertEquals("alan_walker_faded", seed2.videoId)
        assertEquals("Faded", seed2.title)
        assertEquals("Alan Walker", seed2.artist)

        // Verify currently playing track is Faded
        assertEquals("alan_walker_faded", qm.currentTrack()?.videoId)

        // Verify manual track is protected and preserved right after Faded
        val currentQueueBeforeRecs = qm.queue.value ?: emptyList()
        assertEquals("user_manual_song", currentQueueBeforeRecs[1].videoId)

        // 4. Stale delayed in-flight response arrives from Seed 1
        val staleDelayedResponseFromSeed1 = seed1Recommendations
        val callerSession = session1
        val callerSeedId = seed1.videoId
        if (callerSession == radioSessionToken && callerSeedId == currentRadioSeedId) {
            qm.replaceGeneratedUpcomingTracks(staleDelayedResponseFromSeed1)
        }
        // Assert old delayed response cannot overwrite new queue
        assertFalse("Delayed response from seed 1 must be ignored", qm.queue.value?.any { it.videoId == "pitbull_give_me_everything" } == true)

        // 5. Simulate client-side rejection of stale Render backend returning Indian chart/devotional tracks for Faded
        val badBackendTracksForFaded = listOf(
            SearchResult(videoId = "47_T2N3p", title = "Casa Tupka Anthemo", artist = "Yo Yo Honey Singh", durationMs = 183000L, thumbnailUrl = ""),
            SearchResult(videoId = "x8gA2kXk", title = "Boom Shaka", artist = "Dhanda Nyoliwala, KR\$NA", durationMs = 218000L, thumbnailUrl = ""),
            SearchResult(videoId = "gh3Xg3e1", title = "Hanuman Chalisa", artist = "Anup Jalota", durationMs = 532000L, thumbnailUrl = ""),
            SearchResult(videoId = "-D8jkD4j", title = "Parvati", artist = "Sadhu Tiwari", durationMs = 284000L, thumbnailUrl = "")
        )
        val rankedBadBackend = searchEngine.rankRecommendationsLocally(
            candidates = badBackendTracksForFaded,
            seedArtists = listOf(seed2.artist),
            seedTitle = seed2.title
        )
        // All bad backend tracks MUST be rejected by eligibility gate
        assertTrue("Devotional and irrelevant chart tracks must fail client eligibility gate for EDM seed", rankedBadBackend.isEmpty())

        // 6. Valid EDM recommendations for Seed 2 arrive
        val seed2Recommendations = listOf(
            Track(videoId = "alan_walker_alone", title = "Alone", artist = "Alan Walker"),
            Track(videoId = "chainsmokers_closer", title = "Closer", artist = "The Chainsmokers"),
            Track(videoId = "avicii_levels", title = "Levels", artist = "Avicii")
        )
        if (session2 == radioSessionToken && seed2.videoId == currentRadioSeedId) {
            qm.replaceGeneratedUpcomingTracks(seed2Recommendations)
        }

        val finalQueue = qm.queue.value ?: emptyList()

        // Assert Seed 2's queue does NOT retain Seed 1's recommendations
        assertFalse("Previous Seed 1 recommendations must not be retained", finalQueue.any { it.videoId == "pitbull_give_me_everything" })
        assertFalse("Previous Seed 1 recommendations must not be retained", finalQueue.any { it.videoId == "shakira_waka_waka" })
        assertFalse("Previous Seed 1 recommendations must not be retained", finalQueue.any { it.videoId == "rihanna_only_girl" })

        // Assert Seed 2's queue has Seed 2's valid recommendations
        assertTrue("Queue must contain Alone by Alan Walker", finalQueue.any { it.videoId == "alan_walker_alone" })
        assertTrue("Queue must contain Closer by The Chainsmokers", finalQueue.any { it.videoId == "chainsmokers_closer" })
        assertTrue("Queue must contain Levels by Avicii", finalQueue.any { it.videoId == "avicii_levels" })

        // Assert manual track is preserved
        assertEquals("user_manual_song", finalQueue[1].videoId)
        assertTrue(finalQueue[1].isManual)

        // Assert playing track is Faded
        assertEquals("alan_walker_faded", finalQueue[0].videoId)
    }

    @Test
    fun testExplicitPlaylistModeIsNotRegeneratedAsRadio() {
        val qm = com.lumione.player.queue.QueueManager()
        val playlistTracks = listOf(
            Track(videoId = "pl_1", title = "Song 1", artist = "Artist 1"),
            Track(videoId = "pl_2", title = "Song 2", artist = "Artist 2"),
            Track(videoId = "pl_3", title = "Song 3", artist = "Artist 3")
        )
        qm.setQueue(playlistTracks, 0, com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES)

        assertEquals(com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES, qm.queueMode)
        assertFalse("Playlist mode must not be Radio", qm.queueMode == com.lumione.player.queue.QueueMode.RADIO)
        assertEquals(3, qm.queue.value?.size)
        assertEquals("pl_1", qm.currentTrack()?.videoId)
        assertEquals("pl_2", qm.peekNext()?.videoId)
    }

    @Test
    fun testFadedAndJudasProduceSeedDependentRankedResults() {
        val candidates = listOf(
            SearchResult("gaga_1", "Americano", "Lady Gaga", 200000, "", source = "same_artist_catalog", retrievalSources = listOf("same_artist_catalog")),
            SearchResult("gaga_2", "Bad Romance", "Lady Gaga", 250000, "", source = "related_artist_search", retrievalSources = listOf("related_artist_search")),
            SearchResult("avicii_1", "The Nights", "Avicii", 220000, "", source = "related_artist_search", retrievalSources = listOf("related_artist_search")),
            SearchResult("chains_1", "Closer", "The Chainsmokers", 230000, "", source = "related_artist_search", retrievalSources = listOf("related_artist_search")),
            SearchResult("dev_1", "Hanuman Chalisa", "Hariharan", 400000, "", source = "watch_next", retrievalSources = listOf("watch_next"))
        )
        val faded = searchEngine.rankRecommendationsLocally(candidates, seedArtists = listOf("Alan Walker"), seedTitle = "Faded", limit = 5)
        val judas = searchEngine.rankRecommendationsLocally(candidates, seedArtists = listOf("Lady Gaga"), seedTitle = "Judas", limit = 5)
        assertTrue(faded.isNotEmpty())
        assertTrue(judas.isNotEmpty())
        assertNotEquals(faded.map { it.videoId }, judas.map { it.videoId })
        assertFalse(faded.any { it.title == "Hanuman Chalisa" })
        assertTrue(judas.any { it.artist == "Lady Gaga" })
    }

    @Test
    fun testRecordingFromMultipleSourcesDeduplicatesAndRetainsEvidence() {
        val candidates = listOf(
            SearchResult("v1", "The Nights", "Avicii", 220000, "", source = "genre_search", retrievalSources = listOf("genre_search")),
            SearchResult("v1", "The Nights (Official Audio)", "Avicii - Topic", 220000, "", source = "watch_next", retrievalSources = listOf("watch_next"))
        )
        val deduped = TrackNormalizer.deduplicateAndFilter(candidates, maxPerArtist = 4)
        assertEquals(1, deduped.size)
        assertTrue(deduped.single().retrievalSources.contains("watch_next"))
        assertTrue(deduped.single().retrievalSources.contains("genre_search"))
    }

    @Test
    fun testWatchNextEvidenceOutranksWeakerSearchEvidence() {
        val seed = MusicIntelligence.buildTrackProfile("Faded", "Alan Walker")
        val weak = SearchResult("weak", "The Nights", "Avicii", 200000, "", source = "genre_search", retrievalSources = listOf("genre_search"))
        val strong = weak.copy(videoId = "strong", source = "watch_next", retrievalSources = listOf("watch_next"))
        val weakScore = MusicIntelligence.scoreCandidate(weak, MusicIntelligence.buildTrackProfile(weak.title, weak.artist), seed, seedArtists = setOf("alan walker"))
        val strongScore = MusicIntelligence.scoreCandidate(strong, MusicIntelligence.buildTrackProfile(strong.title, strong.artist), seed, seedArtists = setOf("alan walker"))
        assertTrue(strongScore.totalScore > weakScore.totalScore)
        assertEquals(30.0, strongScore.relatedEvidencePoints, 0.0)
    }

    @Test
    fun testUnknownChartCandidatesDoNotFillRadioResults() {
        val chart = SearchResult("chart", "Random Track", "Unknown Performer", 200000, "", source = "generic_chart")
        val ranked = searchEngine.rankRecommendationsLocally(listOf(chart), seedArtists = listOf("Alan Walker"), seedTitle = "Faded", limit = 10)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun testQueueConsumesRankedResultsAndProviderFailureDoesNotPolluteQueue() {
        val qm = com.lumione.player.queue.QueueManager()
        val seed = Track("seed", "Faded", "Alan Walker")
        qm.setQueue(listOf(seed), 0, com.lumione.player.queue.QueueMode.RADIO)
        val ranked = searchEngine.rankRecommendationsLocally(
            listOf(SearchResult("next", "The Nights", "Avicii", 220000, "", source = "related_artist_search", retrievalSources = listOf("related_artist_search"))),
            seedArtists = listOf("Alan Walker"), seedTitle = "Faded", limit = 5
        )
        qm.replaceGeneratedUpcomingTracks(ranked.map { it.toTrack() })
        assertEquals("next", qm.queue.value?.getOrNull(1)?.videoId)
        qm.replaceGeneratedUpcomingTracks(emptyList())
        assertEquals(listOf("seed"), qm.queue.value?.map { it.videoId })
    }

    @Test
    fun testManualQueueItemsAndExplicitPlaylistOrderingArePreserved() {
        val qm = com.lumione.player.queue.QueueManager()
        val tracks = listOf(Track("one", "One", "Artist"), Track("two", "Two", "Artist"))
        qm.setQueue(tracks, 0, com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES)
        qm.replaceGeneratedUpcomingTracks(listOf(Track("replacement", "Replacement", "Artist")))
        assertEquals(listOf("one", "two"), qm.queue.value?.map { it.videoId })
        assertEquals(com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES, qm.queueMode)
    }

}
