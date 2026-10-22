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
        val trackWithoutCustom = Track(
            videoId = "D8m9w",
            title = "Chill Beats",
            artist = "Lofi Producer"
        )
        assertEquals("", trackWithoutCustom.getEffectiveStreamUrl())

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

    @Test
    fun testManualPlayNextAndAddToQueuePreservedDuringRefresh() {
        val seed = Track("seed", "Seed Song", "Seed Artist")
        queueManager.setQueue(listOf(seed), 0, com.lumione.player.queue.QueueMode.RADIO)

        // Initial radio population
        val radio1 = Track("r1", "Radio Song 1", "Artist R1")
        val radio2 = Track("r2", "Radio Song 2", "Artist R2")
        queueManager.appendUpcomingTracks(listOf(radio1, radio2))
        assertEquals(3, queueManager.queue.value?.size)

        // User manually queues a song next (Play next)
        val userManualNext = Track("user_manual_1", "User Selected Song", "Manual Artist")
        queueManager.insertNext(userManualNext)
        assertTrue(queueManager.queue.value?.get(1)?.isManual == true)
        assertEquals("user_manual_1", queueManager.queue.value?.get(1)?.videoId)

        // Queue is refreshed with new radio recommendations
        val freshRadio1 = Track("r3", "Fresh Radio 3", "Artist R3")
        val freshRadio2 = Track("r4", "Fresh Radio 4", "Artist R4")
        queueManager.replaceGeneratedUpcomingTracks(listOf(freshRadio1, freshRadio2))

        val updatedQueue = queueManager.queue.value ?: emptyList()
        // The user manual track must NOT be wiped out!
        assertTrue("User manually added track must be preserved on refresh", updatedQueue.any { it.videoId == "user_manual_1" })
        assertEquals("User manual track should remain next in queue", "user_manual_1", updatedQueue[1].videoId)
    }

    @Test
    fun testPlaylistModePreservesExplicitOrderAndDistinguishedFromRadio() {
        val tracks = listOf(
            Track("p1", "Playlist Track 1", "Artist A"),
            Track("p2", "Playlist Track 2", "Artist B"),
            Track("p3", "Playlist Track 3", "Artist C")
        )
        queueManager.setQueue(tracks, 0, com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES)

        assertEquals(com.lumione.player.queue.QueueMode.PLAYLIST_OR_FAVORITES, queueManager.queueMode)
        assertEquals(3, queueManager.queue.value?.size)
        assertEquals("p1", queueManager.currentTrack()?.videoId)
        assertEquals("p2", queueManager.peekNext()?.videoId)
        assertFalse(queueManager.queueMode == com.lumione.player.queue.QueueMode.RADIO)
    }
}
