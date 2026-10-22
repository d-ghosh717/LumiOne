package com.lumione.player.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.lumione.player.queue.Track
import kotlinx.coroutines.tasks.await

/** UID-scoped Firestore sync. SharedPreferences remain the immediate, durable local cache. */
class AccountLibrarySync(context: Context, private val uid: String) {
    private val firestore = FirebaseFirestore.getInstance()
    private val root = firestore.collection("users").document(uid)
    private val migrationPrefs = context.applicationContext.getSharedPreferences("lumione_account_migrations", Context.MODE_PRIVATE)

    private fun identity(track: Track): String = track.provider + ":" + track.providerId.ifBlank { track.videoId }
    private fun docId(value: String): String = android.util.Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
    )

    suspend fun syncInitial(
        favorites: FavoritesManager,
        history: HistoryManager,
        playlists: PlaylistManager,
        recents: RecentSearchManager
    ) {
        val migrationTarget = migrationPrefs.getString("legacy_migrated_uid", null)
        val legacyClaim = migrationPrefs.getString("legacy_claimed_uid", null)
        if (legacyClaim == null) migrationPrefs.edit().putString("legacy_claimed_uid", uid).commit()
        if (migrationTarget == null && (legacyClaim == null || legacyClaim == uid)) {
            favorites.mergeFavorites(FavoritesManager.getInstance(appContext).getFavorites())
            history.mergeEntries(HistoryManager.getInstance(appContext).getHistory())
            playlists.mergePlaylists(PlaylistManager.getInstance(appContext).getPlaylists())
        }
        val cloudFavorites = root.collection("favorites").get().await().documents.mapNotNull { it.toTrack() }
        val cloudHistory = root.collection("history").orderBy("timestamp", Query.Direction.DESCENDING).limit(100)
            .get().await().documents.mapNotNull { it.toHistoryEntry() }
        val cloudRecents = root.collection("recentSearches").orderBy("updatedAt", Query.Direction.DESCENDING).limit(5)
            .get().await().documents.mapNotNull { doc ->
                doc.getString("query")?.let { RecentSearch(it, doc.getLong("updatedAt") ?: 0L) }
            }
        val cloudPlaylists = root.collection("playlists").get().await().documents.mapNotNull { it.toPlaylist() }

        favorites.mergeFavorites(cloudFavorites)
        history.mergeEntries(cloudHistory)
        recents.merge(cloudRecents)
        playlists.mergePlaylists(cloudPlaylists)

        favorites.getFavorites().forEach { putFavorite(it) }
        history.getHistory().forEach { putHistory(it) }
        syncRecentSearches(recents.getRecent())
        playlists.getPlaylists().forEach { putPlaylist(it) }
        pruneHistory(history.getHistory())
        if (migrationTarget == null) migrationPrefs.edit().putString("legacy_migrated_uid", uid).apply()
    }

    suspend fun putFavorite(track: Track) {
        root.collection("favorites").document(docId(identity(track))).set(track.toCloudMap()).await()
    }

    suspend fun removeFavorite(track: Track) {
        root.collection("favorites").document(docId(identity(track))).delete().await()
    }

    suspend fun putHistory(entry: HistoryEntry) {
        root.collection("history").document(docId(identity(entry.track))).set(entry.toCloudMap()).await()
    }

    suspend fun pruneHistoryTo(entries: List<HistoryEntry>) = pruneHistory(entries)

    suspend fun removeHistory(track: Track) {
        root.collection("history").document(docId(identity(track))).delete().await()
    }

    suspend fun clearFavorites(tracks: List<Track> = emptyList()) {
        if (tracks.isEmpty()) clearCollection("favorites") else tracks.forEach { removeFavorite(it) }
    }
    suspend fun clearHistory(tracks: List<Track> = emptyList()) {
        if (tracks.isEmpty()) clearCollection("history") else tracks.forEach { removeHistory(it) }
    }

    suspend fun putRecent(search: RecentSearch) {
        root.collection("recentSearches").document(docId(search.query.trim().lowercase()))
            .set(mapOf("query" to search.query, "updatedAt" to search.updatedAt)).await()
    }

    suspend fun clearRecentSearches(items: List<RecentSearch> = emptyList()) {
        val collection = root.collection("recentSearches")
        items.forEach { collection.document(docId(it.query.trim().lowercase())).delete().await() }
        if (items.isEmpty()) syncRecentSearches(emptyList())
    }

    suspend fun syncRecentSearches(items: List<RecentSearch>) {
        val collection = root.collection("recentSearches")
        val keep = items.take(5).associateBy { docId(it.query.trim().lowercase()) }
        keep.values.forEach { item ->
            collection.document(docId(item.query.trim().lowercase()))
                .set(mapOf("query" to item.query, "updatedAt" to item.updatedAt)).await()
        }
        collection.get().await().documents.filterNot { it.id in keep.keys }.forEach { it.reference.delete().await() }
    }

    suspend fun putPlaylist(playlist: Playlist) {
        root.collection("playlists").document(docId(playlist.id)).set(playlist.toCloudMap()).await()
    }

    suspend fun removePlaylist(playlistId: String) {
        root.collection("playlists").document(docId(playlistId)).delete().await()
    }

    private suspend fun clearCollection(name: String) {
        val docs = root.collection(name).get().await().documents
        docs.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private suspend fun pruneHistory(retained: List<HistoryEntry>?) {
        val collection = root.collection("history")
        val cutoff = retained?.minOfOrNull { it.timestamp }
        if (cutoff == null) return
        while (true) {
            val old = collection.whereLessThan("timestamp", cutoff).orderBy("timestamp", Query.Direction.ASCENDING).limit(400).get().await().documents
            if (old.isEmpty()) return
            val batch = firestore.batch()
            old.forEach { batch.delete(it.reference) }
            batch.commit().await()
            if (old.size < 400) return
        }
    }

    private fun Track.toCloudMap(): Map<String, Any> = mapOf(
        "videoId" to videoId, "provider" to provider, "providerId" to providerId,
        "youtubeVideoId" to youtubeVideoId, "title" to title, "artist" to artist,
        "durationMs" to durationMs, "thumbnailUrl" to thumbnailUrl
    )

    private fun HistoryEntry.toCloudMap(): Map<String, Any> = track.toCloudMap() + mapOf(
        "timestamp" to timestamp, "playCount" to playCount,
        "completionCount" to completionCount, "skipCount" to skipCount
    )

    private fun Playlist.toCloudMap(): Map<String, Any> = mapOf(
        "id" to id, "name" to name, "description" to description,
        "createdAt" to createdAt, "updatedAt" to updatedAt,
        "tracks" to tracks.map { it.toCloudMap() }
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toTrack(): Track? {
        val id = getString("videoId") ?: return null
        return Track(
            videoId = id, title = getString("title") ?: "Unknown",
            artist = getString("artist") ?: "Unknown Artist", durationMs = getLong("durationMs") ?: 0L,
            thumbnailUrl = getString("thumbnailUrl") ?: "", provider = getString("provider") ?: "lumione",
            providerId = getString("providerId") ?: id, youtubeVideoId = getString("youtubeVideoId") ?: id
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toHistoryEntry(): HistoryEntry? {
        val track = toTrack() ?: return null
        return HistoryEntry(track, getLong("timestamp") ?: 0L, getLong("playCount")?.toInt() ?: 1,
            getLong("completionCount")?.toInt() ?: 0, getLong("skipCount")?.toInt() ?: 0)
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toPlaylist(): Playlist? {
        val id = getString("id") ?: return null
        val tracks = (get("tracks") as? List<*>)?.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val videoId = map["videoId"] as? String ?: return@mapNotNull null
            Track(videoId, map["title"] as? String ?: "Unknown", map["artist"] as? String ?: "Unknown Artist",
                (map["durationMs"] as? Number)?.toLong() ?: 0L, map["thumbnailUrl"] as? String ?: "",
                provider = map["provider"] as? String ?: "lumione", providerId = map["providerId"] as? String ?: videoId,
                youtubeVideoId = map["youtubeVideoId"] as? String ?: videoId)
        }.orEmpty()
        return Playlist(id, getString("name") ?: "Untitled Playlist", getString("description") ?: "",
            getLong("createdAt") ?: 0L, getLong("updatedAt") ?: 0L, tracks)
    }

    private val appContext = context.applicationContext
}
