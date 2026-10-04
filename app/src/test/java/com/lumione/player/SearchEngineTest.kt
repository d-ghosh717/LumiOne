package com.lumione.player

import com.lumione.player.search.SearchEngine
import org.junit.Assert.*
import org.junit.Test

class SearchEngineTest {

    @Test
    fun testParseAudiusTracksValidJson() {
        val json = """
            {
              "data": [
                {
                  "id": "D8m9w",
                  "title": "Summer Vibes",
                  "duration": 185,
                  "user": {
                    "name": "Audius Artist"
                  },
                  "artwork": {
                    "150x150": "https://creatornode.audius.co/ipfs/Qm150",
                    "480x480": "https://creatornode.audius.co/ipfs/Qm480"
                  }
                },
                {
                  "id": "abc1234",
                  "title": "Night Drive",
                  "duration": 210,
                  "user": {
                    "name": "Synth Producer"
                  },
                  "artwork": {
                    "150x150": "https://creatornode.audius.co/ipfs/Qm150_2"
                  }
                }
              ]
            }
        """.trimIndent()

        val engine = SearchEngine()
        val results = engine.parseAudiusTracks(json)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("D8m9w", first.videoId)
        assertEquals("Summer Vibes", first.title)
        assertEquals("Audius Artist", first.artist)
        assertEquals(185000L, first.durationMs)
        assertEquals("https://creatornode.audius.co/ipfs/Qm480", first.thumbnailUrl)
        assertEquals("https://api.audius.co/v1/tracks/D8m9w/stream?app_name=LumiOne", first.streamUrl)

        val second = results[1]
        assertEquals("abc1234", second.videoId)
        assertEquals("Night Drive", second.title)
        assertEquals("Synth Producer", second.artist)
        assertEquals(210000L, second.durationMs)
        assertEquals("https://creatornode.audius.co/ipfs/Qm150_2", second.thumbnailUrl)
    }

    @Test
    fun testParseAudiusTracksEmptyOrMalformed() {
        val engine = SearchEngine()
        assertTrue(engine.parseAudiusTracks("").isEmpty())
        assertTrue(engine.parseAudiusTracks("{}").isEmpty())
        assertTrue(engine.parseAudiusTracks("""{"data": []}""").isEmpty())
        assertTrue(engine.parseAudiusTracks("invalid json").isEmpty())
    }
}
