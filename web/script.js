/**
 * LumiOne — script.js
 * Clean, flat, stable. No overengineering.
 *
 * Architecture:
 *   Queue  → simple array + currentIndex
 *   Player → HTML5 Audio element streaming via backend yt-dlp
 *   Search → local backend /api/search (yt-dlp)
 *   UI     → direct DOM, no framework
 */

'use strict';

// ─── Config ───────────────────────────────────────────────────────────────────
const API_BASE = window.location.origin || 'http://localhost:3000';

// ─── State ────────────────────────────────────────────────────────────────────
let audioPlayer = null;   // HTML5 Audio element
let isPlaying   = false;
let isShuffle   = false;
let repeatMode  = 'none'; // 'none' | 'one' | 'all'

const queue     = [];     // Array of track objects
let curIndex    = -1;     // Current track index in queue

// ─── Track shape ──────────────────────────────────────────────────────────────
// { id, title, artist, thumbnail, duration, durationFormatted }

// ─── DOM refs ─────────────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);

const dom = {
  searchInput:    $('search-input'),
  btnSearch:      $('btn-search'),
  searchStatus:   $('search-status'),
  searchResults:  $('search-results'),
  queueHeader:    $('queue-header'),
  queueList:      $('queue-list'),
  btnClear:       $('btn-clear'),
  albumArt:       $('album-art'),
  artGlow:        document.querySelector('.art-glow'),
  trackTitle:     $('track-title'),
  trackArtist:    $('track-artist'),
  timeCur:        $('time-cur'),
  timeDur:        $('time-dur'),
  progressBar:    $('progress-bar'),
  progressFill:   $('progress-fill'),
  progressThumb:  $('progress-thumb'),
  btnShuffle:     $('btn-shuffle'),
  btnPrev:        $('btn-prev'),
  btnPlay:        $('btn-play'),
  btnNext:        $('btn-next'),
  btnRepeat:      $('btn-repeat'),
  iconPlay:       $('icon-play'),
  iconPause:      $('icon-pause'),
  iconLoad:       $('icon-load'),
  volSlider:      $('vol-slider'),
  toast:          $('toast'),
};

// ─── Mobile audio unlock ──────────────────────────────────────────────────────
// Mobile browsers block Audio.play() until a user gesture unlocks it.
// We create a silent play on first touch to unlock the audio context.
let audioUnlocked = false;
function unlockAudio() {
  if (audioUnlocked) return;
  if (audioPlayer) {
    // Create a short silent buffer and play it to unlock
    audioPlayer.src = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';
    const p = audioPlayer.play();
    if (p) p.then(() => { audioPlayer.pause(); audioPlayer.src = ''; }).catch(() => {});
  }
  audioUnlocked = true;
  document.removeEventListener('touchstart', unlockAudio);
  document.removeEventListener('touchend', unlockAudio);
  document.removeEventListener('click', unlockAudio);
  console.log('[LumiOne] Audio unlocked by user gesture');
}
document.addEventListener('touchstart', unlockAudio, { once: false });
document.addEventListener('touchend', unlockAudio, { once: false });
document.addEventListener('click', unlockAudio, { once: false });

// ─── Audio Player Setup ────────────────────────────────────────────────────────
function initAudioPlayer() {
  audioPlayer = new Audio();
  audioPlayer.preload = 'auto';
  audioPlayer.setAttribute('playsinline', '');  // iOS needs this
  audioPlayer.setAttribute('webkit-playsinline', '');
  audioPlayer.volume = parseInt(dom.volSlider.value, 10) / 100;

  audioPlayer.addEventListener('playing', () => {
    isPlaying = true;
    setPlayIcon('pause');
    if (window.AndroidApp) window.AndroidApp.updatePlaybackState(true);
  });

  audioPlayer.addEventListener('pause', () => {
    isPlaying = false;
    setPlayIcon('play');
    if (window.AndroidApp) window.AndroidApp.updatePlaybackState(false);
  });

  audioPlayer.addEventListener('waiting', () => {
    setPlayIcon('load');
  });

  audioPlayer.addEventListener('canplay', () => {
    if (isPlaying) setPlayIcon('pause');
    else setPlayIcon('play');
  });

  audioPlayer.addEventListener('ended', () => {
    isPlaying = false;
    handleTrackEnd();
  });

  audioPlayer.addEventListener('timeupdate', () => {
    updateProgress();
  });

  audioPlayer.addEventListener('error', (e) => {
    const track = queue[curIndex];
    const name = track ? track.title : 'unknown';
    console.error('[LumiOne] Audio error for:', name, audioPlayer.error);
    toast(`Playback failed — retrying…`, 'error');
    // Try fallback after a brief delay
    setTimeout(() => {
      if (curIndex >= 0 && queue[curIndex]) {
        retryOrSkip(queue[curIndex]);
      }
    }, 1500);
  });

  // Player is ready immediately — load trending
  loadTrending();
}

// Track retry count to avoid infinite loops
let retryCount = 0;
const MAX_RETRIES = 1;

function retryOrSkip(track) {
  if (retryCount < MAX_RETRIES) {
    retryCount++;
    // Try the pipe endpoint (forces server-side piping, no redirect)
    const pipeSrc = `${API_BASE}/api/pipe/${track.id}`;
    console.log('[LumiOne] Retrying with pipe endpoint:', pipeSrc);
    audioPlayer.src = pipeSrc;
    audioPlayer.play().catch(() => {
      toast(`Skipping — couldn't play "${track.title}"`, 'error');
      setTimeout(() => skipNext(), 1000);
    });
  } else {
    retryCount = 0;
    toast(`Skipping "${track.title}"`, 'error');
    setTimeout(() => skipNext(), 800);
  }
}

// ─── Playback controls ────────────────────────────────────────────────────────
function playTrack(track, fromQueue = false) {
  if (!track || !track.id) return;

  // Add to queue if not already playing from queue
  if (!fromQueue) {
    const insertAt = curIndex + 1;
    queue.splice(insertAt, 0, track);
    curIndex = insertAt;
    renderQueue();
  }

  retryCount = 0;
  setPlayIcon('load');

  // Use stream endpoint — proxied with Range support for seeking
  const streamUrl = `${API_BASE}/api/stream/${track.id}`;
  console.log('[LumiOne] Playing:', track.title, '→', streamUrl);

  // Stop current playback first
  audioPlayer.pause();
  audioPlayer.src = streamUrl;
  audioPlayer.load(); // Force load on mobile

  // Use a small delay on mobile to let the load start
  const playPromise = audioPlayer.play();
  if (playPromise) {
    playPromise.catch(err => {
      console.warn('[LumiOne] play() rejected:', err.message);
      // On mobile, retry once after a short delay
      setTimeout(() => {
        audioPlayer.play().catch(() => {
          toast('Tap play to start', 'error');
          setPlayIcon('play');
        });
      }, 300);
    });
  }

  updatePlayerUI(track);
  highlightPlaying(track.id);
}

function togglePlay() {
  if (curIndex < 0 || !queue[curIndex]) { toast('Pick a track to play'); return; }
  if (isPlaying) {
    audioPlayer.pause();
  } else {
    audioPlayer.play().catch(() => {});
  }
}

function skipNext() {
  if (queue.length === 0) return;

  let next;
  if (repeatMode === 'one') {
    next = curIndex;
  } else if (isShuffle) {
    const candidates = [...queue.keys()].filter(i => i !== curIndex);
    next = candidates.length ? candidates[Math.floor(Math.random() * candidates.length)] : curIndex;
  } else {
    next = curIndex + 1;
    if (next >= queue.length) {
      if (repeatMode === 'all') next = 0;
      else return; // end of queue
    }
  }

  curIndex = next;
  playTrack(queue[curIndex], true);
  renderQueue();
}

function skipPrev() {
  if (queue.length === 0) return;

  // If >3s in, restart track; else go previous
  const pos = audioPlayer.currentTime || 0;
  if (pos > 3) {
    audioPlayer.currentTime = 0;
    return;
  }

  const prev = curIndex - 1;
  if (prev < 0) {
    audioPlayer.currentTime = 0;
    return;
  }

  curIndex = prev;
  playTrack(queue[curIndex], true);
  renderQueue();
}

function handleTrackEnd() {
  skipNext();
}

function setVolume(v) {
  if (audioPlayer) audioPlayer.volume = v / 100;
}

// ─── Progress ─────────────────────────────────────────────────────────────────
let isDragging = false;

function updateProgress() {
  if (!audioPlayer || isDragging) return;
  const cur = audioPlayer.currentTime || 0;
  const dur = audioPlayer.duration || 0;
  const pct = dur > 0 ? (cur / dur) * 100 : 0;

  dom.progressFill.style.width  = pct + '%';
  dom.progressThumb.style.left  = pct + '%';
  dom.timeCur.textContent = fmtSec(cur);
  dom.timeDur.textContent = dur > 0 ? fmtSec(dur) : (queue[curIndex]?.durationFormatted || '0:00');
  dom.progressBar.setAttribute('aria-valuenow', Math.round(pct));
}

// ─── Seek and Drag ────────────────────────────────────────────────────────────
function getSeekPct(clientX) {
  const rect = dom.progressBar.getBoundingClientRect();
  return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
}

function updateProgressUIFromEvent(clientX) {
  if (!audioPlayer || !audioPlayer.duration) return;
  const pct = getSeekPct(clientX);
  const cur = pct * audioPlayer.duration;
  dom.progressFill.style.width  = (pct * 100) + '%';
  dom.progressThumb.style.left  = (pct * 100) + '%';
  dom.timeCur.textContent = fmtSec(cur);
}

function commitSeek(clientX) {
  if (!audioPlayer || !audioPlayer.duration) return;
  const pct = getSeekPct(clientX);
  audioPlayer.currentTime = pct * audioPlayer.duration;
  updateProgress();
}

// Mouse
dom.progressBar.addEventListener('mousedown', e => {
  isDragging = true;
  updateProgressUIFromEvent(e.clientX);
});
document.addEventListener('mousemove', e => {
  if (isDragging) updateProgressUIFromEvent(e.clientX);
});
document.addEventListener('mouseup', e => {
  if (isDragging) {
    isDragging = false;
    commitSeek(e.clientX);
  }
});

// Touch
dom.progressBar.addEventListener('touchstart', e => {
  isDragging = true;
  updateProgressUIFromEvent(e.touches[0].clientX);
}, { passive: true });
document.addEventListener('touchmove', e => {
  if (isDragging) updateProgressUIFromEvent(e.touches[0].clientX);
}, { passive: true });
document.addEventListener('touchend', e => {
  if (isDragging) {
    isDragging = false;
    commitSeek(e.changedTouches[0].clientX);
  }
});

// ─── Queue ────────────────────────────────────────────────────────────────────
function addToQueueEnd(track) {
  queue.push(track);
  renderQueue();
}

function clearQueue() {
  queue.length = 0;
  curIndex = -1;
  if (audioPlayer) {
    audioPlayer.pause();
    audioPlayer.src = '';
  }
  isPlaying = false;
  setPlayIcon('play');
  renderQueue();
  updatePlayerUI(null);
  dom.queueHeader.hidden = true;
}

function renderQueue() {
  dom.queueList.innerHTML = '';

  if (queue.length === 0) {
    dom.queueHeader.hidden = true;
    return;
  }

  dom.queueHeader.hidden = false;

  queue.forEach((track, i) => {
    const li = document.createElement('li');
    li.className = 'queue-item' + (i === curIndex ? ' active' : '');
    li.innerHTML = `
      <span class="queue-num">${i === curIndex ? '▶' : i + 1}</span>
      <span class="queue-title">${esc(track.title)}</span>
      <span class="queue-dur">${track.durationFormatted || ''}</span>
    `;
    li.addEventListener('click', () => {
      curIndex = i;
      playTrack(queue[i], true);
      renderQueue();
    });
    dom.queueList.appendChild(li);
  });
}

// ─── Search ───────────────────────────────────────────────────────────────────
async function doSearch(query) {
  if (!query.trim()) return;

  setStatus('Searching…');
  dom.searchResults.innerHTML = '';
  dom.btnSearch.disabled = true;

  try {
    const res  = await fetchWithTimeout(`${API_BASE}/api/search?q=${encodeURIComponent(query)}`, 20000);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    renderResults(data.results || []);
  } catch (err) {
    console.error('[Search]', err);
    setStatus('Search failed — is the backend running? (npm start in /backend)');
    renderPasteIdFallback();
  } finally {
    dom.btnSearch.disabled = false;
  }
}

function renderResults(results) {
  dom.searchResults.innerHTML = '';

  if (!results.length) {
    setStatus('No results found.');
    return;
  }

  setStatus('');

  results.forEach((r, i) => {
    const li   = document.createElement('li');
    li.className  = 'result-item';
    li.setAttribute('role', 'option');
    li.dataset.id = r.id;

    const thumb = r.thumbnail
      ? `<img class="result-thumb" src="${esc(r.thumbnail)}" alt="" loading="lazy" onerror="this.style.display='none'">`
      : `<div class="result-thumb-placeholder"></div>`;

    li.innerHTML = `
      ${thumb}
      <div class="result-meta">
        <div class="result-title">${esc(r.title)}</div>
        <div class="result-artist">${esc(r.artist || '')}</div>
      </div>
      <span class="result-dur">${r.durationFormatted || ''}</span>
    `;

    li.addEventListener('click', () => {
      // Load all current results into the queue so next/prev works
      queue.length = 0;
      results.forEach(res => {
        queue.push({
          id:                res.id,
          title:             res.title,
          artist:            res.artist || 'Unknown Artist',
          thumbnail:         res.thumbnail || '',
          duration:          res.duration || 0,
          durationFormatted: res.durationFormatted || '',
        });
      });
      curIndex = i;
      playTrack(queue[curIndex], true);
      renderQueue();
    });

    dom.searchResults.appendChild(li);
  });
}

function renderPasteIdFallback() {
  const li = document.createElement('li');
  li.innerHTML = `
    <div style="padding:12px 8px">
      <div style="font-size:12px;color:var(--text-muted);margin-bottom:8px">
        Or paste a YouTube video ID / URL:
      </div>
      <div style="display:flex;gap:8px">
        <input id="id-input" type="text" placeholder="dQw4w9WgXcQ or youtube.com/watch?v=…"
          style="flex:1;height:36px;background:var(--surface-hi);border:1px solid var(--outline);
                 border-radius:8px;color:var(--text);padding:0 10px;font-size:12px;outline:none">
        <button id="id-play" style="height:36px;padding:0 14px;border-radius:8px;
          background:var(--gradient);color:#fff;font-size:12px;font-weight:600;cursor:pointer">
          Play
        </button>
      </div>
    </div>
  `;
  dom.searchResults.appendChild(li);

  const idInput = li.querySelector('#id-input');
  const idPlay  = li.querySelector('#id-play');

  const playFromId = () => {
    const raw = idInput.value.trim();
    if (!raw) return;
    const videoId = extractVideoId(raw);
    if (!videoId) { toast('Invalid YouTube URL or ID', 'error'); return; }
    const track = { id: videoId, title: videoId, artist: 'YouTube', thumbnail: `https://i.ytimg.com/vi/${videoId}/mqdefault.jpg`, duration: 0, durationFormatted: '' };
    playTrack(track);
    idInput.value = '';
  };

  idPlay.addEventListener('click', playFromId);
  idInput.addEventListener('keydown', e => { if (e.key === 'Enter') playFromId(); });
}

// ─── Trending (auto-load on start) ────────────────────────────────────────────
async function loadTrending() {
  setStatus('Loading trending…');
  try {
    const res  = await fetchWithTimeout(`${API_BASE}/api/trending`, 20000);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    if (data.results?.length) {
      renderResults(data.results);
      setStatus('');
    } else {
      setStatus('Type to search →');
    }
  } catch {
    setStatus('Backend offline — start it with: cd backend && npm start');
  }
}

// ─── UI helpers ───────────────────────────────────────────────────────────────
function updatePlayerUI(track) {
  if (!track) {
    dom.trackTitle.textContent  = 'Select a track to play';
    dom.trackArtist.textContent = 'Search something above →';
    dom.albumArt.innerHTML = `
      <svg width="60" height="60" viewBox="0 0 24 24" fill="none" opacity="0.3">
        <path d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" fill="#ba9eff"/>
      </svg>`;
    dom.albumArt.className = 'album-art';
    dom.artGlow.style.opacity = '0';
    dom.timeCur.textContent = '0:00';
    dom.timeDur.textContent = '0:00';
    dom.progressFill.style.width = '0%';
    dom.progressThumb.style.left = '0%';
    return;
  }

  dom.trackTitle.textContent  = track.title;
  dom.trackArtist.textContent = track.artist || 'Unknown Artist';
  dom.timeDur.textContent     = track.durationFormatted || '0:00';
  dom.artGlow.style.opacity   = '1';

  // Album art
  if (track.thumbnail) {
    dom.albumArt.innerHTML = `<img src="${esc(track.thumbnail)}" alt="${esc(track.title)}" loading="lazy">`;
    dom.albumArt.className = 'album-art has-art';
  } else {
    dom.albumArt.innerHTML = `
      <svg width="60" height="60" viewBox="0 0 24 24" fill="none" opacity="0.3">
        <path d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" fill="#ba9eff"/>
      </svg>`;
    dom.albumArt.className = 'album-art';
  }

  document.title = `${track.title} — LumiOne`;

  // Native Android App integration for notification panel
  if (window.AndroidApp) {
    window.AndroidApp.updateMetadata(track.title, track.artist || 'Unknown Artist', track.thumbnail || '');
  }

  // Web MediaSession API (for Chrome/Desktop)
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title,
      artist: track.artist || 'Unknown Artist',
      artwork: [
        { src: track.thumbnail || 'https://i.imgur.com/5X8Xj3z.png', sizes: '512x512', type: 'image/png' }
      ]
    });
    navigator.mediaSession.setActionHandler('play', () => { if (audioPlayer) audioPlayer.play(); });
    navigator.mediaSession.setActionHandler('pause', () => { if (audioPlayer) audioPlayer.pause(); });
    navigator.mediaSession.setActionHandler('previoustrack', () => skipPrev());
    navigator.mediaSession.setActionHandler('nexttrack', () => skipNext());
  }
}

function highlightPlaying(videoId) {
  document.querySelectorAll('.result-item').forEach(el => {
    el.classList.toggle('playing', el.dataset.id === videoId);
  });
}

function setPlayIcon(state) {
  dom.iconPlay.style.display  = state === 'play'  ? '' : 'none';
  dom.iconPause.style.display = state === 'pause' ? '' : 'none';
  dom.iconLoad.style.display  = state === 'load'  ? '' : 'none';
}

function setStatus(msg) {
  dom.searchStatus.textContent = msg;
  dom.searchStatus.hidden      = !msg;
}

let toastTimer = null;
function toast(msg, type = '') {
  dom.toast.textContent  = msg;
  dom.toast.className    = `toast show${type ? ' ' + type : ''}`;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { dom.toast.className = 'toast'; }, 3000);
}

// ─── Utilities ────────────────────────────────────────────────────────────────
function fmtSec(s) {
  s = Math.floor(s || 0);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function esc(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function extractVideoId(input) {
  if (/^[\w-]{11}$/.test(input)) return input;
  try {
    const url = new URL(input);
    return url.searchParams.get('v')
      || url.pathname.replace(/^\//, '').split('/').pop()
      || null;
  } catch {
    return null;
  }
}

async function fetchWithTimeout(url, ms = 10000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(url, { signal: ctrl.signal });
  } finally {
    clearTimeout(timer);
  }
}

// ─── Event listeners ─────────────────────────────────────────────────────────
dom.btnSearch.addEventListener('click', () => doSearch(dom.searchInput.value));
dom.searchInput.addEventListener('keydown', e => {
  if (e.key === 'Enter') doSearch(dom.searchInput.value);
});

dom.btnPlay.addEventListener('click', togglePlay);
dom.btnNext.addEventListener('click', skipNext);
dom.btnPrev.addEventListener('click', skipPrev);
dom.btnClear.addEventListener('click', clearQueue);

dom.volSlider.addEventListener('input', e => setVolume(parseInt(e.target.value, 10)));

dom.btnShuffle.addEventListener('click', () => {
  isShuffle = !isShuffle;
  dom.btnShuffle.setAttribute('aria-pressed', isShuffle);
  toast(isShuffle ? 'Shuffle on' : 'Shuffle off');
});

dom.btnRepeat.addEventListener('click', () => {
  const modes = ['none', 'all', 'one'];
  repeatMode = modes[(modes.indexOf(repeatMode) + 1) % modes.length];
  dom.btnRepeat.setAttribute('aria-pressed', repeatMode !== 'none');
  const labels = { none: 'Repeat off', all: 'Repeat all', one: 'Repeat one' };
  toast(labels[repeatMode]);
});

// Keyboard shortcuts
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  switch (e.key) {
    case ' ':        e.preventDefault(); togglePlay();  break;
    case 'ArrowRight': skipNext();                       break;
    case 'ArrowLeft':  skipPrev();                       break;
  }
});

// ─── Init ─────────────────────────────────────────────────────────────────────
setPlayIcon('play');
initAudioPlayer();
