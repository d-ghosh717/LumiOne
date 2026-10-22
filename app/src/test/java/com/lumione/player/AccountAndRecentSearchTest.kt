package com.lumione.player

import com.lumione.player.auth.AuthGatePolicy
import com.lumione.player.data.PlaybackHistoryRecorder
import com.lumione.player.data.RecentSearchManager
import com.lumione.player.queue.Track
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AccountAndRecentSearchTest {
    @Test fun firstLaunchAndSignOutRequireAuthentication() {
        assertTrue(AuthGatePolicy.requiresSignIn(null))
        assertTrue(AuthGatePolicy.requiresSignIn(" "))
        assertFalse(AuthGatePolicy.requiresSignIn("firebase-uid"))
        assertTrue(AuthGatePolicy.requiresSignIn(null)) // sign-out returns to the gate
    }

    @Test fun recentSearchesAreDistinctNewestFirstAndCappedAtFive() {
        val manager = RecentSearchManager(FakeSharedPreferences())
        listOf("one", "two", "three", "four", "five", "six").forEachIndexed { index, value ->
            manager.record(value, index.toLong() + 1)
        }
        manager.record("THREE", 20)
        assertEquals(listOf("THREE", "six", "five", "four", "two"), manager.getRecent().map { it.query })
        assertEquals(5, manager.getRecent().size)
    }

    @Test fun historyRecordsOnlyActivePlaybackTransitions() {
        val recorder = PlaybackHistoryRecorder()
        val faded = Track("faded-id", "Faded", "Alan Walker")
        val judas = Track("judas-id", "Judas", "Lady Gaga")

        // Inactive / preparing playback does not record
        assertFalse(recorder.onPlaybackUpdate(faded, false))
        assertFalse(recorder.onPlaybackUpdate(null, true))
        assertFalse(recorder.onPlaybackUpdate(Track("", "Invalid", "Artist"), true))

        // Playback genuinely starts -> records once
        assertTrue(recorder.onPlaybackUpdate(faded, true))

        // Redundant callbacks while playing do not duplicate
        assertFalse(recorder.onPlaybackUpdate(faded, true))
        assertFalse(recorder.onPlaybackUpdate(faded, true))

        // Pausing does not record
        assertFalse(recorder.onPlaybackUpdate(faded, false))

        // Resuming after pause does NOT record a duplicate
        assertFalse(recorder.onPlaybackUpdate(faded, true))

        // Automatic transition or selecting next track records the new track
        assertTrue(recorder.onPlaybackUpdate(judas, true))
        assertFalse(recorder.onPlaybackUpdate(judas, true))

        // Replaying track after track completion or session reset records sensibly
        recorder.resetForNewTrack(faded.videoId)
        assertTrue(recorder.onPlaybackUpdate(faded, true))
        assertFalse(recorder.onPlaybackUpdate(faded, true))
    }

    @Test fun recentSearchesPersistAcrossRestartsAndMoveRepeatsToTop() {
        val fakePrefs = FakeSharedPreferences()
        val manager1 = RecentSearchManager(fakePrefs)

        manager1.record("coldplay", 1000L)
        manager1.record("dualipa", 2000L)
        manager1.record("weeknd", 3000L)

        // Simulate app restart by creating a new manager with the same prefs
        val manager2 = RecentSearchManager(fakePrefs)
        val loaded = manager2.getRecent()
        assertEquals(3, loaded.size)
        assertEquals("weeknd", loaded[0].query)
        assertEquals("dualipa", loaded[1].query)
        assertEquals("coldplay", loaded[2].query)

        // Repeated search should move to the top
        manager2.record("CoLdPlAy", 4000L)
        val afterRepeat = manager2.getRecent()
        assertEquals(3, afterRepeat.size)
        assertEquals("CoLdPlAy", afterRepeat[0].query)
        assertEquals("weeknd", afterRepeat[1].query)
        assertEquals("dualipa", afterRepeat[2].query)

        // Cap at 5 most recent
        manager2.record("queen", 5000L)
        manager2.record("nirvana", 6000L)
        manager2.record("beatles", 7000L)
        val capped = manager2.getRecent()
        assertEquals(5, capped.size)
        assertEquals(listOf("beatles", "nirvana", "queen", "CoLdPlAy", "weeknd"), capped.map { it.query })
    }

    @Test fun firestoreRulesRequireAnAuthenticatedUidOwner() {
        val rulesFile = listOf(File("../firestore.rules"), File("firestore.rules"))
            .firstOrNull { it.isFile } ?: error("firestore.rules not found")
        val rules = rulesFile.readText()
        assertTrue(rules.contains("request.auth != null"))
        assertTrue(rules.contains("request.auth.uid == uid"))
        assertFalse(Regex("allow\\s+(read|write),?\\s*(read|write)?\\s*:\\s*if\\s+true").containsMatchIn(rules))
    }
}
