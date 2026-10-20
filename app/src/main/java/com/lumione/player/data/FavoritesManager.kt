package com.lumione.player.data

import android.content.Context
import android.content.SharedPreferences
import com.lumione.player.queue.Track
import org.json.JSONArray
import org.json.JSONObject

class FavoritesManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "lumione_favorites_store"
        private const val KEY_FAVORITES = "favorite_tracks"

        @Volatile
        private var instance: FavoritesManager? = null

        fun getInstance(context: Context): FavoritesManager {
            return instance ?: synchronized(this) {
                instance ?: FavoritesManager(context).also { instance = it }
            }
        }
    }

    @Synchronized
    fun isFavorite(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        return getFavorites().any { it.videoId == videoId }
    }

    @Synchronized
    fun toggleFavorite(track: Track): Boolean {
        if (track.videoId.isBlank()) return false
        val favorites = getFavorites().toMutableList()
        val index = favorites.indexOfFirst { it.videoId == track.videoId }
        val nowFavorite = if (index >= 0) {
            favorites.removeAt(index)
            false
        } else {
            favorites.add(0, track)
            true
        }
        saveFavorites(favorites)
        return nowFavorite
    }

    @Synchronized
    fun addFavorite(track: Track) {
        if (track.videoId.isBlank()) return
        val favorites = getFavorites().toMutableList()
        if (favorites.none { it.videoId == track.videoId }) {
            favorites.add(0, track)
            saveFavorites(favorites)
        }
    }

    @Synchronized
    fun removeFavorite(videoId: String) {
        val favorites = getFavorites().toMutableList()
        val index = favorites.indexOfFirst { it.videoId == videoId }
        if (index >= 0) {
            favorites.removeAt(index)
            saveFavorites(favorites)
        }
    }

    @Synchronized
    fun getFavorites(): List<Track> {
        val jsonStr = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        val list = mutableListOf<Track>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val trackObj = jsonArray.getJSONObject(i)
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
                list.add(track)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun clearFavorites() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }

    private fun saveFavorites(favorites: List<Track>) {
        try {
            val jsonArray = JSONArray()
            for (track in favorites) {
                val trackObj = JSONObject().apply {
                    put("videoId", track.videoId)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("durationMs", track.durationMs)
                    put("thumbnailUrl", track.thumbnailUrl)
                    put("streamUrl", track.streamUrl)
                    put("provider", track.provider)
                    put("providerId", track.providerId)
                    put("youtubeVideoId", track.youtubeVideoId)
                }
                jsonArray.put(trackObj)
            }
            prefs.edit().putString(KEY_FAVORITES, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
