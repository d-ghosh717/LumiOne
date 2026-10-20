package com.lumione.player

import com.lumione.player.search.SearchEngine
import org.junit.Assert.*
import org.junit.Test

class SearchEngineTest {

    @Test
    fun testParseBackendResultsValidJson() {
        val json = """
            {
              "results": [
                {
                  "id": "60ItHLz5WEA",
                  "title": "Alan Walker - Faded",
                  "artist": "Alan Walker",
                  "duration": 213,
                  "durationFormatted": "3:33",
                  "thumbnail": "https://i.ytimg.com/vi/60ItHLz5WEA/hqdefault.jpg",
                  "provider": "lumione",
                  "providerId": "60ItHLz5WEA"
                },
                {
                  "id": "4NRXx6U8ABQ",
                  "title": "The Weeknd - Blinding Lights",
                  "artist": "The Weeknd",
                  "duration": 263,
                  "durationFormatted": "4:23",
                  "thumbnail": "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg",
                  "provider": "lumione",
                  "providerId": "4NRXx6U8ABQ"
                }
              ]
            }
        """.trimIndent()

        val engine = SearchEngine()
        val results = engine.parseBackendResults(json)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("60ItHLz5WEA", first.videoId)
        assertEquals("60ItHLz5WEA", first.youtubeVideoId)
        assertEquals("Alan Walker - Faded", first.title)
        assertEquals("Alan Walker", first.artist)
        assertEquals(213000L, first.durationMs)
        assertEquals("https://i.ytimg.com/vi/60ItHLz5WEA/hqdefault.jpg", first.thumbnailUrl)
        assertEquals("lumione", first.provider)

        val second = results[1]
        assertEquals("4NRXx6U8ABQ", second.videoId)
        assertEquals("4NRXx6U8ABQ", second.youtubeVideoId)
        assertEquals("The Weeknd - Blinding Lights", second.title)
        assertEquals("The Weeknd", second.artist)
        assertEquals(263000L, second.durationMs)
        assertEquals("https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", second.thumbnailUrl)
        assertEquals("lumione", second.provider)
    }

    @Test
    fun testParseBackendResultsExplicitYoutubeVideoId() {
        val json = """
            {
              "results": [
                {
                  "youtubeVideoId": "60ItHLz5WEA",
                  "title": "Alan Walker - Faded",
                  "artist": "Alan Walker",
                  "duration": 213,
                  "durationFormatted": "3:33",
                  "thumbnail": "https://i.ytimg.com/vi/60ItHLz5WEA/hqdefault.jpg"
                }
              ]
            }
        """.trimIndent()

        val engine = SearchEngine()
        val results = engine.parseBackendResults(json)

        assertEquals(1, results.size)
        assertEquals("60ItHLz5WEA", results[0].videoId)
        assertEquals("60ItHLz5WEA", results[0].youtubeVideoId)
    }

    @Test
    fun testParseBackendResultsEmptyOrMalformed() {
        val engine = SearchEngine()
        assertTrue(engine.parseBackendResults("").isEmpty())
        assertTrue(engine.parseBackendResults("{}").isEmpty())
        assertTrue(engine.parseBackendResults("""{"results": []}""").isEmpty())
        assertTrue(engine.parseBackendResults("invalid json").isEmpty())
    }

    @Test
    fun testIsMusicTrackFiltersIrrelevantContent() {
        // Non-music content must be rejected
        assertFalse(SearchEngine.isMusicTrack("GTA 5 LIVE: INSANE HEIST WITH FRIENDS", "GamerBoy", 3600000L))
        assertFalse(SearchEngine.isMusicTrack("Slime ASMR Most Satisfying Video", "Relax Slime", 600000L))
        assertFalse(SearchEngine.isMusicTrack("Minecraft 100 Days Hardcore Survival Gameplay", "Crafter", 1800000L))
        assertFalse(SearchEngine.isMusicTrack("Roblox Brookhaven Roleplay Episode 4", "RobloxFun", 900000L))
        assertFalse(SearchEngine.isMusicTrack("Reacting to Trending TikToks", "Reactor", 450000L))
        assertFalse(SearchEngine.isMusicTrack("Fortnite Season 5 Battle Pass Walkthrough", "ProGamer", 800000L))

        // Duration bounds: reject < 35s or > 15min (900s)
        assertFalse(SearchEngine.isMusicTrack("Short meme clip", "Creator", 15000L))
        assertFalse(SearchEngine.isMusicTrack("10 Hour Study Ambient Stream", "RelaxChannel", 36000000L))

        // Valid music tracks must be accepted
        assertTrue(SearchEngine.isMusicTrack("Blinding Lights", "The Weeknd", 200000L))
        assertTrue(SearchEngine.isMusicTrack("Die With A Smile", "Lady Gaga & Bruno Mars", 250000L))
        assertTrue(SearchEngine.isMusicTrack("Kesariya (From 'Brahmastra')", "Arijit Singh, Pritam", 268000L))
        assertTrue(SearchEngine.isMusicTrack("Shape of You", "Ed Sheeran", 233000L))
    }
}
