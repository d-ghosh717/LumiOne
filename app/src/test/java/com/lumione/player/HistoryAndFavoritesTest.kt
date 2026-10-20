package com.lumione.player

import android.content.SharedPreferences
import com.lumione.player.data.FavoritesManager
import com.lumione.player.data.HistoryManager
import com.lumione.player.queue.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeSharedPreferences : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val temp = mutableMapOf<String, Any?>()
        val removes = mutableSetOf<String>()
        var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) temp[key] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removes.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            map.putAll(temp)
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class HistoryAndFavoritesTest {

    private lateinit var fakePrefsHistory: FakeSharedPreferences
    private lateinit var fakePrefsFavorites: FakeSharedPreferences
    private lateinit var historyManager: HistoryManager
    private lateinit var favoritesManager: FavoritesManager

    private val track1 = Track(videoId = "v1", title = "Midnight City", artist = "M83", durationMs = 240000L)
    private val track2 = Track(videoId = "v2", title = "Get Lucky", artist = "Daft Punk", durationMs = 248000L)
    private val track3 = Track(videoId = "v3", title = "Starboy", artist = "The Weeknd", durationMs = 230000L)

    @Before
    fun setup() {
        fakePrefsHistory = FakeSharedPreferences()
        fakePrefsFavorites = FakeSharedPreferences()
        historyManager = HistoryManager(fakePrefsHistory)
        favoritesManager = FavoritesManager(fakePrefsFavorites)
    }

    @Test
    fun testRecordHistoryAndDeduplication() {
        assertEquals(0, historyManager.getHistory().size)

        // Record track 1
        historyManager.recordTrack(track1)
        val h1 = historyManager.getHistory()
        assertEquals(1, h1.size)
        assertEquals("v1", h1[0].track.videoId)
        assertEquals("Midnight City", h1[0].track.title)

        // Record track 2
        historyManager.recordTrack(track2)
        val h2 = historyManager.getHistory()
        assertEquals(2, h2.size)
        assertEquals("v2", h2[0].track.videoId)
        assertEquals("v1", h2[1].track.videoId)

        // Play track 1 again -> should move to top without creating duplicate
        historyManager.recordTrack(track1)
        val h3 = historyManager.getHistory()
        assertEquals(2, h3.size)
        assertEquals("v1", h3[0].track.videoId)
        assertEquals("v2", h3[1].track.videoId)

        // Clear history
        historyManager.clearHistory()
        assertEquals(0, historyManager.getHistory().size)
    }

    @Test
    fun testFavoritesManagement() {
        assertFalse(favoritesManager.isFavorite("v1"))

        // Add to favorites
        val isFav1 = favoritesManager.toggleFavorite(track1)
        assertTrue(isFav1)
        assertTrue(favoritesManager.isFavorite("v1"))
        assertEquals(1, favoritesManager.getFavorites().size)
        assertEquals("Midnight City", favoritesManager.getFavorites()[0].title)

        // Toggle off
        val isFav2 = favoritesManager.toggleFavorite(track1)
        assertFalse(isFav2)
        assertFalse(favoritesManager.isFavorite("v1"))
        assertEquals(0, favoritesManager.getFavorites().size)

        // Add multiple
        favoritesManager.addFavorite(track1)
        favoritesManager.addFavorite(track2)
        favoritesManager.addFavorite(track3)
        assertEquals(3, favoritesManager.getFavorites().size)

        // Remove one
        favoritesManager.removeFavorite("v2")
        assertEquals(2, favoritesManager.getFavorites().size)
        assertFalse(favoritesManager.isFavorite("v2"))
        assertTrue(favoritesManager.isFavorite("v1"))
        assertTrue(favoritesManager.isFavorite("v3"))

        // Clear all
        favoritesManager.clearFavorites()
        assertEquals(0, favoritesManager.getFavorites().size)
    }
}
