package com.lumione.player.data

import com.lumione.player.queue.Track

/** Returns true only for a track transition while Media3 reports active playback. */
class PlaybackHistoryRecorder {
    private var activeTrackId: String? = null

    fun onPlaybackUpdate(track: Track?, isPlaying: Boolean): Boolean {
        if (!isPlaying || track == null) {
            if (!isPlaying) activeTrackId = null
            return false
        }
        if (activeTrackId == track.videoId) return false
        activeTrackId = track.videoId
        return true
    }
}
