package com.lumione.player.data

import android.content.Context
import android.content.SharedPreferences
import com.lumione.player.queue.Track
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val track: Track,
    val timestamp: Long
)

class HistoryManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "lumione_listening_history"
        private const val KEY_HISTORY = "history_entries"
        private const val MAX_HISTORY_SIZE = 100
        private const val DEDUPE_WINDOW_MS = 60_000L // 1 minute window for duplicate track playback

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

        // Deduplication: if the same track is already at the top or was played recently,
        // update its timestamp instead of creating duplicate clutter.
        val existingIndex = currentHistory.indexOfFirst { it.track.videoId == track.videoId }
        if (existingIndex == 0) {
            // Already current track, just refresh timestamp if enough time passed
            currentHistory[0] = HistoryEntry(track, now)
        } else {
            if (existingIndex > 0) {
                currentHistory.removeAt(existingIndex)
            }
            currentHistory.add(0, HistoryEntry(track, now))
        }

        if (currentHistory.size > MAX_HISTORY_SIZE) {
            currentHistory.subList(MAX_HISTORY_SIZE, currentHistory.size).clear()
        }

        saveHistory(currentHistory)
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
                list.add(HistoryEntry(track, timestamp))
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
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
