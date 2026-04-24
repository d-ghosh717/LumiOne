package com.lumione.player.ui

import androidx.lifecycle.*
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPct: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: QueueManager.RepeatMode = QueueManager.RepeatMode.NONE,
    val isPlayerReady: Boolean = false,
    val errorCode: Int? = null
)

class PlaybackViewModel : ViewModel(), PlaybackService.PlaybackServiceListener {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Progress formatted for display
    val formattedPosition: String
        get() = formatMs(_uiState.value.currentPositionMs)

    val formattedDuration: String
        get() = formatMs(_uiState.value.durationMs)

    val seekProgress: Int
        get() {
            val state = _uiState.value
            if (state.durationMs == 0L) return 0
            return ((state.currentPositionMs * 1000) / state.durationMs).toInt()
        }

    // ─── PlaybackService.PlaybackServiceListener ──────────────────────────────

    override fun onTrackChanged(track: Track?) {
        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            currentPositionMs = 0L,
            durationMs = track?.durationMs ?: 0L
        )
    }

    override fun onPlayStateChanged(playing: Boolean) {
        _uiState.value = _uiState.value.copy(isPlaying = playing)
    }

    override fun onProgressUpdate(currentMs: Long, durationMs: Long, bufferedPct: Int) {
        _uiState.value = _uiState.value.copy(
            currentPositionMs = currentMs,
            durationMs = durationMs,
            bufferedPct = bufferedPct
        )
    }

    override fun onPlayerReady() {
        _uiState.value = _uiState.value.copy(isPlayerReady = true)
    }

    override fun onError(code: Int) {
        _uiState.value = _uiState.value.copy(errorCode = code)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorCode = null)
    }

    fun syncShuffle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(shuffleEnabled = enabled)
    }

    fun syncRepeat(mode: QueueManager.RepeatMode) {
        _uiState.value = _uiState.value.copy(repeatMode = mode)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
