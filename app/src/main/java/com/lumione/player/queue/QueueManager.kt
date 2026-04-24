package com.lumione.player.queue

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val thumbnailUrl: String = ""
)

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
        _queue.value = tracks
        _currentIndex.value = startIndex.coerceIn(0, tracks.lastIndex)
        history.clear()
    }

    fun addToQueue(track: Track) {
        _queue.value = (_queue.value ?: emptyList()) + track
    }

    fun insertNext(track: Track) {
        val current = _queue.value?.toMutableList() ?: mutableListOf()
        val insertAt = (_currentIndex.value ?: -1) + 1
        current.add(insertAt.coerceIn(0, current.size), track)
        _queue.value = current
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
                candidates.randomOrNull()
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
