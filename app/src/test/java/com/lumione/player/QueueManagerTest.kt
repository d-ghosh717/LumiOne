package com.lumione.player

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.RepeatMode
import com.lumione.player.queue.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class QueueManagerTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var queueManager: QueueManager

    @Before
    fun setup() {
        queueManager = QueueManager()
    }

    @Test
    fun testTrackStreamUrl() {
        val trackWithDefault = Track(
            videoId = "D8m9w",
            title = "Chill Beats",
            artist = "Lofi Producer"
        )
        assertEquals(
            "https://api.audius.co/v1/tracks/D8m9w/stream?app_name=LumiOne",
            trackWithDefault.getEffectiveStreamUrl()
        )

        val trackWithCustom = Track(
            videoId = "D8m9w",
            title = "Chill Beats",
            artist = "Lofi Producer",
            streamUrl = "https://custom.stream/audio.mp3"
        )
        assertEquals("https://custom.stream/audio.mp3", trackWithCustom.getEffectiveStreamUrl())
    }

    @Test
    fun testQueueNavigation() {
        val tracks = listOf(
            Track("t1", "Track 1", "Artist 1"),
            Track("t2", "Track 2", "Artist 2"),
            Track("t3", "Track 3", "Artist 3")
        )

        queueManager.setQueue(tracks, 0)
        assertEquals(0, queueManager.currentIndex.value)
        assertEquals("t1", queueManager.currentTrack()?.videoId)

        // Next
        val next = queueManager.nextTrack()
        assertEquals("t2", next?.videoId)
        assertEquals(1, queueManager.currentIndex.value)

        // Next again
        val next2 = queueManager.nextTrack()
        assertEquals("t3", next2?.videoId)
        assertEquals(2, queueManager.currentIndex.value)

        // At end with repeat NONE -> null
        val noNext = queueManager.nextTrack()
        assertNull(noNext)
        assertEquals(2, queueManager.currentIndex.value)

        // Previous back through history
        val prev = queueManager.previousTrack()
        assertEquals("t2", prev?.videoId)

        val prev2 = queueManager.previousTrack()
        assertEquals("t1", prev2?.videoId)
    }

    @Test
    fun testRepeatOneAndRepeatAll() {
        val tracks = listOf(
            Track("t1", "Track 1", "Artist 1"),
            Track("t2", "Track 2", "Artist 2")
        )

        queueManager.setQueue(tracks, 1) // At last track

        // Repeat ALL wraps to 0
        queueManager.repeatMode = RepeatMode.ALL
        val wrapped = queueManager.nextTrack()
        assertEquals("t1", wrapped?.videoId)
        assertEquals(0, queueManager.currentIndex.value)

        // Repeat ONE stays on current
        queueManager.repeatMode = RepeatMode.ONE
        val repeatSame = queueManager.nextTrack()
        assertEquals("t1", repeatSame?.videoId)
        assertEquals(0, queueManager.currentIndex.value)
    }

    @Test
    fun testInsertAndRemove() {
        val tracks = listOf(
            Track("t1", "Track 1", "Artist 1"),
            Track("t2", "Track 2", "Artist 2")
        )
        queueManager.setQueue(tracks, 0)

        // Insert next
        val inserted = Track("t_next", "Inserted", "Artist")
        queueManager.insertNext(inserted)

        assertEquals(3, queueManager.queue.value?.size)
        assertEquals("t_next", queueManager.queue.value?.get(1)?.videoId)

        // Remove
        queueManager.removeAt(1)
        assertEquals(2, queueManager.queue.value?.size)
        assertEquals("t2", queueManager.queue.value?.get(1)?.videoId)
    }
}
