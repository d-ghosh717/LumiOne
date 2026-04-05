/* ═══════════════════════════════════════════════════════════════
   LUMINOUS — UI Renderer
   DOM manipulation & rendering for all screens
   ═══════════════════════════════════════════════════════════════ */

const UI = (() => {
  // ── DOM References ─────────────────────────────────────────
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  // ── Render: Song Item ──────────────────────────────────────
  function renderSongItem(track, index, options = {}) {
    const { showDuration = true, showRemove = false, isPlaying = false, onClick, onRemove } = options;
    
    const div = document.createElement('div');
    div.className = `song-item${isPlaying ? ' playing' : ''}`;
    div.innerHTML = `
      <img class="song-item-thumb" 
           src="${track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`}" 
           alt="${escapeHtml(track.title)}"
           loading="lazy"
           onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 52 52%22><rect fill=%22%23181826%22 width=%2252%22 height=%2252%22/><text x=%2226%22 y=%2230%22 text-anchor=%22middle%22 fill=%22%23757482%22 font-size=%2218%22>♪</text></svg>'">
      <div class="song-item-info">
        <div class="song-item-title">${escapeHtml(track.title)}</div>
        <div class="song-item-artist">${escapeHtml(track.artist || 'Unknown Artist')}</div>
      </div>
      ${isPlaying ? `
        <div class="playing-indicator">
          <span></span><span></span><span></span>
        </div>
      ` : ''}
      ${showDuration && track.durationFormatted ? `
        <span class="song-item-duration">${track.durationFormatted}</span>
      ` : ''}
      ${showRemove ? `
        <button class="btn btn-icon btn-icon-sm remove-btn" data-index="${index}">
          <span class="material-symbols-rounded">close</span>
        </button>
      ` : ''}
    `;

    if (onClick) {
      div.addEventListener('click', (e) => {
        if (e.target.closest('.remove-btn')) return;
        onClick(track, index);
      });
    }

    if (showRemove && onRemove) {
      const removeBtn = div.querySelector('.remove-btn');
      if (removeBtn) {
        removeBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          onRemove(index);
        });
      }
    }

    return div;
  }

  // ── Render: Home Screen ────────────────────────────────────
  function renderHome(trendingTracks, historyTracks) {
    const container = $('#home-content');
    if (!container) return;

    const greeting = getGreeting();

    container.innerHTML = `
      <div class="home-header">
        <div>
          <div class="home-greeting">${greeting}</div>
          <div class="home-greeting-sub">DISCOVER SOMETHING NEW</div>
        </div>
        <button class="btn btn-icon" id="btn-settings">
          <span class="material-symbols-rounded">settings</span>
        </button>
      </div>

      <!-- Hero Card -->
      <div class="hero-card" id="hero-card">
        <div class="hero-card-bg" style="background: linear-gradient(135deg, #0a1628 0%, #1a0a2e 50%, #0d0d18 100%);"></div>
        <div class="hero-card-gradient"></div>
        <div class="hero-card-content">
          <span class="hero-card-label">FOR YOU • RECOMMENDED</span>
          <span class="hero-card-title">Discover Your<br>Sonic Atmosphere</span>
          <span class="hero-card-subtitle">Search for any song to begin your journey</span>
        </div>
        <button class="hero-play-btn" id="hero-search-btn">
          <span class="material-symbols-rounded">search</span>
        </button>
      </div>

      <!-- Continue Listening -->
      ${historyTracks.length > 0 ? `
        <div class="home-section">
          <div class="section-header">
            <span class="section-title">Continue Listening</span>
            <button class="section-action" id="btn-clear-history">CLEAR</button>
          </div>
          <div class="h-scroll" id="history-scroll"></div>
        </div>
      ` : ''}

      <!-- Mood Picks -->
      <div class="home-section">
        <div class="section-header">
          <span class="section-title">Mood Picks</span>
        </div>
        <div class="h-scroll" id="mood-scroll"></div>
      </div>

      <!-- Trending -->
      <div class="home-section">
        <div class="section-header">
          <span class="section-title">Trending Now</span>
        </div>
        <div id="trending-list" style="padding: 0 var(--spacing-3);">
          ${Array(5).fill('').map(() => `
            <div class="song-item">
              <div class="skeleton" style="width: 52px; height: 52px; border-radius: var(--radius-sm); flex-shrink: 0;"></div>
              <div class="song-item-info">
                <div class="skeleton" style="width: 70%; height: 16px; margin-bottom: 6px;"></div>
                <div class="skeleton" style="width: 40%; height: 12px;"></div>
              </div>
              <div class="skeleton" style="width: 36px; height: 14px; flex-shrink: 0;"></div>
            </div>
          `).join('')}
        </div>
      </div>
    `;

    // Render history bubbles
    if (historyTracks.length > 0) {
      const historyScroll = $('#history-scroll');
      historyTracks.slice(0, 10).forEach(track => {
        const bubble = document.createElement('div');
        bubble.className = 'category-bubble';
        bubble.innerHTML = `
          <img class="category-bubble-img" 
               src="${track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`}"
               loading="lazy"
               onerror="this.style.background='var(--surface-container-high)'">
          <span class="category-bubble-label">${escapeHtml(track.title.substring(0, 15))}</span>
        `;
        bubble.addEventListener('click', () => Player.play(track));
        historyScroll.appendChild(bubble);
      });
    }

    // Render mood cards
    const moodScroll = $('#mood-scroll');
    const moods = [
      { name: 'Chill', query: 'chill lofi beats', gradient: 'linear-gradient(135deg, #0d2b45, #0a4d68)' },
      { name: 'Workout', query: 'workout energy music', gradient: 'linear-gradient(135deg, #4a0e0e, #8b1a1a)' },
      { name: 'Focus', query: 'focus ambient music', gradient: 'linear-gradient(135deg, #1a0a3e, #2d1b69)' },
      { name: 'Party', query: 'party dance hits', gradient: 'linear-gradient(135deg, #3d0a4a, #6b1a8d)' },
      { name: 'Romance', query: 'romantic love songs', gradient: 'linear-gradient(135deg, #3d0a1a, #6b1a3d)' },
    ];
    
    moods.forEach(mood => {
      const card = document.createElement('div');
      card.className = 'mood-card';
      card.innerHTML = `
        <div class="mood-card-bg" style="background: ${mood.gradient};"></div>
        <div class="mood-card-overlay"></div>
        <span class="mood-card-label">${mood.name}</span>
      `;
      card.addEventListener('click', () => {
        App.navigateTo('search');
        setTimeout(() => {
          const input = $('#search-input');
          if (input) {
            input.value = mood.query;
            input.dispatchEvent(new Event('input'));
            App.performSearch(mood.query);
          }
        }, 100);
      });
      moodScroll.appendChild(card);
    });

    // Render trending
    const trendingList = $('#trending-list');
    if (trendingTracks.length > 0) {
      trendingTracks.forEach((track, i) => {
        const item = renderSongItem(track, i, {
          onClick: (t) => {
            Player.play(t);
            Player.addMultipleToQueue(trendingTracks.filter(tr => tr.id !== t.id));
          }
        });
        trendingList.appendChild(item);
      });
    } else {
      trendingList.innerHTML = `
        <div class="empty-state" style="padding: var(--spacing-8);">
          <span class="material-symbols-rounded">wifi_off</span>
          <span class="empty-state-text">Couldn't load trending<br>Try searching instead</span>
        </div>
      `;
    }

    // Event listeners
    const settingsBtn = $('#btn-settings');
    if (settingsBtn) settingsBtn.addEventListener('click', () => App.navigateTo('settings'));
    
    const heroSearchBtn = $('#hero-search-btn');
    if (heroSearchBtn) heroSearchBtn.addEventListener('click', () => App.navigateTo('search'));

    const heroCard = $('#hero-card');
    if (heroCard) heroCard.addEventListener('click', () => App.navigateTo('search'));

    const clearHistoryBtn = $('#btn-clear-history');
    if (clearHistoryBtn) {
      clearHistoryBtn.addEventListener('click', () => {
        localStorage.removeItem('luminous_history');
        renderHome(trendingTracks, []);
        showToast('History cleared');
      });
    }
  }

  // ── Render: Full Screen Player ─────────────────────────────
  function renderPlayerView(track) {
    const container = $('#player-content');
    if (!container || !track) return;

    const thumbUrl = track.thumbnail || `https://i.ytimg.com/vi/${track.id}/maxresdefault.jpg`;
    const nextTrack = Player.getNextTrack();

    container.innerHTML = `
      <div class="player-bg">
        <img class="player-bg-image" src="${thumbUrl}" alt="" 
             onerror="this.style.display='none'">
        <div class="player-bg-overlay"></div>
      </div>

      <div class="player-header">
        <button class="btn btn-icon" id="btn-player-back">
          <span class="material-symbols-rounded">keyboard_arrow_down</span>
        </button>
        <span class="player-header-title">NOW PLAYING</span>
        <button class="btn btn-icon" id="btn-player-queue">
          <span class="material-symbols-rounded">queue_music</span>
        </button>
      </div>

      <div class="player-artwork-container">
        <div class="player-artwork-glow" id="artwork-glow"></div>
        <img class="player-artwork" id="player-art" src="${thumbUrl}" alt="${escapeHtml(track.title)}"
             onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 320 320%22><rect fill=%22%23181826%22 width=%22320%22 height=%22320%22/><text x=%22160%22 y=%22170%22 text-anchor=%22middle%22 fill=%22%23757482%22 font-size=%2264%22>♪</text></svg>'">
      </div>

      <div class="player-info">
        <div class="player-track-title">${escapeHtml(track.title)}</div>
        <div class="player-track-artist">${escapeHtml(track.artist || 'Unknown Artist')}</div>
      </div>

      <div class="player-progress">
        <div class="progress-bar" id="player-progress-bar">
          <div class="progress-fill" id="player-progress-fill">
            <div class="progress-thumb"></div>
          </div>
        </div>
        <div class="progress-times">
          <span class="progress-time" id="player-time-current">0:00</span>
          <span class="progress-time" id="player-time-total">0:00</span>
        </div>
      </div>

      <div class="player-controls">
        <button class="btn btn-icon ${Player.shuffleMode ? 'control-active' : ''}" id="btn-shuffle">
          <span class="material-symbols-rounded">shuffle</span>
        </button>
        <button class="btn btn-icon" id="btn-prev" style="font-size: 32px;">
          <span class="material-symbols-rounded" style="font-size: 32px;">skip_previous</span>
        </button>
        <button class="btn btn-play-main" id="btn-play-pause">
          <span class="material-symbols-rounded" id="play-pause-icon">pause</span>
        </button>
        <button class="btn btn-icon" id="btn-next" style="font-size: 32px;">
          <span class="material-symbols-rounded" style="font-size: 32px;">skip_next</span>
        </button>
        <button class="btn btn-icon ${Player.repeatMode > 0 ? 'control-active' : ''}" id="btn-repeat">
          <span class="material-symbols-rounded">${Player.repeatMode === 2 ? 'repeat_one' : 'repeat'}</span>
        </button>
      </div>

      <div class="player-controls-secondary">
        <button class="btn btn-icon btn-icon-sm" id="btn-share">
          <span class="material-symbols-rounded" style="font-size: 20px;">share</span>
        </button>
        <div class="waveform" id="waveform"></div>
        <button class="btn btn-icon btn-icon-sm" id="btn-download">
          <span class="material-symbols-rounded" style="font-size: 20px;">download</span>
        </button>
      </div>

      ${nextTrack ? `
        <div class="up-next-panel">
          <div class="up-next-label">UP NEXT</div>
          <div class="song-item" id="up-next-item" style="padding: 0;">
            <img class="song-item-thumb" src="${nextTrack.thumbnail || `https://i.ytimg.com/vi/${nextTrack.id}/hqdefault.jpg`}" 
                 loading="lazy" alt="">
            <div class="song-item-info">
              <div class="song-item-title">${escapeHtml(nextTrack.title)}</div>
              <div class="song-item-artist">${escapeHtml(nextTrack.artist || '')}</div>
            </div>
          </div>
        </div>
      ` : ''}
    `;

    // Build waveform
    const waveformEl = $('#waveform');
    if (waveformEl) {
      for (let i = 0; i < 30; i++) {
        const bar = document.createElement('div');
        bar.className = 'waveform-bar unplayed';
        bar.style.height = `${Math.random() * 80 + 20}%`;
        waveformEl.appendChild(bar);
      }
    }

    // Progress bar interaction
    const progressBar = $('#player-progress-bar');
    if (progressBar) {
      let isDragging = false;

      const handleProgressSeek = (e) => {
        const rect = progressBar.getBoundingClientRect();
        const x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
        const percent = Math.max(0, Math.min(1, x / rect.width));
        Player.seekPercent(percent);
      };

      progressBar.addEventListener('touchstart', (e) => { isDragging = true; handleProgressSeek(e); });
      progressBar.addEventListener('touchmove', (e) => { if (isDragging) handleProgressSeek(e); });
      progressBar.addEventListener('touchend', () => { isDragging = false; });
      progressBar.addEventListener('click', handleProgressSeek);
    }

    // Control buttons
    bindBtn('#btn-player-back', () => App.navigateBack());
    bindBtn('#btn-player-queue', () => App.navigateTo('queue'));
    bindBtn('#btn-shuffle', () => Player.toggleShuffle());
    bindBtn('#btn-prev', () => Player.playPrevious());
    bindBtn('#btn-play-pause', () => Player.togglePlay());
    bindBtn('#btn-next', () => Player.playNext());
    bindBtn('#btn-repeat', () => Player.toggleRepeat());
    bindBtn('#up-next-item', () => Player.playNext());

    // Share: use Android native share sheet, fallback to clipboard
    bindBtn('#btn-share', () => {
      if (window.NativeMedia && window.NativeMedia.shareTrack) {
        window.NativeMedia.shareTrack(track.title, track.artist || '', track.id);
      } else {
        const url = `https://www.youtube.com/watch?v=${track.id}`;
        const text = `🎵 ${track.title} — ${track.artist || ''}
${url}`;
        if (navigator.share) {
          navigator.share({ title: track.title, text, url });
        } else if (navigator.clipboard) {
          navigator.clipboard.writeText(text).then(() => showToast('🔗 Link copied!'));
        } else {
          showToast('Share not supported on this device');
        }
      }
    });

    // Download: trigger native DownloadManager
    bindBtn('#btn-download', () => {
      if (window.NativeMedia && window.NativeMedia.downloadTrack) {
        showToast('⏬ Fetching download link...');
        window.NativeMedia.downloadTrack(track.id, track.title, track.artist || '');
      } else {
        showToast('Download not available');
      }
    });
  } // end renderPlayerView

  // ── Render: Queue Screen ───────────────────────────────────
  function renderQueue() {
    const container = $('#queue-content');
    if (!container) return;

    const queue = Player.getQueue();
    const currentIndex = Player.getCurrentIndex();

    container.innerHTML = `
      <div class="queue-header">
        <div class="queue-title">Queue</div>
        <div class="queue-count">${queue.length} TRACK${queue.length !== 1 ? 'S' : ''}</div>
      </div>

      <div class="queue-radio">
        <div class="radio-toggle">
          <div class="radio-toggle-info">
            <span class="radio-toggle-title">
              <span class="material-symbols-rounded" style="font-size: 18px; vertical-align: middle;">auto_awesome</span>
              Smart Radio Mode
            </span>
            <span class="radio-toggle-desc">Start infinite stream from current vibe</span>
          </div>
          <div class="toggle-switch" id="smart-radio-toggle"></div>
        </div>
      </div>

      <div class="queue-list" id="queue-list"></div>

      ${queue.length > 1 ? `
        <div style="padding: var(--spacing-4) var(--spacing-5); text-align: center;">
          <button class="btn btn-secondary" id="btn-clear-queue">
            <span class="material-symbols-rounded" style="font-size: 18px;">delete_sweep</span>
            Clear Queue
          </button>
        </div>
      ` : ''}
    `;

    // Render queue items
    const queueList = $('#queue-list');
    queue.forEach((track, i) => {
      const item = renderSongItem(track, i, {
        showDuration: true,
        showRemove: true,
        isPlaying: i === currentIndex,
        onClick: (t, idx) => Player.playFromQueue(idx),
        onRemove: (idx) => {
          Player.removeFromQueue(idx);
          renderQueue();
        },
      });
      queueList.appendChild(item);
    });

    if (queue.length === 0) {
      queueList.innerHTML = `
        <div class="empty-state">
          <span class="material-symbols-rounded">queue_music</span>
          <span class="empty-state-text">Your queue is empty<br>Search and add some tracks!</span>
        </div>
      `;
    }

    bindBtn('#btn-clear-queue', () => {
      Player.clearQueue();
      renderQueue();
      showToast('Queue cleared');
    });
  }

  // ── Render: Search Screen ──────────────────────────────────
  function renderSearch() {
    const container = $('#search-content');
    if (!container) return;

    const recentSearches = getRecentSearches();

    container.innerHTML = `
      <div class="search-header">
        <div class="search-title">Search</div>
        <div class="search-bar">
          <span class="search-icon material-symbols-rounded">search</span>
          <input class="search-input" id="search-input" type="text" 
                 placeholder="Songs, artists, albums..." autocomplete="off">
        </div>
      </div>

      ${recentSearches.length > 0 ? `
        <div class="search-recent">
          <div class="section-header" style="padding: 0; margin-bottom: var(--spacing-3);">
            <span class="section-title" style="font-size: var(--body-md);">Recent</span>
            <button class="section-action" id="btn-clear-searches">CLEAR</button>
          </div>
          <div id="recent-tags" style="display: flex; flex-wrap: wrap;"></div>
        </div>
      ` : ''}

      <div class="search-results" id="search-results"></div>
      <div id="search-loading" style="display: none;">
        <div style="padding: var(--spacing-3);">
          ${Array(5).fill('').map(() => `
            <div class="song-item">
              <div class="skeleton" style="width: 52px; height: 52px; flex-shrink: 0;"></div>
              <div class="song-item-info">
                <div class="skeleton" style="width: 70%; height: 16px; margin-bottom: 6px;"></div>
                <div class="skeleton" style="width: 40%; height: 12px;"></div>
              </div>
            </div>
          `).join('')}
        </div>
      </div>
    `;

    // Recent search tags
    if (recentSearches.length > 0) {
      const tagsContainer = $('#recent-tags');
      recentSearches.forEach(query => {
        const tag = document.createElement('button');
        tag.className = 'recent-tag';
        tag.innerHTML = `
          <span class="material-symbols-rounded" style="font-size: 14px;">history</span>
          ${escapeHtml(query)}
        `;
        tag.addEventListener('click', () => {
          const input = $('#search-input');
          if (input) input.value = query;
          App.performSearch(query);
        });
        tagsContainer.appendChild(tag);
      });
    }

    // Search input handling
    const input = $('#search-input');
    let searchTimeout;
    if (input) {
      input.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        const query = e.target.value.trim();
        if (query.length >= 2) {
          searchTimeout = setTimeout(() => App.performSearch(query), 500);
        } else {
          const results = $('#search-results');
          if (results) results.innerHTML = '';
        }
      });

      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          clearTimeout(searchTimeout);
          App.performSearch(input.value.trim());
        }
      });

      // Auto-focus
      setTimeout(() => input.focus(), 200);
    }

    bindBtn('#btn-clear-searches', () => {
      localStorage.removeItem('luminous_recent_searches');
      renderSearch();
    });
  }

  function renderSearchResults(tracks) {
    const container = $('#search-results');
    const loading = $('#search-loading');
    if (loading) loading.style.display = 'none';
    if (!container) return;

    container.innerHTML = '';

    if (tracks.length === 0) {
      container.innerHTML = `
        <div class="empty-state" style="padding: var(--spacing-8);">
          <span class="material-symbols-rounded">search_off</span>
          <span class="empty-state-text">No results found</span>
        </div>
      `;
      return;
    }

    tracks.forEach((track, i) => {
      const item = renderSongItem(track, i, {
        onClick: (t) => {
          // Play selected track; SmartQueue will auto-fill queue with related/similar songs
          Player.play(t);
          App.navigateTo('player');
        }
      });
      container.appendChild(item);
    });

  }

  function showSearchLoading() {
    const loading = $('#search-loading');
    const results = $('#search-results');
    if (loading) loading.style.display = 'block';
    if (results) results.innerHTML = '';
  }

  // ── Render: Settings Screen ────────────────────────────────
  function renderSettings() {
    const container = $('#settings-content');
    if (!container) return;

    const currentUrl = API.getBackendUrl();

    container.innerHTML = `
      <div class="settings-header">
        <div style="display: flex; align-items: center; gap: var(--spacing-3);">
          <button class="btn btn-icon" id="btn-settings-back">
            <span class="material-symbols-rounded">arrow_back</span>
          </button>
          <span class="queue-title">Settings</span>
        </div>
      </div>

      <div class="settings-group">
        <div class="settings-group-title">STREAMING MODE</div>
        <div class="settings-row" style="flex-direction: column; align-items: flex-start; gap: var(--spacing-2);">
          <span class="text-body-md">
            <span class="material-symbols-rounded" style="font-size: 18px; vertical-align: middle; color: var(--primary);">cloud</span>
            Direct Streaming (No Server Needed)
          </span>
          <span class="text-label-sm text-muted">Uses Piped API to stream directly from YouTube. Works without any backend server. Currently using: <strong style="color: var(--primary);">${Piped.getInstance().replace('https://', '')}</strong></span>
        </div>
      </div>

      <div class="settings-group" style="margin-top: var(--spacing-4);">
        <div class="settings-group-title">BACKEND SERVER (OPTIONAL)</div>
        <div class="settings-row" style="flex-direction: column; align-items: flex-start; gap: var(--spacing-2);">
          <span class="text-label-sm text-muted">Only needed if direct streaming fails. Enter your PC's IP:</span>
          <input class="settings-input" id="settings-url" type="url" 
                 value="${escapeHtml(currentUrl)}" placeholder="http://192.168.1.x:3000" style="width: 100%;">
        </div>
        <div style="padding: 0 var(--spacing-5);">
          <button class="btn btn-secondary" id="btn-save-url" style="width: 100%; padding: var(--spacing-3);">
            <span class="material-symbols-rounded" style="font-size: 18px;">save</span>
            Save & Test Connection
          </button>
        </div>
        <div id="connection-status" style="padding: var(--spacing-3) var(--spacing-5); text-align: center;"></div>
      </div>

      <div class="settings-group" style="margin-top: var(--spacing-4);">
        <div class="settings-group-title">SMART QUEUE</div>
        <div class="settings-row">
          <span class="text-body-md">Auto-fill queue</span>
          <span class="text-label-sm text-primary">Always On</span>
        </div>
        <div class="settings-row" style="flex-direction: column; align-items: flex-start; gap: var(--spacing-2);">
          <span class="text-label-sm text-muted">Automatically adds ${SmartQueue.QUEUE_SIZE} related songs. Learns your taste: fully listened songs boost similar tracks, skipped songs get deprioritized.</span>
        </div>
      </div>

      <div class="settings-group" style="margin-top: var(--spacing-4);">
        <div class="settings-group-title">ABOUT</div>
        <div class="settings-row">
          <span class="text-body-md">Version</span>
          <span class="text-label-sm text-muted">2.0.0</span>
        </div>
        <div class="settings-row">
          <span class="text-body-md">Design System</span>
          <span class="text-label-sm text-primary">LumiOne Atmosphere</span>
        </div>
        <div class="settings-row">
          <span class="text-body-md">Engine</span>
          <span class="text-label-sm text-muted">YouTube IFrame + Smart Queue</span>
        </div>
      </div>
    `;

    bindBtn('#btn-settings-back', () => App.navigateBack());
    bindBtn('#btn-save-url', async () => {
      const urlInput = $('#settings-url');
      const statusEl = $('#connection-status');
      if (!urlInput || !statusEl) return;

      const newUrl = urlInput.value.trim().replace(/\/$/, '');
      API.setBackendUrl(newUrl);
      statusEl.innerHTML = '<span class="text-label-sm text-muted">Testing connection...</span>';

      const ok = await API.checkBackend();
      if (ok) {
        statusEl.innerHTML = '<span class="text-label-sm text-primary">✓ Connected successfully!</span>';
        showToast('Backend connected');
      } else {
        statusEl.innerHTML = '<span class="text-label-sm" style="color: var(--error);">✕ Backend unreachable — direct streaming will be used instead</span>';
      }
    });
  }

  // ── Update: Mini Player ────────────────────────────────────
  function updateMiniPlayer(track, isPlaying) {
    const mini = $('#mini-player');
    if (!mini) return;

    if (!track) {
      mini.classList.add('hidden');
      return;
    }

    mini.classList.remove('hidden');
    const thumb = mini.querySelector('.mini-player-thumb');
    const title = mini.querySelector('.mini-player-title');
    const artist = mini.querySelector('.mini-player-artist');
    const playIcon = mini.querySelector('#mini-play-icon');

    if (thumb) thumb.src = track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
    if (title) title.textContent = track.title;
    if (artist) artist.textContent = track.artist || 'Unknown Artist';
    if (playIcon) playIcon.textContent = isPlaying ? 'pause' : 'play_arrow';
  }

  function updateMiniProgress(percent) {
    const fill = $('#mini-progress-fill');
    if (fill) fill.style.width = `${percent}%`;
  }

  // ── Update: Player Progress ────────────────────────────────
  function updatePlayerProgress(currentTime, duration) {
    const fill = $('#player-progress-fill');
    const timeCurrent = $('#player-time-current');
    const timeTotal = $('#player-time-total');

    if (fill && duration) {
      fill.style.width = `${(currentTime / duration) * 100}%`;
    }
    if (timeCurrent) timeCurrent.textContent = Player.formatTime(currentTime);
    if (timeTotal) timeTotal.textContent = Player.formatTime(duration);

    // Update waveform
    const bars = $$('#waveform .waveform-bar');
    if (bars.length > 0 && duration) {
      const progress = currentTime / duration;
      bars.forEach((bar, i) => {
        const barProgress = i / bars.length;
        bar.classList.toggle('played', barProgress <= progress);
        bar.classList.toggle('unplayed', barProgress > progress);
      });
    }
  }

  // ── Update: Player State ───────────────────────────────────
  function updatePlayerState(state) {
    const playIcon = $('#play-pause-icon');
    if (playIcon) playIcon.textContent = state.isPlaying ? 'pause' : 'play_arrow';

    const shuffleBtn = $('#btn-shuffle');
    if (shuffleBtn) shuffleBtn.classList.toggle('control-active', state.shuffleMode);

    const repeatBtn = $('#btn-repeat');
    if (repeatBtn) {
      repeatBtn.classList.toggle('control-active', state.repeatMode > 0);
      const icon = repeatBtn.querySelector('.material-symbols-rounded');
      if (icon) icon.textContent = state.repeatMode === 2 ? 'repeat_one' : 'repeat';
    }
  }

  // ── Update: Bottom Nav ─────────────────────────────────────
  function updateActiveNav(viewId) {
    $$('.nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.view === viewId);
    });
  }

  // ── Toast ──────────────────────────────────────────────────
  function showToast(message) {
    const toast = $('#toast');
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 2500);
  }

  // ── Helpers ────────────────────────────────────────────────
  function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  function getGreeting() {
    const hour = new Date().getHours();
    if (hour < 5) return 'Late Night';
    if (hour < 12) return 'Good Morning';
    if (hour < 17) return 'Good Afternoon';
    if (hour < 21) return 'Good Evening';
    return 'Night Vibes';
  }

  function getRecentSearches() {
    try {
      return JSON.parse(localStorage.getItem('luminous_recent_searches') || '[]');
    } catch { return []; }
  }

  function saveRecentSearch(query) {
    if (!query) return;
    const searches = getRecentSearches().filter(s => s !== query);
    searches.unshift(query);
    localStorage.setItem('luminous_recent_searches', JSON.stringify(searches.slice(0, 10)));
  }

  function bindBtn(selector, handler) {
    const el = $(selector);
    if (el) el.addEventListener('click', handler);
  }

  return {
    renderHome,
    renderPlayerView,
    renderQueue,
    renderSearch,
    renderSearchResults,
    renderSettings,
    showSearchLoading,
    updateMiniPlayer,
    updateMiniProgress,
    updatePlayerProgress,
    updatePlayerState,
    updateActiveNav,
    showToast,
    saveRecentSearch,
  };
})();

// ── Expose globally so Android native bridge can call window.UI.showToast() ──
window.UI = UI;
