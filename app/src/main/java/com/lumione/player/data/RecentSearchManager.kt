package com.lumione.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

data class RecentSearch(val query: String, val updatedAt: Long)

class RecentSearchManager(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY_RECENTS = "recent_searches"
        private const val MAX_RECENTS = 5
        fun forUser(context: Context, uid: String): RecentSearchManager {
            val safeUid = uid.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return RecentSearchManager(context.applicationContext.getSharedPreferences("lumione_recent_searches_$safeUid", Context.MODE_PRIVATE))
        }
    }

    @Synchronized fun getRecent(): List<RecentSearch> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                normalize(item.optString("query")).takeIf { it.isNotEmpty() }?.let {
                    RecentSearch(it, item.optLong("updatedAt"))
                }
            }.distinctBy { it.query.lowercase() }.sortedByDescending { it.updatedAt }.take(MAX_RECENTS)
        }.getOrDefault(emptyList())
    }

    @Synchronized fun record(query: String, now: Long = System.currentTimeMillis()) {
        val clean = normalize(query).take(200)
        if (clean.isEmpty()) return
        val records = (listOf(RecentSearch(clean, now)) + getRecent().filterNot { it.query.equals(clean, true) })
            .take(MAX_RECENTS)
        save(records)
    }

    @Synchronized fun merge(incoming: List<RecentSearch>) {
        val merged = (getRecent() + incoming).groupBy { it.query.lowercase() }
            .map { (_, entries) -> entries.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
            .take(MAX_RECENTS)
        save(merged)
    }

    @Synchronized fun clear() { prefs.edit().remove(KEY_RECENTS).apply() }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun save(items: List<RecentSearch>) {
        val json = JSONArray()
        items.forEach { json.put(org.json.JSONObject().put("query", it.query).put("updatedAt", it.updatedAt)) }
        prefs.edit().putString(KEY_RECENTS, json.toString()).apply()
    }
}
