package com.lumione.player.queue

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val thumbnailUrl: String = "",
    val streamUrl: String = "",
    val provider: String = "lumione",
    val providerId: String = videoId,
    val youtubeVideoId: String = videoId
) {
    val trackId: String get() = videoId

    fun getEffectiveStreamUrl(): String {
        return streamUrl
    }
}

enum class RepeatMode { NONE, ONE, ALL }

class QueueManager {

    private val _queue = MutableLiveData<List<Track>>(emptyList())
    val queue: LiveData<List<Track>> = _queue

    private val _currentIndex = MutableLiveData(-1)
    val currentIndex: LiveData<Int> = _currentIndex

    private val history = ArrayDeque<Int>()
    var shuffleEnabled = false
    var repeatMode = RepeatMode.NONE

    // ─── Queue Manipulation ───────────────────────────────────────────────────

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            _queue.value = emptyList()
            _currentIndex.value = -1
            history.clear()
            return
        }
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val activeTrack = tracks[safeIndex]

        val seenIds = mutableSetOf<String>()
        val sanitized = mutableListOf<Track>()

        seenIds.add(activeTrack.videoId)

        // Add unique tracks before safeIndex
        for (i in 0 until safeIndex) {
            val t = tracks[i]
            if (!seenIds.contains(t.videoId)) {
                seenIds.add(t.videoId)
                sanitized.add(t)
            }
        }

        val newCurrentIndex = sanitized.size
        sanitized.add(activeTrack)

        // Add unique tracks after safeIndex
        for (i in (safeIndex + 1) until tracks.size) {
            val t = tracks[i]
            if (!seenIds.contains(t.videoId)) {
                seenIds.add(t.videoId)
                sanitized.add(t)
            }
        }

        _queue.value = sanitized
        _currentIndex.value = newCurrentIndex
        history.clear()
    }

    fun addToQueue(track: Track) {
        val current = _queue.value ?: emptyList()
        if (current.any { it.videoId == track.videoId }) return
        _queue.value = current + track
    }

    fun insertNext(track: Track) {
        val current = _queue.value?.toMutableList() ?: mutableListOf()
        val ci = _currentIndex.value ?: -1
        if (current.getOrNull(ci + 1)?.videoId == track.videoId) return
        val insertAt = ci + 1
        current.add(insertAt.coerceIn(0, current.size), track)
        _queue.value = current
    }

    fun updateCurrentTrack(track: Track) {
        val ci = _currentIndex.value ?: return
        val current = _queue.value?.toMutableList() ?: return
        if (ci in current.indices) {
            current[ci] = track
            _queue.value = current
        }
    }

    fun removeAt(index: Int) {
        val current = _queue.value?.toMutableList() ?: return
        if (index !in current.indices) return
        current.removeAt(index)
        _queue.value = current
        val ci = _currentIndex.value ?: 0
        if (index < ci) _currentIndex.value = ci - 1
    }

    fun moveTrack(from: Int, to: Int) {
        val current = _queue.value?.toMutableList() ?: return
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from)
        current.add(to, item)
        _queue.value = current
        val ci = _currentIndex.value ?: return
        _currentIndex.value = when (ci) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) ci - 1 else ci + 1
            else -> ci
        }
    }

    fun clearQueue() {
        _queue.value = emptyList()
        _currentIndex.value = -1
        history.clear()
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun currentTrack(): Track? {
        val idx = _currentIndex.value ?: return null
        return _queue.value?.getOrNull(idx)
    }

    fun nextTrack(): Track? {
        val next = resolveNext() ?: return null
        history.addLast(_currentIndex.value ?: 0)
        _currentIndex.value = next
        return _queue.value?.getOrNull(next)
    }

    fun previousTrack(): Track? {
        if (history.isNotEmpty()) {
            val prev = history.removeLast()
            _currentIndex.value = prev
            return _queue.value?.getOrNull(prev)
        }
        val queue = _queue.value ?: return null
        val ci = _currentIndex.value ?: return null
        val prev = if (ci > 0) ci - 1 else if (repeatMode == RepeatMode.ALL) queue.lastIndex else return null
        _currentIndex.value = prev
        return queue.getOrNull(prev)
    }

    fun peekNext(): Track? {
        val next = resolveNext() ?: return null
        return _queue.value?.getOrNull(next)
    }

    fun hasNext(): Boolean = resolveNext() != null

    private fun resolveNext(): Int? {
        val queue = _queue.value ?: return null
        if (queue.isEmpty()) return null
        val ci = _currentIndex.value ?: return null

        return when {
            repeatMode == RepeatMode.ONE -> ci
            shuffleEnabled -> {
                val candidates = queue.indices.filter { it != ci }
                candidates.randomOrNull() ?: ci
            }
            ci < queue.lastIndex -> ci + 1
            repeatMode == RepeatMode.ALL -> 0
            else -> null
        }
    }

    fun jumpTo(index: Int): Track? {
        val queue = _queue.value ?: return null
        if (index !in queue.indices) return null
        history.addLast(_currentIndex.value ?: 0)
        _currentIndex.value = index
        return queue[index]
    }
}
