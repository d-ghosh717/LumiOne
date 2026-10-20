package com.lumione.player

import com.lumione.player.data.PlaylistManager
import com.lumione.player.queue.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaylistManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var playlistManager: PlaylistManager

    private val testTrack1 = Track(
        videoId = "track_001",
        title = "Midnight Echoes",
        artist = "Solar Drift",
        durationMs = 210000L,
        thumbnailUrl = "https://example.com/art1.jpg"
    )

    private val testTrack2 = Track(
        videoId = "track_002",
        title = "Neon Waves",
        artist = "Cyberpulse",
        durationMs = 185000L,
        thumbnailUrl = "https://example.com/art2.jpg"
    )

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        playlistManager = PlaylistManager(fakePrefs)
    }

    @Test
    fun testCreatePlaylist_validName_createsAndPersists() {
        val playlist = playlistManager.createPlaylist("Chill Vibez", "Late night listening")
        assertNotNull(playlist.id)
        assertEquals("Chill Vibez", playlist.name)
        assertEquals("Late night listening", playlist.description)
        assertEquals(0, playlist.trackCount)

        val retrieved = playlistManager.getPlaylist(playlist.id)
        assertNotNull(retrieved)
        assertEquals("Chill Vibez", retrieved?.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreatePlaylist_emptyName_throwsException() {
        playlistManager.createPlaylist("   ")
    }

    @Test
    fun testAddTrackToPlaylist_addsAndPreventsDuplicates() {
        val playlist = playlistManager.createPlaylist("Favorites Mix")
        val addedFirst = playlistManager.addTrackToPlaylist(playlist.id, testTrack1)
        assertTrue(addedFirst)

        val updated = playlistManager.getPlaylist(playlist.id)
        assertEquals(1, updated?.trackCount)
        assertEquals(testTrack1.videoId, updated?.tracks?.get(0)?.videoId)

        // Attempting to add duplicate should return false
        val addedSecond = playlistManager.addTrackToPlaylist(playlist.id, testTrack1)
        assertFalse(addedSecond)
        assertEquals(1, playlistManager.getPlaylist(playlist.id)?.trackCount)

        // Adding different track should succeed
        val addedOther = playlistManager.addTrackToPlaylist(playlist.id, testTrack2)
        assertTrue(addedOther)
        assertEquals(2, playlistManager.getPlaylist(playlist.id)?.trackCount)
    }

    @Test
    fun testRemoveTrackFromPlaylist_removesOnlyTargetTrack() {
        val playlist = playlistManager.createPlaylist("Roadtrip")
        playlistManager.addTrackToPlaylist(playlist.id, testTrack1)
        playlistManager.addTrackToPlaylist(playlist.id, testTrack2)

        assertEquals(2, playlistManager.getPlaylist(playlist.id)?.trackCount)

        val removed = playlistManager.removeTrackFromPlaylist(playlist.id, testTrack1.videoId)
        assertTrue(removed)

        val updated = playlistManager.getPlaylist(playlist.id)
        assertEquals(1, updated?.trackCount)
        assertEquals(testTrack2.videoId, updated?.tracks?.get(0)?.videoId)
    }

    @Test
    fun testRenamePlaylist_updatesNameAndPreservesTracks() {
        val playlist = playlistManager.createPlaylist("Work Mix")
        playlistManager.addTrackToPlaylist(playlist.id, testTrack1)

        val renamed = playlistManager.renamePlaylist(playlist.id, "Deep Focus", "Coding beats")
        assertTrue(renamed)

        val updated = playlistManager.getPlaylist(playlist.id)
        assertEquals("Deep Focus", updated?.name)
        assertEquals("Coding beats", updated?.description)
        assertEquals(1, updated?.trackCount)
    }

    @Test
    fun testDeletePlaylist_removesPlaylist() {
        val p1 = playlistManager.createPlaylist("List 1")
        val p2 = playlistManager.createPlaylist("List 2")

        assertEquals(2, playlistManager.getPlaylists().size)

        val deleted = playlistManager.deletePlaylist(p1.id)
        assertTrue(deleted)

        val playlists = playlistManager.getPlaylists()
        assertEquals(1, playlists.size)
        assertEquals(p2.id, playlists[0].id)
        assertNull(playlistManager.getPlaylist(p1.id))
    }
}
