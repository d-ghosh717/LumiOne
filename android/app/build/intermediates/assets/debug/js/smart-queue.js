/* ═══════════════════════════════════════════════════════════════
   LUMINOUS — Smart Queue Engine
   Intelligent auto-queue with related videos, keyword matching,
   history learning, and scoring
   ═══════════════════════════════════════════════════════════════ */

const SmartQueue = (() => {
  const QUEUE_SIZE = 5;       // Always keep 5 songs queued ahead
  const STORAGE_KEY = 'luminous_smart_history';

  // ── History Data ───────────────────────────────────────────
  let history = loadHistory();

  function loadHistory() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    } catch { return {}; }
  }

  function saveHistory() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(history));
    } catch {}
  }

  function ensureHistory() {
    if (!history.listenedFully) history.listenedFully = [];   // [{id, title, artist, keywords}]
    if (!history.skipped) history.skipped = [];                // [{id, artist}]
    if (!history.playedInSession) history.playedInSession = []; // [id, id, ...]
    if (!history.artistBoosts) history.artistBoosts = {};      // {artist: score}
    if (!history.keywordBoosts) history.keywordBoosts = {};    // {keyword: score}
  }
  ensureHistory();

  // ── Track Listening Events ─────────────────────────────────
  function onTrackListenedFully(track) {
    if (!track) return;
    ensureHistory();

    // Add to fully listened
    history.listenedFully = history.listenedFully.filter(t => t.id !== track.id);
    const keywords = extractKeywords(track.title);
    history.listenedFully.unshift({ id: track.id, title: track.title, artist: track.artist, keywords });
    if (history.listenedFully.length > 100) history.listenedFully = history.listenedFully.slice(0, 100);

    // Boost artist
    const artist = normalizeArtist(track.artist);
    if (artist) {
      history.artistBoosts[artist] = (history.artistBoosts[artist] || 0) + 10;
    }

    // Boost keywords
    keywords.forEach(kw => {
      history.keywordBoosts[kw] = (history.keywordBoosts[kw] || 0) + 5;
    });

    saveHistory();
    console.log(`[SmartQ] ✓ Listened fully: ${track.title}`);
  }

  function onTrackSkipped(track) {
    if (!track) return;
    ensureHistory();

    history.skipped = history.skipped.filter(t => t.id !== track.id);
    history.skipped.unshift({ id: track.id, artist: track.artist });
    if (history.skipped.length > 50) history.skipped = history.skipped.slice(0, 50);

    // Penalize artist
    const artist = normalizeArtist(track.artist);
    if (artist) {
      history.artistBoosts[artist] = (history.artistBoosts[artist] || 0) - 5;
    }

    saveHistory();
    console.log(`[SmartQ] ✕ Skipped: ${track.title}`);
  }

  function markPlayedInSession(trackId) {
    ensureHistory();
    if (!history.playedInSession.includes(trackId)) {
      history.playedInSession.push(trackId);
    }
  }

  function clearSession() {
    ensureHistory();
    history.playedInSession = [];
  }

  // ── Score a Candidate Song ─────────────────────────────────
  function scoreSong(candidate, currentTrack, existingQueueIds = []) {
    let score = 0;
    const candidateArtist = normalizeArtist(candidate.artist);
    const currentArtist = normalizeArtist(currentTrack?.artist);
    const candidateKeywords = extractKeywords(candidate.title);

    // +50 → Same artist as current
    if (candidateArtist && currentArtist && candidateArtist === currentArtist) score += 50;

    if (currentTrack) {
      const currentKeywords = extractKeywords(currentTrack.title);
      // +30 → Similar title/keywords
      const overlap = candidateKeywords.filter(kw => currentKeywords.includes(kw));
      if (overlap.length > 0) score += 30; 
      
      // +20 → Same language/genre (inferred here broadly if large keyword overlap exists or specific keywords match)
      if (overlap.length > 1) score += 20; 
    }

    // High views/popularity → +10 to +30
    if (candidate.views > 10000000) score += 30;
    else if (candidate.views > 1000000) score += 20;
    else if (candidate.views > 100000) score += 10;

    ensureHistory();
    // Recently played → −50
    if (history.playedInSession.includes(candidate.id)) score -= 50;

    // Already in queue → −100
    if (existingQueueIds.includes(candidate.id)) score -= 100;

    return score;
  }

  // ── Get Next Songs for Auto-Queue ──────────────────────────
  async function getNextSongs(currentTrack, existingQueueIds = [], count = QUEUE_SIZE) {
    if (!currentTrack || !currentTrack.id) return [];
    console.log(`[SmartQ] Finding ${count} songs after: ${currentTrack.title}`);

    let candidates = [];
    const alreadyIds = new Set([...existingQueueIds, currentTrack.id, ...(history.playedInSession || [])]);

    // Method 1: YouTube Related Videos (Best quality)
    try {
      const related = await Piped.getRelated(currentTrack.id);
      candidates.push(...related.filter(t => !alreadyIds.has(t.id)));
      console.log(`[SmartQ] Related: ${related.length} found, ${candidates.length} new`);
    } catch (err) {
      console.warn('[SmartQ] Related fetch failed:', err.message);
    }

    // Method 2: Keyword-based search (if not enough candidates)
    if (candidates.length < count * 2) {
      try {
        const searchQueries = generateSearchQueries(currentTrack);
        for (const query of searchQueries.slice(0, 2)) {
          const results = await Piped.search(query);
          const newResults = results.filter(t => !alreadyIds.has(t.id) && !candidates.some(c => c.id === t.id));
          candidates.push(...newResults);
          if (candidates.length >= count * 3) break;
        }
        console.log(`[SmartQ] After keyword search: ${candidates.length} total candidates`);
      } catch (err) {
        console.warn('[SmartQ] Keyword search failed:', err.message);
      }
    }

    // Score all candidates
    const scored = candidates.map(c => ({
      ...c,
      _score: scoreSong(c, currentTrack, existingQueueIds),
    }));

    // Sort by score (highest first)
    scored.sort((a, b) => b._score - a._score);

    // Pick top N, remove internal score so UI doesn't try to render it explicitly or mess up equality checks
    const picked = scored.slice(0, count).map(({ _score, _fromRelated, ...track }) => track);

    // Filter to ensure no duplicates
    const uniquePicked = [];
    const seen = new Set();
    for (const track of picked) {
      if (!seen.has(track.id)) {
        seen.add(track.id);
        uniquePicked.push(track);
      }
    }

    return uniquePicked;
  }

  // ── Auto-Fill Queue ────────────────────────────────────────
  // Call this whenever the queue needs topping up
  async function autoFillQueue(currentTrack, currentQueue, currentIndex) {
    const songsAfterCurrent = currentQueue.length - currentIndex - 1;
    const needed = QUEUE_SIZE - songsAfterCurrent;

    if (needed <= 0) {
      console.log(`[SmartQ] Queue full (${songsAfterCurrent} songs ahead), no fill needed`);
      return [];
    }

    console.log(`[SmartQ] Need ${needed} more songs (${songsAfterCurrent} in queue)`);
    
    const existingIds = currentQueue.map(t => t.id);
    const newSongs = await getNextSongs(currentTrack, existingIds, needed);
    return newSongs;
  }

  // ── Generate Search Queries ────────────────────────────────
  function generateSearchQueries(track) {
    const queries = [];
    const artist = track.artist?.replace(/VEVO|Official|Channel|Music/gi, '').trim();
    const cleanTitle = cleanSongTitle(track.title);
    const keywords = extractKeywords(track.title);

    // "Songs like [title]"
    queries.push(`songs like ${cleanTitle}`);

    // "[Artist] songs"
    if (artist && artist.length > 2) {
      queries.push(`${artist} songs`);
      queries.push(`${artist} best songs`);
    }

    // Genre/mood based
    const langKeywords = keywords.filter(kw => 
      ['hindi', 'punjabi', 'tamil', 'telugu', 'english', 'bengali', 'korean', 'japanese'].includes(kw)
    );
    const moodKeywords = keywords.filter(kw => 
      ['romantic', 'sad', 'party', 'chill', 'lofi', 'workout', 'dance', 'love'].includes(kw)
    );

    if (langKeywords.length > 0 || moodKeywords.length > 0) {
      queries.push(`${[...moodKeywords, ...langKeywords].join(' ')} songs`);
    }

    return queries;
  }

  // ── Keyword Extraction ─────────────────────────────────────
  function extractKeywords(title) {
    if (!title) return [];
    const lower = title.toLowerCase();
    
    const keywords = [];
    
    // Language detection
    const langMap = {
      'hindi': ['hindi', 'bollywood', 'desi'],
      'punjabi': ['punjabi', 'panjabi'],
      'tamil': ['tamil', 'kollywood'],
      'telugu': ['telugu', 'tollywood'],
      'korean': ['korean', 'kpop', 'k-pop'],
      'english': ['english', 'pop'],
      'bengali': ['bengali', 'bangla'],
      'japanese': ['japanese', 'anime'],
    };
    for (const [lang, patterns] of Object.entries(langMap)) {
      if (patterns.some(p => lower.includes(p))) keywords.push(lang);
    }

    // Mood/genre detection
    const moods = ['romantic', 'sad', 'happy', 'love', 'party', 'dance', 'chill', 'lofi', 
                   'lo-fi', 'workout', 'gym', 'acoustic', 'unplugged', 'remix', 'mashup',
                   'slowed', 'reverb', 'bass', 'edm', 'rock', 'rap', 'hip hop', 'classical'];
    moods.forEach(mood => {
      if (lower.includes(mood)) keywords.push(mood.replace('-', ''));
    });

    // Type detection
    const types = ['lyrical', 'lyrics', 'official', 'cover', 'live', 'acoustic', 'karaoke'];
    types.forEach(type => {
      if (lower.includes(type)) keywords.push(type);
    });

    return [...new Set(keywords)];
  }

  // ── Helper: Clean Song Title ───────────────────────────────
  function cleanSongTitle(title) {
    if (!title) return '';
    return title
      .replace(/\(Official\s*(Music\s*)?Video\)/gi, '')
      .replace(/\(Official\s*Lyrical\s*Video\)/gi, '')
      .replace(/\(Lyric\s*Video\)/gi, '')
      .replace(/\[.*?\]/g, '')
      .replace(/\|.*$/, '')
      .replace(/ft\.?\s*.*/gi, '')
      .replace(/feat\.?\s*.*/gi, '')
      .replace(/-\s*(Official|Audio|HD|HQ|4K)/gi, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  // ── Helper: Normalize Artist Name ──────────────────────────
  function normalizeArtist(artist) {
    if (!artist) return '';
    return artist
      .replace(/VEVO$/i, '')
      .replace(/Official$/i, '')
      .replace(/Music$/i, '')
      .replace(/Channel$/i, '')
      .replace(/\s*-\s*Topic$/i, '')
      .trim()
      .toLowerCase();
  }

  // ── Public API ─────────────────────────────────────────────
  return {
    getNextSongs,
    autoFillQueue,
    onTrackListenedFully,
    onTrackSkipped,
    markPlayedInSession,
    clearSession,
    scoreSong,
    extractKeywords,
    cleanSongTitle,
    get QUEUE_SIZE() { return QUEUE_SIZE; },
  };
})();

// ── Expose globally ──────────────────────────────────────────
window.SmartQueue = SmartQueue;
