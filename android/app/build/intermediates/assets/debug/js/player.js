/* ═══════════════════════════════════════════════════════════════
   LUMIONE — Pure Native ExoPlayer Engine (JS Wrapper)
   ═══════════════════════════════════════════════════════════════ */

const Player = (() => {
  // Shared state
  let queue = [];
  let currentIndex = -1;
  let isPlaying = false;
  let shuffleMode = false;
  let repeatMode = 0; // 0=off, 1=all, 2=one
  let progressMs = 0;
  let durationMs = 0;
  let history = [];

  // Callbacks
  let onStateChange = null;
  let onProgressUpdate = null;
  let onTrackChange = null;
  let onQueueChange = null;
  let onError = null;

  // ── Native Bridge Hooks ─────────────────────────────────────
  // These are called exclusively by MainActivity.kt

  window.Player = window.Player || {}; // Expose globally

  window.Player.onExoStateChanged = function(playing) {
    isPlaying = playing;
    if (window.Player.onStateChange) window.Player.onStateChange(isPlaying);
    console.log('[Exo] Play state:', isPlaying);
  };

  window.Player.onExoTrackEnded = function() {
    handleTrackEnd();
  };

  window.Player.onExoProgress = function(posSec, durSec) {
    progressMs = posSec * 1000;
    durationMs = durSec * 1000;
    if (window.Player.onProgressUpdate) window.Player.onProgressUpdate(posSec, durSec);
  };

  window.Player.onExoError = function(message) {
    console.error('[Exo] Playback error:', message);
    if (window.Player.onError) window.Player.onError(message);
    // Auto-skip to next track after a short delay so the user isn't stuck
    setTimeout(function() {
      if (queue.length > 0 && currentIndex < queue.length - 1) {
        console.log('[Exo] Auto-skipping to next track after error');
        playNext();
      }
    }, 1500);
  };

  // ── Core Playback Methods ────────────────────────────────────

  function togglePlay() {
    if (currentIndex < 0 || currentIndex >= queue.length) return;
    if (isPlaying) {
      pause();
    } else {
      play();
    }
  }

  function play(track) {
    if (track) {
      // If song is clicked directly
      addToQueue(track, false);
      playTrackAt(queue.length - 1);
    } else {
      if (window.NativeMedia && window.NativeMedia.exoControl) {
        window.NativeMedia.exoControl('PLAY', 0);
      }
    }
  }

  function pause() {
    if (window.NativeMedia && window.NativeMedia.exoControl) {
      window.NativeMedia.exoControl('PAUSE', 0);
    }
  }

  function playTrackAt(index) {
    if (index < 0 || index >= queue.length) return;
    
    // Stop old track if needed (handled natively mostly)

    // Log internally for Smart Queue History (handled by UI bind)
    let track = queue[index];

    currentIndex = index;
    progressMs = 0;
    durationMs = track.duration * 1000;

    if (window.Player.onTrackChange) window.Player.onTrackChange(track, currentIndex);
    if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);

    if (window.NativeMedia && window.NativeMedia.playViaExo) {
       window.NativeMedia.playViaExo(track.id, track.title, track.artist || '', track.thumbnail || '');
    } else {
       console.error('[Exo] NativeBridge missing. Cannot play.');
       if (window.Player.onError) window.Player.onError("Native ExoPlayer missing");
    }
  }

  function handleTrackEnd() {
    // Notify SmartQueue via shared event that the song is fully done
    if (window.SmartQueue && window.SmartQueue.onTrackListenedFully) {
        window.SmartQueue.onTrackListenedFully(queue[currentIndex]);
    }

    if (repeatMode === 2) {
      playTrackAt(currentIndex); // Loop one
    } else {
      playNext();
    }
  }

  function playNext() {
    if (queue.length === 0) return;
    
    // Check if we need Auto-Fill
    if (window.SmartQueue && currentIndex >= queue.length - 2) {
       window.SmartQueue.autoFillQueue(queue[currentIndex], queue, currentIndex).then(added => {
          if (added && added.length > 0) {
              queue.push(...added);
              if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
          }
       });
    }

    let nextIdx = currentIndex + 1;
    if (nextIdx >= queue.length) {
      if (repeatMode === 1) {
        nextIdx = 0;
      } else {
        pause();
        return;
      }
    }
    
    // Tell SmartQueue about a skip if we skip halfway
    if (window.SmartQueue && durationMs > 0 && progressMs < durationMs * 0.5) {
       window.SmartQueue.onTrackSkipped(queue[currentIndex]);
    }

    playTrackAt(nextIdx);
  }

  function playPrevious() {
    if (progressMs > 3000) {
      seekTo(0);
      return;
    }
    if (queue.length === 0) return;
    let prevIdx = currentIndex - 1;
    if (prevIdx < 0) {
      if (repeatMode === 1) {
        prevIdx = queue.length - 1;
      } else {
        seekTo(0);
        return;
      }
    }
    playTrackAt(prevIdx);
  }

  function seekTo(seconds) {
    if (window.NativeMedia && window.NativeMedia.exoControl) {
      window.NativeMedia.exoControl('SEEK', Math.floor(seconds * 1000));
    }
    progressMs = seconds * 1000;
  }

  function addToQueue(track, autoPlay = true) {
    queue.push(track);
    if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
    if (autoPlay && queue.length === 1) {
      playTrackAt(0);
    }
  }

  function addMultipleToQueue(tracks) {
    if (!tracks || !tracks.length) return;
    queue.push(...tracks);
    if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
  }

  function insertNext(track) {
    if (currentIndex < 0) {
      addToQueue(track, true);
    } else {
      queue.splice(currentIndex + 1, 0, track);
      if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
    }
  }

  function removeFromQueue(index) {
    if (index < 0 || index >= queue.length) return;
    if (index === currentIndex) {
      playNext();
    } else if (index < currentIndex) {
      currentIndex--;
    }
    queue.splice(index, 1);
    if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
  }

  function clearQueue() {
    queue = [];
    currentIndex = -1;
    if (window.SmartQueue) window.SmartQueue.clearSession();
    if (window.Player.onQueueChange) window.Player.onQueueChange(queue, currentIndex);
  }

  // Setters/Getters
  function toggleShuffle() {
    shuffleMode = !shuffleMode;
    // Basic array scramble not fully needed if SmartQueue appends 
    return shuffleMode;
  }
  
  function toggleRepeat() {
    repeatMode = (repeatMode + 1) % 3;
    return repeatMode;
  }

  function getCurrentTrack() { return queue[currentIndex] || null; }
  function isCurrentlyPlaying() { return isPlaying; }
  function getCurrentTime() { return progressMs / 1000; }
  function getDuration() { return durationMs / 1000; }
  function getNextTrack() { return queue[currentIndex + 1] || null; }
  function getCurrentIndex() { return currentIndex; }
  function getIsPlaying() { return isPlaying; }
  function getShuffleMode() { return shuffleMode; }
  function getRepeatMode() { return repeatMode; }

  function seekPercent(percent) {
    if (durationMs > 0) {
      seekTo(percent * durationMs / 1000);
    }
  }

  function playFromQueue(index) {
    playTrackAt(index);
  }

  function formatTime(seconds) {
    if (!seconds || isNaN(seconds) || seconds < 0) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }

  // Expose API
  return {
    togglePlay, play, pause, playNext, playPrevious, seekTo,
    addToQueue, addMultipleToQueue, insertNext, removeFromQueue, clearQueue,
    toggleShuffle, toggleRepeat, playTrackAt,
    getCurrentTrack, isPlaying: isCurrentlyPlaying, 
    getCurrentTime, getDuration, getQueue: () => queue,
    getHistory: () => history,
    getNextTrack, getCurrentIndex, getIsPlaying, getShuffleMode, getRepeatMode,
    seekPercent, playFromQueue, formatTime,
    
    // Lifecycle setup (no longer needed for IFrame but maintained for signatures)
    onAppBackgrounded: () => { console.log('Backgrounded'); },
    onAppForegrounded: () => { console.log('Foregrounded'); },
    
    // Binding
    setOnStateChange: function(cb) { onStateChange = cb; window.Player.onStateChange = cb; },
    setOnProgressUpdate: function(cb) { onProgressUpdate = cb; window.Player.onProgressUpdate = cb; },
    setOnTrackChange: function(cb) { onTrackChange = cb; window.Player.onTrackChange = cb; },
    setOnQueueChange: function(cb) { onQueueChange = cb; window.Player.onQueueChange = cb; },
    setOnError: function(cb) { onError = cb; },
    
    // Re-expose bridge-hooks on the returned object just in case UI calls them
    onExoStateChanged: window.Player.onExoStateChanged,
    onExoTrackEnded: window.Player.onExoTrackEnded,
    onExoProgress: window.Player.onExoProgress,
    onExoError: window.Player.onExoError
  };
})();

// Re-expose so window references match immediately
Object.assign(window.Player, Player);
