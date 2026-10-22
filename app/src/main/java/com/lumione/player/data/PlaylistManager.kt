package com.lumione.player.data

import android.content.Context
import android.content.SharedPreferences
import com.lumione.player.queue.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tracks: List<Track> = emptyList()
) {
    val trackCount: Int get() = tracks.size

    val coverUrl: String?
        get() = tracks.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl

    val totalDurationMs: Long
        get() = tracks.sumOf { it.durationMs }
}

class PlaylistManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "lumione_playlist_store"
        private const val KEY_PLAYLISTS = "user_playlists"

        private val instances = ConcurrentHashMap<String, PlaylistManager>()

        fun getInstance(context: Context, uid: String? = null): PlaylistManager {
            val key = uid ?: "legacy"
            val prefsName = if (uid == null) PREFS_NAME else "${PREFS_NAME}_${uid.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
            return instances.getOrPut(key) {
                PlaylistManager(context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
            }
        }
    }

    @Synchronized
    fun getPlaylists(): List<Playlist> {
        val jsonStr = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        val list = mutableListOf<Playlist>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val pObj = jsonArray.getJSONObject(i)
                val tracksJson = pObj.optJSONArray("tracks") ?: JSONArray()
                val tracks = mutableListOf<Track>()
                for (j in 0 until tracksJson.length()) {
                    val tObj = tracksJson.getJSONObject(j)
                    val track = Track(
                        videoId = tObj.optString("videoId", ""),
                        title = tObj.optString("title", "Unknown"),
                        artist = tObj.optString("artist", "Unknown Artist"),
                        durationMs = tObj.optLong("durationMs", 0L),
                        thumbnailUrl = tObj.optString("thumbnailUrl", ""),
                        streamUrl = tObj.optString("streamUrl", ""),
                        provider = tObj.optString("provider", "lumione"),
                        providerId = tObj.optString("providerId", tObj.optString("videoId", "")),
                        youtubeVideoId = tObj.optString("youtubeVideoId", tObj.optString("videoId", ""))
                    )
                    tracks.add(track)
                }

                list.add(
                    Playlist(
                        id = pObj.optString("id", UUID.randomUUID().toString()),
                        name = pObj.optString("name", "Untitled Playlist"),
                        description = pObj.optString("description", ""),
                        createdAt = pObj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = pObj.optLong("updatedAt", System.currentTimeMillis()),
                        tracks = tracks
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun mergePlaylists(incoming: List<Playlist>) {
        val merged = LinkedHashMap<String, Playlist>()
        (getPlaylists() + incoming).forEach { playlist ->
            val existing = merged[playlist.id]
            if (existing == null || playlist.updatedAt > existing.updatedAt) merged[playlist.id] = playlist
        }
        savePlaylists(merged.values.sortedByDescending { it.updatedAt })
    }

    @Synchronized
    fun getPlaylist(id: String): Playlist? {
        if (id.isBlank()) return null
        return getPlaylists().firstOrNull { it.id == id }
    }

    @Synchronized
    fun createPlaylist(name: String, description: String = ""): Playlist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Playlist name cannot be empty" }

        val newPlaylist = Playlist(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            description = description.trim(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            tracks = emptyList()
        )

        val playlists = getPlaylists().toMutableList()
        playlists.add(0, newPlaylist)
        savePlaylists(playlists)
        return newPlaylist
    }

    @Synchronized
    fun renamePlaylist(id: String, newName: String, newDescription: String? = null): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false

        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return false

        val current = playlists[index]
        playlists[index] = current.copy(
            name = trimmed,
            description = newDescription?.trim() ?: current.description,
            updatedAt = System.currentTimeMillis()
        )
        savePlaylists(playlists)
        return true
    }

    @Synchronized
    fun deletePlaylist(id: String): Boolean {
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return false

        playlists.removeAt(index)
        savePlaylists(playlists)
        return true
    }

    @Synchronized
    fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        if (track.videoId.isBlank()) return false
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false

        val current = playlists[index]
        // Check if track is already present to prevent duplicate clutter
        if (current.tracks.any { it.videoId == track.videoId }) {
            return false
        }

        val updatedTracks = current.tracks.toMutableList()
        updatedTracks.add(track)

        playlists[index] = current.copy(
            tracks = updatedTracks,
            updatedAt = System.currentTimeMillis()
        )
        savePlaylists(playlists)
        return true
    }

    @Synchronized
    fun removeTrackFromPlaylist(playlistId: String, videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false

        val current = playlists[index]
        val updatedTracks = current.tracks.toMutableList()
        val trackIndex = updatedTracks.indexOfFirst { it.videoId == videoId }
        if (trackIndex < 0) return false

        updatedTracks.removeAt(trackIndex)
        playlists[index] = current.copy(
            tracks = updatedTracks,
            updatedAt = System.currentTimeMillis()
        )
        savePlaylists(playlists)
        return true
    }

    @Synchronized
    fun clearAllPlaylists() {
        prefs.edit().remove(KEY_PLAYLISTS).apply()
    }

    private fun savePlaylists(playlists: List<Playlist>) {
        try {
            val jsonArray = JSONArray()
            for (p in playlists) {
                val pObj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("description", p.description)
                    put("createdAt", p.createdAt)
                    put("updatedAt", p.updatedAt)

                    val tracksArray = JSONArray()
                    for (track in p.tracks) {
                        val tObj = JSONObject().apply {
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
                        tracksArray.put(tObj)
                    }
                    put("tracks", tracksArray)
                }
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
