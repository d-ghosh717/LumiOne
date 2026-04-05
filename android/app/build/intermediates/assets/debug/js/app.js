/* ═══════════════════════════════════════════════════════════════
   LUMIONE — App Controller
   Main orchestrator: routing, state, initialization
   ═══════════════════════════════════════════════════════════════ */

const App = (() => {
  let currentView = 'home';
  let viewStack = ['home'];
  let trendingCache = [];
  let isInitialized = false;

  // ── Initialize ─────────────────────────────────────────────
  async function init() {
    if (isInitialized) return;
    isInitialized = true;

    console.log('[LumiOne] Initializing...');

    // Bind Player callbacks
    Player.onStateChange = handleStateChange;
    Player.onProgressUpdate = handleProgress;
    Player.onTrackChange = handleTrackChange;
    Player.onQueueChange = handleQueueChange;
    Player.onError = (msg) => UI.showToast(msg);

    // Bind navigation
    document.querySelectorAll('.nav-item').forEach(item => {
      item.addEventListener('click', () => {
        const view = item.dataset.view;
        if (view) navigateTo(view);
      });
    });

    // Bind mini player
    const miniPlayer = document.querySelector('#mini-player');
    if (miniPlayer) {
      miniPlayer.addEventListener('click', (e) => {
        if (e.target.closest('.mini-player-controls')) return;
        navigateTo('player');
      });
    }

    const miniPlayBtn = document.querySelector('#mini-play-btn');
    if (miniPlayBtn) {
      miniPlayBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        Player.togglePlay();
      });
    }

    const miniNextBtn = document.querySelector('#mini-next-btn');
    if (miniNextBtn) {
      miniNextBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        Player.playNext();
      });
    }

    // Load trending data in background
    loadTrending();

    // Show home view
    navigateTo('home');

    console.log('[LumiOne] Ready ✓');
  }

  // ── Load Data ──────────────────────────────────────────────
  async function loadTrending() {
    try {
      trendingCache = await API.getTrending();
    } catch {
      trendingCache = [];
    }
    
    // Try to update trending in-place first (no full re-render disruption)
    const trendingList = document.querySelector('#trending-list');
    if (trendingList && currentView === 'home') {
      trendingList.innerHTML = '';
      if (trendingCache.length > 0) {
        trendingCache.forEach((track, i) => {
          // Use a minimal song item render
          const div = document.createElement('div');
          div.className = 'song-item';
          div.style.cursor = 'pointer';
          div.innerHTML = `
            <img class="song-item-thumb" 
                 src="https://i.ytimg.com/vi/${track.id}/hqdefault.jpg" 
                 loading="lazy" alt=""
                 onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 52 52%22><rect fill=%22%23181826%22 width=%2252%22 height=%2252%22/><text x=%2226%22 y=%2230%22 text-anchor=%22middle%22 fill=%22%23757482%22 font-size=%2218%22>♪</text></svg>'">
            <div class="song-item-info">
              <div class="song-item-title">${escapeHtmlLocal(track.title)}</div>
              <div class="song-item-artist">${escapeHtmlLocal(track.artist || 'Unknown')}</div>
            </div>
            ${track.durationFormatted ? `<span class="song-item-duration">${track.durationFormatted}</span>` : ''}
          `;
          div.addEventListener('click', () => {
            Player.play(track);
            const remaining = trendingCache.filter(tr => tr.id !== track.id);
            Player.addMultipleToQueue(remaining);
            navigateTo('player');
          });
          trendingList.appendChild(div);
        });
      } else {
        trendingList.innerHTML = `
          <div class="empty-state" style="padding: var(--spacing-8);">
            <span class="material-symbols-rounded">wifi_off</span>
            <span class="empty-state-text">Couldn't load trending<br>Try searching instead</span>
          </div>`;
      }
    } else if (currentView === 'home') {
      // Full re-render only if trending-list DOM isn't there yet
      UI.renderHome(trendingCache, Player.getHistory());
    }
  }

  function escapeHtmlLocal(text) {
    if (!text) return '';
    const d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
  }

  // ── Navigation ─────────────────────────────────────────────
  function navigateTo(viewId) {
    if (viewId === currentView && viewId !== 'home') return;

    // Hide all views
    document.querySelectorAll('.view').forEach(v => {
      v.classList.remove('active');
    });

    // Show target view
    const targetView = document.querySelector(`#view-${viewId}`);
    if (targetView) {
      targetView.classList.add('active');
    }

    // Track navigation stack
    if (viewId !== currentView) {
      if (viewId === 'home') {
        viewStack = ['home'];
      } else {
        viewStack.push(viewId);
      }
    }

    currentView = viewId;

    // Render the view content
    switch (viewId) {
      case 'home':
        UI.renderHome(trendingCache, Player.getHistory());
        break;
      case 'player':
        UI.renderPlayerView(Player.getCurrentTrack());
        break;
      case 'queue':
        UI.renderQueue();
        break;
      case 'search':
        UI.renderSearch();
        break;
      case 'settings':
        UI.renderSettings();
        break;
    }

    // Update bottom nav active state
    UI.updateActiveNav(viewId);

    // Show/hide bottom nav and mini player based on view
    const bottomNav = document.querySelector('.bottom-nav');
    const miniPlayer = document.querySelector('#mini-player');
    
    if (viewId === 'player') {
      if (bottomNav) bottomNav.style.display = 'none';
      if (miniPlayer) miniPlayer.style.display = 'none';
    } else {
      if (bottomNav) bottomNav.style.display = 'flex';
      if (miniPlayer) miniPlayer.style.display = '';
    }
  }

  function navigateBack() {
    viewStack.pop();
    const previous = viewStack[viewStack.length - 1] || 'home';
    navigateTo(previous);
  }

  // ── Search ─────────────────────────────────────────────────
  async function performSearch(query) {
    if (!query || query.length < 2) return;

    UI.showSearchLoading();
    UI.saveRecentSearch(query);

    try {
      const results = await API.search(query);
      UI.renderSearchResults(results);
    } catch (err) {
      UI.renderSearchResults([]);
      UI.showToast('Search failed — check server connection');
    }
  }

  // ── Player Callbacks ───────────────────────────────────────
  function handleStateChange(playing) {
    const track = Player.getCurrentTrack();
    UI.updateMiniPlayer(track, playing);
    if (currentView === 'player') {
      UI.updatePlayerState({
        isPlaying: playing,
        shuffleMode: Player.getShuffleMode(),
        repeatMode: Player.getRepeatMode()
      });
    }
  }

  function handleProgress(posSec, durSec) {
    // Update mini player progress
    if (durSec > 0) {
      UI.updateMiniProgress((posSec / durSec) * 100);
    }

    // Update full player progress
    if (currentView === 'player') {
      UI.updatePlayerProgress(posSec, durSec);
    }
  }

  function handleTrackChange(track) {
    if (!track) return;

    // Update player view if visible
    if (currentView === 'player') {
      UI.renderPlayerView(track);
    }

    // Update mini player
    UI.updateMiniPlayer(track, Player.getIsPlaying());

    // Update ambient background color based on thumbnail
    updateAmbientColors(track);
  }

  function handleQueueChange(queue, index) {
    if (currentView === 'queue') {
      UI.renderQueue();
    }
  }

  // ── Ambient Color Updates ──────────────────────────────────
  function updateAmbientColors(track) {
    const orbs = document.querySelectorAll('.ambient-orb');
    if (orbs.length >= 3) {
      const hue = (track.title.length * 13 + track.id.charCodeAt(0) * 7) % 360;
      orbs[0].style.background = `radial-gradient(circle, hsl(${hue}, 70%, 50%) 0%, transparent 70%)`;
      orbs[1].style.background = `radial-gradient(circle, hsl(${(hue + 120) % 360}, 60%, 40%) 0%, transparent 70%)`;
      orbs[2].style.background = `radial-gradient(circle, hsl(${(hue + 240) % 360}, 65%, 45%) 0%, transparent 70%)`;
    }
  }

  // ── Android Lifecycle ──────────────────────────────────────
  window.handleBackPress = () => {
    if (currentView === 'player' || currentView === 'settings') {
      navigateBack();
      return true;
    }
    return false;
  };

  window.onAppPause = () => {
    // App went to background - keep playing
  };

  window.onAppResume = () => {
    // App came to foreground
    if (currentView === 'player') {
      UI.renderPlayerView(Player.getCurrentTrack());
    }
  };

  // ── Start ──────────────────────────────────────────────────
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  return {
    navigateTo,
    navigateBack,
    performSearch,
    get currentView() { return currentView; },
  };
})();

// ── Expose globally so Android native bridge can call window.App.* ──
window.App = App;
