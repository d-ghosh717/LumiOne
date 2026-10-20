package com.lumione.player.data

import android.content.Context
import android.content.SharedPreferences
import com.lumione.player.player.TrackMatcher
import com.lumione.player.queue.Track
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val track: Track,
    val timestamp: Long,
    val playCount: Int = 1,
    val completionCount: Int = 0,
    val skipCount: Int = 0
)

class HistoryManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "lumione_listening_history"
        private const val KEY_HISTORY = "history_entries"
        private const val MAX_HISTORY_SIZE = 100

        @Volatile
        private var instance: HistoryManager? = null

        fun getInstance(context: Context): HistoryManager {
            return instance ?: synchronized(this) {
                instance ?: HistoryManager(context).also { instance = it }
            }
        }
    }

    @Synchronized
    fun recordTrack(track: Track) {
        if (track.videoId.isBlank()) return
        val currentHistory = getHistory().toMutableList()
        val now = System.currentTimeMillis()

        val existingIndex = currentHistory.indexOfFirst { it.track.videoId == track.videoId }
        val existing = if (existingIndex >= 0) currentHistory[existingIndex] else null

        val updatedEntry = if (existing != null) {
            HistoryEntry(
                track = track,
                timestamp = now,
                playCount = existing.playCount + 1,
                completionCount = existing.completionCount,
                skipCount = existing.skipCount
            )
        } else {
            HistoryEntry(track = track, timestamp = now, playCount = 1, completionCount = 0, skipCount = 0)
        }

        if (existingIndex >= 0) {
            currentHistory.removeAt(existingIndex)
        }
        currentHistory.add(0, updatedEntry)

        if (currentHistory.size > MAX_HISTORY_SIZE) {
            currentHistory.subList(MAX_HISTORY_SIZE, currentHistory.size).clear()
        }

        saveHistory(currentHistory)
    }

    @Synchronized
    fun recordCompletion(videoId: String) {
        if (videoId.isBlank()) return
        val currentHistory = getHistory().toMutableList()
        val index = currentHistory.indexOfFirst { it.track.videoId == videoId }
        if (index >= 0) {
            val item = currentHistory[index]
            currentHistory[index] = item.copy(completionCount = item.completionCount + 1)
            saveHistory(currentHistory)
        }
    }

    @Synchronized
    fun recordSkip(videoId: String) {
        if (videoId.isBlank()) return
        val currentHistory = getHistory().toMutableList()
        val index = currentHistory.indexOfFirst { it.track.videoId == videoId }
        if (index >= 0) {
            val item = currentHistory[index]
            currentHistory[index] = item.copy(skipCount = item.skipCount + 1)
            saveHistory(currentHistory)
        }
    }

    @Synchronized
    fun getTopArtists(limit: Int = 5): List<String> {
        val history = getHistory()
        val artistScores = mutableMapOf<String, Int>()
        for (entry in history) {
            val rawArtist = entry.track.artist.trim()
            if (rawArtist.isBlank() || rawArtist.equals("Unknown Artist", ignoreCase = true)) continue
            val clean = TrackMatcher.cleanArtist(rawArtist)
            if (clean.isBlank()) continue
            // Weighted score: 1 point per play, 2 bonus points per completion, -1 for skip
            val score = (entry.playCount * 1) + (entry.completionCount * 2) - entry.skipCount
            artistScores[rawArtist] = (artistScores[rawArtist] ?: 0) + score
        }
        return artistScores.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    @Synchronized
    fun getSkippedArtists(): List<String> {
        val history = getHistory()
        val skipMap = mutableMapOf<String, Int>()
        for (entry in history) {
            if (entry.skipCount > entry.completionCount) {
                val rawArtist = entry.track.artist.trim()
                if (rawArtist.isNotBlank() && !rawArtist.equals("Unknown Artist", ignoreCase = true)) {
                    skipMap[rawArtist] = (skipMap[rawArtist] ?: 0) + (entry.skipCount - entry.completionCount)
                }
            }
        }
        return skipMap.entries.filter { it.value >= 2 }.map { it.key }
    }

    @Synchronized
    fun getHistory(): List<HistoryEntry> {
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<HistoryEntry>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val trackObj = obj.getJSONObject("track")
                val track = Track(
                    videoId = trackObj.optString("videoId", ""),
                    title = trackObj.optString("title", "Unknown"),
                    artist = trackObj.optString("artist", "Unknown Artist"),
                    durationMs = trackObj.optLong("durationMs", 0L),
                    thumbnailUrl = trackObj.optString("thumbnailUrl", ""),
                    streamUrl = trackObj.optString("streamUrl", ""),
                    provider = trackObj.optString("provider", "lumione"),
                    providerId = trackObj.optString("providerId", trackObj.optString("videoId", "")),
                    youtubeVideoId = trackObj.optString("youtubeVideoId", trackObj.optString("videoId", ""))
                )
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val playCount = obj.optInt("playCount", 1)
                val completionCount = obj.optInt("completionCount", 0)
                val skipCount = obj.optInt("skipCount", 0)
                list.add(HistoryEntry(track, timestamp, playCount, completionCount, skipCount))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    @Synchronized
    fun removeEntry(videoId: String) {
        val updated = getHistory().filterNot { it.track.videoId == videoId }
        saveHistory(updated)
    }

    private fun saveHistory(entries: List<HistoryEntry>) {
        try {
            val jsonArray = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                val trackObj = JSONObject().apply {
                    put("videoId", entry.track.videoId)
                    put("title", entry.track.title)
                    put("artist", entry.track.artist)
                    put("durationMs", entry.track.durationMs)
                    put("thumbnailUrl", entry.track.thumbnailUrl)
                    put("streamUrl", entry.track.streamUrl)
                    put("provider", entry.track.provider)
                    put("providerId", entry.track.providerId)
                    put("youtubeVideoId", entry.track.youtubeVideoId)
                }
                obj.put("track", trackObj)
                obj.put("timestamp", entry.timestamp)
                obj.put("playCount", entry.playCount)
                obj.put("completionCount", entry.completionCount)
                obj.put("skipCount", entry.skipCount)
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
