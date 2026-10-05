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
                  "thumbnail": "https://i.ytimg.com/vi/60ItHLz5WEA/hqdefault.jpg"
                },
                {
                  "id": "4NRXx6U8ABQ",
                  "title": "The Weeknd - Blinding Lights",
                  "artist": "The Weeknd",
                  "duration": 263,
                  "durationFormatted": "4:23",
                  "thumbnail": "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg"
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

        val second = results[1]
        assertEquals("4NRXx6U8ABQ", second.videoId)
        assertEquals("The Weeknd - Blinding Lights", second.title)
        assertEquals("The Weeknd", second.artist)
        assertEquals(263000L, second.durationMs)
        assertEquals("https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg", second.thumbnailUrl)
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
    fun testParseInvidiousDirectValidJson() {
        val json = """
            [
              {
                "videoId": "dQw4w9WgXcQ",
                "title": "Never Gonna Give You Up",
                "author": "Rick Astley",
                "lengthSeconds": 212,
                "videoThumbnails": [
                  {
                    "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
                  }
                ]
              }
            ]
        """.trimIndent()

        val engine = SearchEngine()
        val results = engine.parseInvidiousDirect(json)

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("dQw4w9WgXcQ", item.videoId)
        assertEquals("Never Gonna Give You Up", item.title)
        assertEquals("Rick Astley", item.artist)
        assertEquals(212000L, item.durationMs)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", item.thumbnailUrl)
    }
}
