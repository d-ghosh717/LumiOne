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
        assertFalse(recorder.onPlaybackUpdate(faded, false))
        assertTrue(recorder.onPlaybackUpdate(faded, true))
        assertFalse(recorder.onPlaybackUpdate(faded, true))
        assertFalse(recorder.onPlaybackUpdate(faded, false))
        assertTrue(recorder.onPlaybackUpdate(faded, true))
        assertTrue(recorder.onPlaybackUpdate(judas, true))
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
