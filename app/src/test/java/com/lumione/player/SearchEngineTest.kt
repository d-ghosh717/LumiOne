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
        assertEquals("Alan Walker - Faded", first.title)
        assertEquals("Alan Walker", first.artist)
        assertEquals(213000L, first.durationMs)
        assertEquals("https://i.ytimg.com/vi/60ItHLz5WEA/hqdefault.jpg", first.thumbnailUrl)
        assertEquals("lumione", first.provider)

        val second = results[1]
        assertEquals("4NRXx6U8ABQ", second.videoId)
        assertEquals("The Weeknd - Blinding Lights", second.title)
        assertEquals("The Weeknd", second.artist)
        assertEquals(263000L, second.durationMs)
        assertEquals("https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", second.thumbnailUrl)
        assertEquals("lumione", second.provider)
    }

    @Test
    fun testParseBackendResultsEmptyOrMalformed() {
        val engine = SearchEngine()
        assertTrue(engine.parseBackendResults("").isEmpty())
        assertTrue(engine.parseBackendResults("{}").isEmpty())
        assertTrue(engine.parseBackendResults("""{"results": []}""").isEmpty())
        assertTrue(engine.parseBackendResults("invalid json").isEmpty())
    }
}
