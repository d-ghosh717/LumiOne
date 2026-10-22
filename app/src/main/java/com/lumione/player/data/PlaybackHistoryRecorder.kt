package com.lumione.player.data

import com.lumione.player.queue.Track

/**
 * Ensures every track played is recorded in Listening History when playback genuinely starts.
 *
 * Deduplication rules:
 * - Redundant player callbacks while the same track is playing do not duplicate history.
 * - Pausing and resuming playback does not duplicate history.
 * - Track transitions (from queue, search, playlists, recommendations, or auto-next) trigger history recording.
 * - Repeated plays and replays of the same song (via new playback session) are recorded sensibly.
 */
class PlaybackHistoryRecorder {
    private var recordedTrackId: String? = null
    private var recordedSessionId: Long = 0L
    private var currentSessionId: Long = 1L

    /**
     * Resets or increments the playback session.
     * Called when a track is explicitly loaded, queued to play, skipped, changed, or completed.
     */
    @Synchronized
    fun resetForNewTrack(trackId: String? = null) {
        currentSessionId++
        if (trackId != null && trackId != recordedTrackId) {
            recordedTrackId = null
        }
    }

    /**
     * Evaluates playback state and records playback start.
     * Returns true if playback genuinely started and should be recorded in Listening History.
     */
    @Synchronized
    fun onPlaybackUpdate(track: Track?, isPlaying: Boolean): Boolean {
        if (!isPlaying || track == null || track.videoId.isBlank()) {
            return false
        }

        // Avoid duplicate entry if this exact track was already recorded for this playback session
        if (track.videoId == recordedTrackId && currentSessionId == recordedSessionId) {
            return false
        }

        recordedTrackId = track.videoId
        recordedSessionId = currentSessionId
        return true
    }

    @Synchronized
    fun currentRecordedTrackId(): String? = recordedTrackId

    @Synchronized
    fun clear() {
        recordedTrackId = null
        recordedSessionId = 0L
        currentSessionId = 1L
    }
}

