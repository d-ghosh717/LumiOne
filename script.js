'use strict';

const API_BASE = window.location.origin;
const firebaseConfig = window.LUMIONE_FIREBASE_CONFIG;
const $ = (id) => document.getElementById(id);
const ui = {
  authState: $('auth-state'),
  signIn: $('sign-in'), signOut: $('sign-out'), headerSignIn: $('header-sign-in'),
  headerAccount: $('header-account'), headerSignOut: $('header-sign-out'),
  homeSignIn: $('home-sign-in'), sessionGate: $('session-gate'),
  sessionGateMessage: $('session-gate-message'), authFeedback: $('auth-feedback'),
  search: $('search-input'),
  searchButton: $('search-button'), discoveryStatus: $('discovery-status'),
  results: $('results-list'), queue: $('queue-list'), queuePreview: $('queue-preview-list'), queueEmpty: $('queue-empty'),
  fullQueueEmpty: $('full-queue-empty'), fullQueueCount: $('full-queue-count'),
  queueCount: $('queue-count'), recommendations: $('recommendation-list'),
  recommendationEmpty: $('recommendation-empty'), title: $('track-title'), artist: $('track-artist'),
  note: $('player-note'), placeholder: $('player-placeholder'), seek: $('seek'),
  timeCurrent: $('time-current'), timeDuration: $('time-duration'), play: $('play-pause'),
  previous: $('previous'), next: $('next'), shuffle: $('shuffle'), repeat: $('repeat'),
  playerView: $('player-view'), searchView: $('search-view'), queueView: $('queue-view'),
  libraryView: $('library-view'), settings: $('settings'),
  libraryStatus: $('library-status'), historyList: $('history-list'), likedList: $('liked-list'),
  playlistList: $('playlist-list'), playlistTracks: $('playlist-tracks'), playlistDetail: $('playlist-detail'),
  playlistTitle: $('playlist-title'),
  settingsService: $('settings-service'), defaultShuffle: $('default-shuffle'),
  defaultRepeat: $('default-repeat'), preferencesFeedback: $('preferences-feedback')
};

let auth = null;
let firestore = null;
let signedInUser = null;
let youtube = null;
let youtubeApiReady = false;
let youtubeReady = false;
let queue = [];
let currentIndex = -1;
const queueMode = 'radio';
const PREFERENCES_KEY = 'lumione-player-preferences';
const preferences = loadPreferences();
let shuffleEnabled = preferences.shuffle;
let repeatMode = preferences.repeat;
let seekInProgress = false;
let recommendationRequest = 0;
let recordedHistoryId = '';
let recommendationLoading = false;
let trackEnded = false;
let currentView = 'home';
let intendedView = 'home';
let libraryData = { history: [], liked: [], playlists: [] };
let selectedLibraryTab = 'history';
let pendingPlaybackSelection = null;
let sidebarCollapsed = false;
const SIDEBAR_KEY = 'lumione-sidebar-collapsed';

function initializeAuth() {
  const requiredSettings = ['apiKey', 'authDomain', 'projectId', 'appId'];
  const missingConfig = !firebaseConfig || requiredSettings.some((key) => {
    const value = firebaseConfig[key];
    return typeof value !== 'string' || !value.trim() || /^YOUR_/i.test(value.trim());
  });
  if (missingConfig) {
    updateSessionUi(null);
    const message = 'Firebase Web configuration is missing. Add the public web app settings to firebase-config.js.';
    ui.authState.textContent = message;
    ui.authFeedback.textContent = 'See firebase-config.example.js for the required fields.';
    ui.discoveryStatus.textContent = 'SIGN IN REQUIRED · Configure Firebase Web sign-in, then sign in to search and load trending music.';
    return;
  }
  if (!window.firebase) {
    updateSessionUi(null);
    ui.authState.textContent = 'The Firebase Web SDK could not load.';
    ui.authFeedback.textContent = 'Check your network connection and reload the page.';
    ui.discoveryStatus.textContent = 'SIGN IN REQUIRED · Firebase sign-in is currently unavailable.';
    return;
  }
  try {
    const app = window.firebase.apps.length ? window.firebase.app() : window.firebase.initializeApp(firebaseConfig);
    auth = app.auth();
    firestore = app.firestore();
    auth.onAuthStateChanged((user) => {
      const wasAuthenticated = Boolean(signedInUser);
      updateSessionUi(user);
      if (user) {
        loadTrending();
        loadLibrary();
        applyRoute();
      }
      else {
        if (wasAuthenticated) {
          youtube?.pauseVideo();
          queue = [];
          currentIndex = -1;
          renderQueue();
          ui.recommendations.replaceChildren();
          ui.recommendationEmpty.hidden = false;
          ui.recommendationEmpty.textContent = 'Play a track to load related music.';
        }
        ui.discoveryStatus.textContent = 'SIGN IN REQUIRED · Sign in to search and load trending music.';
        ui.results.replaceChildren();
        libraryData = { history: [], liked: [], playlists: [] };
        renderLibrary();
      }
    });
  } catch (error) {
    ui.authState.textContent = `Firebase could not initialize: ${error.message}`;
    ui.authFeedback.textContent = 'Check the Firebase Web configuration and authorized domain.';
    updateSessionUi(null);
  }
}

function updateSessionUi(user) {
  signedInUser = user;
  const authenticated = Boolean(user);
  const label = user?.displayName || user?.email || 'SIGNED IN';
  ui.headerSignIn.hidden = authenticated;
  ui.headerAccount.hidden = !authenticated;
  ui.headerSignOut.hidden = !authenticated;
  ui.headerAccount.textContent = label;
  ui.signIn.hidden = authenticated;
  ui.signOut.hidden = !authenticated;
  ui.sessionGate.hidden = authenticated;
  ui.authState.textContent = authenticated
    ? `SIGNED IN · ${label}`
    : 'SIGNED OUT · Sign in to search and load trending music.';
  if (!authenticated) {
    ui.title.textContent = 'NOTHING PLAYING';
    ui.artist.textContent = 'Sign in to search for music';
  }
}

async function getIdToken() {
  if (!signedInUser) throw new Error('Sign in to search music.');
  return signedInUser.getIdToken();
}

async function api(path, options = {}, requiresAuth = false) {
  const headers = new Headers(options.headers || {});
  if (options.body) headers.set('Content-Type', 'application/json');
  if (requiresAuth) headers.set('Authorization', `Bearer ${await getIdToken()}`);
  const response = await fetch(new URL(path, API_BASE), { ...options, headers });
  if (response.status === 401 && requiresAuth) {
    let refreshedToken;
    try {
      refreshedToken = await signedInUser?.getIdToken(true);
    } catch (_) {
      refreshedToken = null;
    }
    if (!refreshedToken) {
      await auth?.signOut();
      throw new Error('Your session expired. Sign in again to continue.');
    }
    const retryHeaders = new Headers(headers);
    retryHeaders.set('Authorization', `Bearer ${refreshedToken}`);
    const retry = await fetch(new URL(path, API_BASE), { ...options, headers: retryHeaders });
    if (retry.status === 401) {
      await auth?.signOut();
      throw new Error('Your session expired. Sign in again to continue.');
    }
    return parseResponse(retry);
  }
  return parseResponse(response);
}

async function parseResponse(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.error || `Service returned ${response.status}`);
  return data;
}

function trackFrom(raw) {
  const videoId = String(raw.youtubeVideoId || raw.id || raw.videoId || raw.providerId || '');
  return {
    id: videoId,
    title: String(raw.title || 'Unknown track'),
    artist: String(raw.artist || 'Unknown artist'),
    duration: Number(raw.duration) || durationSeconds(raw.durationFormatted),
    durationFormatted: String(raw.durationFormatted || formatTime(Number(raw.duration) || 0)),
    provider: String(raw.provider || 'lumione'),
    providerId: String(raw.providerId || raw.id || raw.videoId || videoId),
    youtubeVideoId: videoId,
    durationMs: Number(raw.durationMs) || (Number(raw.duration) || durationSeconds(raw.durationFormatted)) * 1000,
    thumbnailUrl: String(raw.thumbnailUrl || ''),
    isManual: Boolean(raw.isManual)
  };
}

function loadPreferences() {
  try {
    const saved = JSON.parse(localStorage.getItem(PREFERENCES_KEY) || '{}');
    return {
      shuffle: Boolean(saved.shuffle),
      repeat: ['none', 'all', 'one'].includes(saved.repeat) ? saved.repeat : 'none'
    };
  } catch (_) {
    return { shuffle: false, repeat: 'none' };
  }
}

function savePreferences() {
  preferences.shuffle = ui.defaultShuffle.checked;
  preferences.repeat = ui.defaultRepeat.value;
  shuffleEnabled = preferences.shuffle;
  repeatMode = preferences.repeat;
  ui.shuffle.setAttribute('aria-pressed', String(shuffleEnabled));
  ui.repeat.setAttribute('aria-pressed', String(repeatMode !== 'none'));
  ui.repeat.title = `Repeat ${repeatMode}`;
  $('repeat-one').hidden = repeatMode !== 'one';
  try {
    localStorage.setItem(PREFERENCES_KEY, JSON.stringify(preferences));
    ui.preferencesFeedback.textContent = 'Playback defaults saved in this browser.';
  } catch (_) {
    ui.preferencesFeedback.textContent = 'Browser storage is unavailable; playback defaults apply for this session only.';
  }
}

function initializePreferences() {
  ui.defaultShuffle.checked = preferences.shuffle;
  ui.defaultRepeat.value = preferences.repeat;
  ui.shuffle.setAttribute('aria-pressed', String(shuffleEnabled));
  ui.repeat.setAttribute('aria-pressed', String(repeatMode !== 'none'));
  ui.repeat.title = `Repeat ${repeatMode}`;
  $('repeat-one').hidden = repeatMode !== 'one';
}

function durationSeconds(value = '') {
  const parts = value.split(':').map(Number);
  if (!parts.length || parts.some(Number.isNaN)) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

function formatTime(seconds) {
  const safe = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`;
}

function renderTracks(target, tracks, kind) {
  target.replaceChildren();
  if (!tracks.length && ['history', 'liked', 'playlist-track'].includes(kind)) {
    const empty = document.createElement('li');
    empty.className = 'empty-state';
    empty.textContent = kind === 'history' ? 'No listening history in this account yet.'
      : kind === 'liked' ? 'No liked songs in this account yet.' : 'This playlist is empty.';
    target.append(empty);
    return;
  }
  tracks.forEach((track, index) => {
    const playable = Boolean(track.id && /^[\w-]{11}$/.test(track.id));
    const row = document.createElement('li');
    const queueIndex = Number.isInteger(track.queueIndex) ? track.queueIndex : -1;
    row.className = `track-row${kind === 'queue' && queueIndex === currentIndex ? ' current' : ''}${playable ? '' : ' unplayable'}`;
    row.title = playable ? 'Track actions' : 'This metadata result has no browser-playable video ID';
    const fields = [
      makeCell('track-number', String(index + 1).padStart(2, '0')),
      makeCell('track-name', track.title),
      makeCell('track-artist-cell', track.artist),
      makeCell('track-duration', track.durationFormatted || (track.durationMs ? formatTime(track.durationMs / 1000) : '—'))
    ];
    fields.forEach((field) => row.append(field));
    const actions = document.createElement('span');
    actions.className = 'row-action';
    if (kind === 'queue') {
      if (queueIndex === currentIndex) actions.append(makeCell('playing-label', 'PLAYING'));
      else actions.append(actionButton('PLAY', () => playQueueIndex(queueIndex)));
      if (queueIndex >= 0 && queueIndex !== currentIndex) actions.append(actionButton('REMOVE', () => removeQueueTrack(queueIndex)));
    } else if (kind === 'liked' && playable) {
      actions.append(actionButton('PLAY', () => startTrackSelection(track)));
      actions.append(actionButton('UNLIKE', () => toggleLike(track)));
    } else if (kind === 'playlist-track' && playable) {
      actions.append(actionButton('PLAY', () => startTrackSelection(track)));
      actions.append(actionButton('REMOVE', () => removeFromPlaylist(track)));
    } else if (playable) {
      actions.append(actionButton('PLAY', () => startTrackSelection(track)));
      actions.append(actionButton('QUEUE', () => addToQueue(track)));
      if (signedInUser) actions.append(actionButton(isLiked(track) ? 'LIKED' : 'LIKE', () => toggleLike(track)));
      if (signedInUser && libraryData.playlists.length) actions.append(actionButton('LIST', () => addTrackToPlaylist(track)));
    } else actions.textContent = '—';
    row.append(actions);
    target.append(row);
  });
}

function actionButton(label, callback) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'row-action-button';
  button.textContent = label;
  button.addEventListener('click', (event) => { event.stopPropagation(); callback(); });
  return button;
}

function makeCell(className, text) {
  const span = document.createElement('span');
  span.className = className;
  span.textContent = text;
  return span;
}

function renderQueue() {
  const upcoming = currentIndex < 0 ? queue : queue.slice(currentIndex + 1);
  ui.queueCount.textContent = `(${upcoming.length})`;
  ui.fullQueueCount.textContent = `(${upcoming.length})`;
  ui.queueEmpty.hidden = upcoming.length > 0;
  ui.fullQueueEmpty.hidden = queue.length > 0;
  renderTracks(ui.queuePreview, upcoming.slice(0, 3).map((track, i) => ({ ...track, queueIndex: currentIndex + 1 + i })), 'queue');
  const firstVisible = Math.max(0, currentIndex);
  renderTracks(ui.queue, queue.slice(firstVisible).map((track, i) => ({ ...track, queueIndex: firstVisible + i })), 'queue');
}

function canonicalKey(track) {
  return `${track.artist}`.toLowerCase().replace(/[^a-z0-9]+/g, '') + ':::' + `${track.title}`.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function addToQueue(track, manual = true) {
  if (!track?.id || !/^[\w-]{11}$/.test(track.id)) return;
  const seen = new Set(queue.map(canonicalKey));
  if (seen.has(canonicalKey(track))) return;
  queue.push({ ...track, isManual: manual });
  renderQueue();
  if (currentIndex < 0) startTrackSelection(track);
}

function startTrackSelection(track) {
  const manualTracks = currentIndex < 0 ? [] : queue.slice(currentIndex + 1).filter((item) => item.isManual);
  queue = [{ ...track, isManual: false }, ...manualTracks];
  currentIndex = 0;
  renderQueue();
  return startTrack(track, { navigateOnPlaying: true });
}

function playQueueIndex(index) {
  if (!queue[index]) return;
  currentIndex = index;
  startTrack(queue[index]);
  renderQueue();
}

function removeQueueTrack(index) {
  if (index < 0 || index >= queue.length || index === currentIndex) return;
  queue.splice(index, 1);
  if (index < currentIndex) currentIndex -= 1;
  renderQueue();
}

function startTrack(track, options = {}) {
  if (!track.id || !/^[\w-]{11}$/.test(track.id)) {
    ui.note.textContent = 'This result has no playable YouTube video ID. It remains available as metadata only.';
    return Promise.resolve(false);
  }
  let playbackPromise = Promise.resolve(true);
  if (options.navigateOnPlaying) {
    if (pendingPlaybackSelection?.timer) clearTimeout(pendingPlaybackSelection.timer);
    playbackPromise = new Promise((resolve) => {
      const pending = { resolve, timer: null };
      pending.timer = setTimeout(() => {
        if (pendingPlaybackSelection !== pending) return;
        pendingPlaybackSelection = null;
        ui.note.textContent = 'Playback did not start. This track may be unavailable in the current browser.';
        resolve(false);
      }, 18000);
      pendingPlaybackSelection = pending;
    });
  }
  ui.title.textContent = track.title;
  ui.artist.textContent = track.artist;
  ui.note.textContent = 'Loading YouTube playback…';
  recordedHistoryId = '';
  trackEnded = false;
  renderQueue();
  loadRelated(track);
  if (!youtubeApiReady) return playbackPromise;
  ui.placeholder.hidden = true;
  if (!youtube) {
    youtube = new window.YT.Player('youtube-player', {
      width: '100%', height: '100%', videoId: track.id,
      playerVars: { autoplay: 0, controls: 0, playsinline: 1, rel: 0, modestbranding: 1 },
      events: { onReady: onPlayerReady, onStateChange: onPlayerStateChange,
        onError: () => {
          ui.note.textContent = 'YouTube could not play this video in the current browser.';
          finishPlaybackSelection(false);
        } }
    });
  } else if (youtubeReady) {
    youtube.loadVideoById(track.id);
  }
  return playbackPromise;
}

function finishPlaybackSelection(success) {
  const pending = pendingPlaybackSelection;
  if (!pending) return;
  pendingPlaybackSelection = null;
  clearTimeout(pending.timer);
  if (success) setView('home');
  pending.resolve(success);
}

function onPlayerReady(event) {
  youtubeReady = true;
  youtube = event.target;
  ui.play.disabled = ui.previous.disabled = ui.next.disabled = ui.shuffle.disabled = ui.repeat.disabled = false;
  ui.seek.disabled = false;
  youtube.playVideo();
}

function onPlayerStateChange(event) {
  if (!youtube) return;
  if (event.data === window.YT.PlayerState.PLAYING && pendingPlaybackSelection) {
    const requestedTrack = queue[currentIndex];
    const playingVideoId = youtube.getVideoData?.().video_id;
    if (requestedTrack && playingVideoId && playingVideoId !== requestedTrack.id) return;
  }
  $('audio-visualizer').classList.toggle('is-playing', event.data === window.YT.PlayerState.PLAYING);
  if (event.data === window.YT.PlayerState.PLAYING) {
    ui.note.textContent = 'PLAYING · YOUTUBE';
    ui.play.innerHTML = '<svg class="icon play-glyph" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4h4v16H7zM15 4h4v16h-4z"/></svg>';
    ui.play.classList.add('is-playing');
    ui.play.setAttribute('aria-label', 'Pause');
    ui.play.title = 'Pause';
    ui.placeholder.hidden = true;
    const activeTrack = queue[currentIndex];
    if (activeTrack && recordedHistoryId !== activeTrack.id) {
      recordedHistoryId = activeTrack.id;
      recordHistory(activeTrack);
    }
    finishPlaybackSelection(true);
  } else if (event.data === window.YT.PlayerState.PAUSED) {
    ui.note.textContent = 'PAUSED · YOUTUBE';
    ui.play.innerHTML = '<svg class="icon play-glyph" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 4 13 8-13 8z"/></svg>';
    ui.play.classList.remove('is-playing');
    ui.play.setAttribute('aria-label', 'Play');
    ui.play.title = 'Play';
  } else if (event.data === window.YT.PlayerState.ENDED) {
    ui.play.innerHTML = '<svg class="icon play-glyph" viewBox="0 0 24 24" aria-hidden="true"><path d="m7 4 13 8-13 8z"/></svg>';
    ui.play.classList.remove('is-playing');
    ui.play.setAttribute('aria-label', 'Play');
    ui.play.title = 'Play';
    trackEnded = true;
    updateHistoryCounts('completionCount');
    advanceTrack();
  } else if (event.data === window.YT.PlayerState.CUED) {
    ui.note.textContent = 'READY · YOUTUBE';
  }
}

window.onYouTubeIframeAPIReady = function onYouTubeIframeAPIReady() {
  youtubeApiReady = true;
  if (queue[currentIndex]) startTrack(queue[currentIndex]);
};
if (window.YT && window.YT.Player) window.onYouTubeIframeAPIReady();

function togglePlayback() {
  if (!youtube || !youtubeReady) return;
  const state = youtube.getPlayerState();
  if (state === window.YT.PlayerState.PLAYING || state === window.YT.PlayerState.BUFFERING) youtube.pauseVideo();
  else youtube.playVideo();
}

function advanceTrack(manual = false) {
  if (!queue.length) return;
  if (repeatMode === 'one') return startTrack(queue[currentIndex]);
  let next;
  if (shuffleEnabled && currentIndex < queue.length - 1) {
    const options = Array.from({ length: queue.length - currentIndex - 1 }, (_, i) => currentIndex + i + 1);
    next = options[Math.floor(Math.random() * options.length)];
  } else next = currentIndex + 1;
  if (next >= queue.length) {
    if (repeatMode !== 'all') return;
    next = shuffleEnabled && queue.length > 1 ? Math.floor(Math.random() * (queue.length - 1)) : 0;
  }
  if (manual && queue[currentIndex]) updateHistoryCounts('skipCount');
  playQueueIndex(next);
}

function previousTrack() {
  if (!queue.length) return;
  if (youtube?.getCurrentTime() > 3) { youtube.seekTo(0, true); return; }
  playQueueIndex(Math.max(0, currentIndex - 1));
}

async function loadTrending() {
  if (!signedInUser) return;
  ui.discoveryStatus.textContent = 'Loading trending tracks…';
  try {
    const data = await api('/api/trending', {}, true);
    const tracks = (data.results || []).map(trackFrom);
    renderTracks(ui.results, tracks, 'results');
    ui.discoveryStatus.textContent = tracks.length ? 'TRENDING · choose a row with a video ID to play' : 'No trending tracks returned.';
  } catch (error) {
    ui.discoveryStatus.textContent = error.message;
  }
}

async function searchTracks() {
  const query = ui.search.value.trim();
  if (!query) { ui.discoveryStatus.textContent = 'Enter a track or artist name.'; return; }
  if (!signedInUser) {
    intendedView = 'search';
    setView('search');
    ui.discoveryStatus.textContent = 'SIGN IN REQUIRED · Sign in to search the music catalog.';
    ui.authFeedback.textContent = 'Search and trending require a Firebase ID token.';
    return;
  }
  ui.searchButton.disabled = true;
  ui.discoveryStatus.textContent = 'Searching…';
  try {
    const data = await api(`/api/search?q=${encodeURIComponent(query)}`, {}, true);
    const tracks = (data.results || []).map(trackFrom);
    renderTracks(ui.results, tracks, 'results');
    ui.discoveryStatus.textContent = tracks.length ? `${tracks.length} RESULT${tracks.length === 1 ? '' : 'S'}` : 'No tracks found.';
  } catch (error) {
    ui.discoveryStatus.textContent = error.message;
  } finally { ui.searchButton.disabled = false; }
}

async function loadRelated(track) {
  const requestId = ++recommendationRequest;
  ui.recommendationEmpty.hidden = false;
  ui.recommendationEmpty.textContent = 'Loading related tracks…';
  ui.recommendations.replaceChildren();
  const signals = recommendationSignals();
  const recentTracks = [{ title: track.title, artist: track.artist }, ...signals.recentTracks,
    ...queue.slice(0, 8).map((item) => ({ title: item.title, artist: item.artist }))].slice(0, 24);
  const params = new URLSearchParams({
    title: track.title, artist: track.artist, videoId: track.id, duration: String(track.duration || 0), limit: '8',
    recentTracks: JSON.stringify(recentTracks), likedArtists: JSON.stringify(signals.likedArtists),
    topPlayedArtists: JSON.stringify(signals.topPlayedArtists), likedTracks: JSON.stringify(signals.likedTracks),
    skippedArtists: JSON.stringify(signals.skippedArtists)
  });
  recommendationLoading = true;
  try {
    const data = await api(`/api/recommendations/related?${params}`, {}, Boolean(signedInUser));
    if (requestId !== recommendationRequest) return;
    const tracks = (data.results || []).map(trackFrom).filter((item) => item.id && item.id !== track.id);
    renderTracks(ui.recommendations, tracks, 'recommendations');
    ui.recommendationEmpty.hidden = tracks.length > 0;
    ui.recommendationEmpty.textContent = tracks.length ? '' : 'No related tracks are available for this selection.';
    replaceGeneratedUpcoming(tracks);
  } catch (error) {
    if (requestId === recommendationRequest) ui.recommendationEmpty.textContent = `Related tracks unavailable: ${error.message}`;
  } finally {
    if (requestId === recommendationRequest) recommendationLoading = false;
  }
}

function recommendationSignals() {
  const liked = libraryData.liked;
  const history = libraryData.history;
  const artistAffinity = new Map();
  history.forEach((entry) => {
    const score = (Number(entry.playCount) || 1) + 2 * (Number(entry.completionCount) || 0) - (Number(entry.skipCount) || 0);
    if (score > 0 && entry.artist) artistAffinity.set(entry.artist, (artistAffinity.get(entry.artist) || 0) + score);
  });
  const recentTracks = history.slice(0, 15).map((entry) => ({ title: entry.title, artist: entry.artist }));
  const skippedArtists = [...new Set(history.filter((entry) => Number(entry.skipCount) - Number(entry.completionCount) >= 2)
    .map((entry) => entry.artist).filter(Boolean))].slice(0, 20);
  return {
    likedArtists: [...new Set(liked.map((track) => track.artist).filter(Boolean))].slice(0, 20),
    topPlayedArtists: [...artistAffinity.entries()].sort((a, b) => b[1] - a[1]).map(([artist]) => artist).slice(0, 5),
    likedTracks: liked.slice(0, 20).map((track) => ({ title: track.title, artist: track.artist })), recentTracks, skippedArtists
  };
}

function accountCollection(name) {
  if (!firestore || !signedInUser) throw new Error('Sign in to access your account library.');
  return firestore.collection('users').doc(signedInUser.uid).collection(name);
}

function cloudDocumentId(track) {
  const identity = `${track.provider || 'lumione'}:${track.providerId || track.id}`;
  return cloudStringId(identity);
}

function cloudStringId(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function trackCloudData(track) {
  return {
    videoId: track.id, provider: track.provider || 'lumione', providerId: track.providerId || track.id,
    youtubeVideoId: track.youtubeVideoId || track.id, title: track.title, artist: track.artist,
    durationMs: Number(track.durationMs) || Number(track.duration || 0) * 1000,
    thumbnailUrl: track.thumbnailUrl || ''
  };
}

function libraryTrack(raw) {
  return { ...trackFrom(raw), ...raw, id: String(raw.videoId || raw.youtubeVideoId || ''),
    duration: Number(raw.durationMs) ? Math.floor(Number(raw.durationMs) / 1000) : Number(raw.duration) || 0,
    durationFormatted: formatTime(Number(raw.durationMs) ? Number(raw.durationMs) / 1000 : Number(raw.duration) || 0) };
}

async function loadLibrary() {
  if (!signedInUser || !firestore) return;
  ui.libraryStatus.textContent = 'Loading account library…';
  try {
    const [historySnap, likedSnap, playlistSnap] = await Promise.all([
      accountCollection('history').orderBy('timestamp', 'desc').limit(100).get(),
      accountCollection('favorites').get(),
      accountCollection('playlists').get()
    ]);
    libraryData = {
      history: historySnap.docs.map((doc) => ({ ...libraryTrack(doc.data()), ...doc.data() })).sort((a, b) => b.timestamp - a.timestamp),
      liked: likedSnap.docs.map((doc) => libraryTrack(doc.data())),
      playlists: playlistSnap.docs.map((doc) => doc.data()).sort((a, b) => b.updatedAt - a.updatedAt)
    };
    ui.libraryStatus.textContent = 'Synced from your LUMIONE account.';
    renderLibrary();
  } catch (error) {
    ui.libraryStatus.textContent = `Library unavailable: ${error.message}`;
  }
}

function renderLibrary() {
  renderTracks(ui.historyList, libraryData.history, 'history');
  renderTracks(ui.likedList, libraryData.liked, 'liked');
  ui.playlistList.replaceChildren();
  libraryData.playlists.forEach((playlist) => {
    const row = document.createElement('li');
    row.className = 'playlist-row';
    const open = actionButton(`${playlist.name} · ${(playlist.tracks || []).length} TRACKS`, () => openPlaylist(playlist));
    const remove = actionButton('DELETE', () => deletePlaylist(playlist));
    row.append(open, remove);
    ui.playlistList.append(row);
  });
  if (!libraryData.playlists.length) {
    const empty = document.createElement('li'); empty.className = 'empty-state'; empty.textContent = 'No playlists in this account yet.';
    ui.playlistList.append(empty);
  }
}

function isLiked(track) {
  return libraryData.liked.some((item) => item.provider === track.provider && item.providerId === track.providerId);
}

async function toggleLike(track) {
  if (!signedInUser) return;
  const ref = accountCollection('favorites').doc(cloudDocumentId(track));
  try {
    if (isLiked(track)) await ref.delete();
    else await ref.set(trackCloudData(track));
    await loadLibrary();
  } catch (error) { ui.libraryStatus.textContent = `Could not update liked songs: ${error.message}`; }
}

async function recordHistory(track) {
  if (!signedInUser) return;
  try {
    const ref = accountCollection('history').doc(cloudDocumentId(track));
    await ref.set({ ...trackCloudData(track), timestamp: Date.now(),
      playCount: window.firebase.firestore.FieldValue.increment(1) }, { merge: true });
    loadLibrary();
  } catch (error) { ui.libraryStatus.textContent = `History could not sync: ${error.message}`; }
}

async function updateHistoryCounts(field) {
  const track = queue[currentIndex];
  if (!signedInUser || !track) return;
  try {
    await accountCollection('history').doc(cloudDocumentId(track)).set({ [field]: window.firebase.firestore.FieldValue.increment(1) }, { merge: true });
    loadLibrary();
  } catch (error) { ui.libraryStatus.textContent = `History could not sync: ${error.message}`; }
}

async function createPlaylist() {
  if (!signedInUser) { ui.libraryStatus.textContent = 'Sign in to create account playlists.'; return; }
  const name = window.prompt('Playlist name');
  if (!name?.trim()) return;
  const now = Date.now();
  const playlist = { id: window.crypto.randomUUID(), name: name.trim(), description: '', createdAt: now, updatedAt: now, tracks: [] };
  try {
    await accountCollection('playlists').doc(cloudStringId(playlist.id)).set(playlist);
    await loadLibrary(); openPlaylist(playlist);
  } catch (error) { ui.libraryStatus.textContent = `Could not create playlist: ${error.message}`; }
}

function openPlaylist(playlist) {
  ui.playlistDetail.hidden = false;
  ui.playlistTitle.textContent = `${playlist.name} · ${(playlist.tracks || []).length} TRACKS`;
  renderTracks(ui.playlistTracks, (playlist.tracks || []).map(libraryTrack), 'playlist-track');
  ui.playlistDetail.dataset.playlistId = playlist.id;
}

async function deletePlaylist(playlist) {
  if (!window.confirm(`Delete playlist “${playlist.name}”?`)) return;
  try {
    await accountCollection('playlists').doc(cloudStringId(playlist.id)).delete();
    ui.playlistDetail.hidden = true;
    await loadLibrary();
  } catch (error) { ui.libraryStatus.textContent = `Could not delete playlist: ${error.message}`; }
}

async function addTrackToPlaylist(track) {
  if (!libraryData.playlists.length) return;
  const options = libraryData.playlists.map((playlist, index) => `${index + 1}. ${playlist.name}`).join('\n');
  const picked = Number(window.prompt(`Add to which playlist?\n${options}`)) - 1;
  const playlist = libraryData.playlists[picked];
  if (!playlist) return;
  const keys = new Set((playlist.tracks || []).map((item) => `${item.provider}:${item.providerId || item.videoId}`));
  const identity = `${track.provider}:${track.providerId || track.id}`;
  if (!keys.has(identity)) playlist.tracks = [...(playlist.tracks || []), trackCloudData(track)];
  playlist.updatedAt = Date.now();
  try {
    await accountCollection('playlists').doc(cloudStringId(playlist.id)).set(playlist);
    await loadLibrary(); openPlaylist(playlist);
  } catch (error) { ui.libraryStatus.textContent = `Could not update playlist: ${error.message}`; }
}

async function removeFromPlaylist(track) {
  const playlist = libraryData.playlists.find((item) => item.id === ui.playlistDetail.dataset.playlistId);
  if (!playlist) return;
  const identity = `${track.provider}:${track.providerId || track.id}`;
  playlist.tracks = (playlist.tracks || []).filter((item) => `${item.provider || 'lumione'}:${item.providerId || item.videoId}` !== identity);
  playlist.updatedAt = Date.now();
  try {
    await accountCollection('playlists').doc(cloudStringId(playlist.id)).set(playlist);
    await loadLibrary(); openPlaylist(playlist);
  } catch (error) { ui.libraryStatus.textContent = `Could not update playlist: ${error.message}`; }
}

function replaceGeneratedUpcoming(tracks) {
  if (queueMode !== 'radio' || currentIndex < 0) return;
  const prefix = queue.slice(0, currentIndex + 1);
  const manual = queue.slice(currentIndex + 1).filter((item) => item.isManual);
  const used = new Set([...prefix, ...manual].map(canonicalKey));
  const generated = tracks.filter((item) => !used.has(canonicalKey(item))).map((item) => ({ ...item, isManual: false }));
  queue = [...prefix, ...manual, ...generated];
  renderQueue();
  if (trackEnded && queue[currentIndex + 1]) playQueueIndex(currentIndex + 1);
}

async function checkService() {
  try {
    await api('/api/health');
    ui.settingsService.textContent = 'ONLINE';
  } catch {
    ui.settingsService.textContent = 'OFFLINE';
    ui.discoveryStatus.textContent = 'Backend unavailable. Check the configured LUMIONE service.';
  }
}

function applyView(view) {
  const views = ['home', 'search', 'queue', 'library', 'settings'];
  if (!views.includes(view)) view = 'home';
  currentView = view;
  const main = document.querySelector('.main-content');
  main.dataset.view = view;
  document.querySelectorAll('[data-view]').forEach((link) => {
    link.classList.toggle('active', link.dataset.view === view && link.classList.contains('nav-link'));
    link.classList.toggle('selected', link.dataset.view === view && link.classList.contains('side-link'));
  });
  ui.playerView.hidden = view !== 'home';
  ui.searchView.hidden = view !== 'search';
  ui.queueView.hidden = view !== 'queue';
  ui.settings.hidden = view !== 'settings';
  ui.libraryView.hidden = view !== 'library';
  if (view === 'search') {
    intendedView = 'search';
    if (!signedInUser) ui.discoveryStatus.textContent = 'SIGN IN REQUIRED · Sign in to search the music catalog.';
    requestAnimationFrame(() => ui.search.focus());
  }
  if (view === 'home') intendedView = 'home';
  if (view === 'settings') {
    if (!ui.settingsService.textContent || ui.settingsService.textContent === 'CHECKING') checkService();
  }
  if (view === 'library' && !signedInUser) ui.libraryStatus.textContent = 'SIGN IN TO SYNC YOUR HISTORY, LIKED SONGS, AND PLAYLISTS.';
  main.scrollTo({ top: 0 });
  closeMobileDrawer();
}

function applyRoute() {
  const routeParts = (window.location.hash || '#/home').replace(/^#\/?/, '').split('/').map((part) => part.toLowerCase());
  const requested = routeParts[0];
  const route = ['home', 'search', 'queue', 'library', 'settings'].includes(requested) ? requested : 'home';
  if (route !== requested) window.history.replaceState(null, '', '#/home');
  applyView(route);
  if (route === 'library') setLibraryTab(['history', 'liked', 'playlists'].includes(routeParts[1]) ? routeParts[1] : 'history');
}

function setView(view) {
  if (!['home', 'search', 'queue', 'library', 'settings'].includes(view)) view = 'home';
  const hash = `#/${view}`;
  if (window.location.hash === hash) applyView(view);
  else window.location.hash = hash;
}

function isNarrowViewport() {
  return window.matchMedia('(max-width: 760px)').matches;
}

function closeMobileDrawer() {
  document.querySelector('.app-shell').classList.remove('mobile-drawer-open');
  $('sidebar-backdrop').hidden = true;
  $('mobile-menu-toggle').setAttribute('aria-label', 'Open navigation');
  $('mobile-menu-toggle').title = 'Open navigation';
}

function openMobileDrawer() {
  document.querySelector('.app-shell').classList.add('mobile-drawer-open');
  $('sidebar-backdrop').hidden = false;
  $('mobile-menu-toggle').setAttribute('aria-label', 'Close navigation');
  $('mobile-menu-toggle').title = 'Close navigation';
}

function initializeSidebar() {
  try { sidebarCollapsed = localStorage.getItem(SIDEBAR_KEY) === 'true'; } catch { sidebarCollapsed = false; }
  document.querySelector('.app-shell').classList.toggle('sidebar-collapsed', sidebarCollapsed);
  const toggle = $('sidebar-toggle');
  toggle.setAttribute('aria-label', sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar');
  toggle.title = toggle.getAttribute('aria-label');
  toggle.addEventListener('click', () => {
    if (isNarrowViewport()) {
      closeMobileDrawer();
      return;
    }
    sidebarCollapsed = !sidebarCollapsed;
    document.querySelector('.app-shell').classList.toggle('sidebar-collapsed', sidebarCollapsed);
    const label = sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar';
    toggle.setAttribute('aria-label', label);
    toggle.title = label;
    try { localStorage.setItem(SIDEBAR_KEY, String(sidebarCollapsed)); } catch { /* Storage can be disabled. */ }
  });
  $('mobile-menu-toggle').addEventListener('click', () => {
    document.querySelector('.app-shell').classList.contains('mobile-drawer-open') ? closeMobileDrawer() : openMobileDrawer();
  });
  $('sidebar-backdrop').addEventListener('click', closeMobileDrawer);
  window.addEventListener('resize', () => { if (!isNarrowViewport()) closeMobileDrawer(); });
}

function setLibraryTab(tab) {
  if (!['history', 'liked', 'playlists'].includes(tab)) return;
  selectedLibraryTab = tab;
  document.querySelectorAll('[data-library-tab]').forEach((button) => button.classList.toggle('active', button.dataset.libraryTab === tab));
  document.querySelectorAll('.library-panel').forEach((panel) => { panel.hidden = panel.id !== `library-${tab}`; });
}

function navigateLibraryTab(tab) {
  if (!['history', 'liked', 'playlists'].includes(tab)) return;
  const route = `#/library/${tab}`;
  if (window.location.hash === route) setLibraryTab(tab);
  else window.location.hash = route;
}

async function beginSignIn() {
  if (!auth) {
    const message = 'Sign-in is unavailable until Firebase Web configuration is added to firebase-config.js.';
    ui.authState.textContent = 'CONFIGURATION REQUIRED';
    ui.authFeedback.textContent = message;
    ui.sessionGateMessage.textContent = message;
    ui.discoveryStatus.textContent = `SIGN IN REQUIRED · ${message}`;
    return;
  }
  intendedView = currentView;
  ui.authFeedback.textContent = 'Opening Google sign-in…';
  try {
    await auth.signInWithPopup(new window.firebase.auth.GoogleAuthProvider());
    ui.authFeedback.textContent = '';
  } catch (error) {
    ui.authFeedback.textContent = `Sign-in failed: ${error.message}`;
    ui.sessionGateMessage.textContent = `Sign-in failed: ${error.message}`;
  }
}

async function signOut() {
  if (!auth) return;
  try {
    await auth.signOut();
    intendedView = 'home';
    setView('home');
    ui.discoveryStatus.textContent = 'SIGNED OUT · Sign in to search and load trending music.';
  } catch (error) {
    ui.authFeedback.textContent = `Sign-out failed: ${error.message}`;
  }
}

function setupEvents() {
  ui.searchButton.addEventListener('click', searchTracks);
  ui.search.addEventListener('keydown', (event) => { if (event.key === 'Enter') searchTracks(); });
  $('refresh-discovery').addEventListener('click', loadTrending);
  ui.signIn.addEventListener('click', beginSignIn);
  ui.homeSignIn.addEventListener('click', () => beginSignIn());
  ui.headerSignIn.addEventListener('click', () => {
    if (!auth) setView('settings');
    beginSignIn();
  });
  ui.signOut.addEventListener('click', signOut);
  ui.headerSignOut.addEventListener('click', signOut);
  ui.defaultShuffle.addEventListener('change', savePreferences);
  ui.defaultRepeat.addEventListener('change', savePreferences);
  $('create-playlist').addEventListener('click', createPlaylist);
  document.querySelectorAll('[data-library-tab]').forEach((button) => button.addEventListener('click', () => navigateLibraryTab(button.dataset.libraryTab)));
  ui.play.addEventListener('click', togglePlayback);
  ui.previous.addEventListener('click', previousTrack);
  ui.next.addEventListener('click', () => advanceTrack(true));
  ui.shuffle.addEventListener('click', () => {
    shuffleEnabled = !shuffleEnabled;
    ui.shuffle.setAttribute('aria-pressed', String(shuffleEnabled));
  });
  ui.repeat.addEventListener('click', () => {
    repeatMode = ({ none: 'all', all: 'one', one: 'none' })[repeatMode];
    ui.repeat.setAttribute('aria-pressed', String(repeatMode !== 'none'));
    ui.repeat.title = `Repeat ${repeatMode}`;
    $('repeat-one').hidden = repeatMode !== 'one';
  });
  ui.seek.addEventListener('input', () => { seekInProgress = true; });
  ui.seek.addEventListener('change', () => {
    if (youtube && youtubeReady && youtube.getDuration()) youtube.seekTo(Number(ui.seek.value) / 1000 * youtube.getDuration(), true);
    seekInProgress = false;
  });
  document.querySelectorAll('[data-view]').forEach((link) => link.addEventListener('click', (event) => {
    if (link instanceof HTMLAnchorElement) event.preventDefault();
    setView(link.dataset.view);
  }));
  window.addEventListener('hashchange', applyRoute);
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && document.querySelector('.app-shell').classList.contains('mobile-drawer-open')) closeMobileDrawer();
    if (event.target instanceof HTMLInputElement) return;
    if (event.code === 'Space') { event.preventDefault(); togglePlayback(); }
    if (event.key === 'ArrowRight') advanceTrack(true);
    if (event.key === 'ArrowLeft') previousTrack();
  });
}

setInterval(() => {
  if (!youtube || !youtubeReady || seekInProgress) return;
  const duration = Number(youtube.getDuration()) || 0;
  const current = Number(youtube.getCurrentTime()) || 0;
  ui.timeCurrent.textContent = formatTime(current);
  ui.timeDuration.textContent = formatTime(duration || queue[currentIndex]?.duration || 0);
  if (duration) ui.seek.value = String(Math.round(current / duration * 1000));
}, 500);

setupEvents();
initializeSidebar();
initializePreferences();
setLibraryTab(selectedLibraryTab);
applyRoute();
checkService();
initializeAuth();
