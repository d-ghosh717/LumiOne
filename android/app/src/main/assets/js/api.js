/* ═══════════════════════════════════════════════════════════════
   LUMIONE — API Client
   Search via YouTube InnerTube (native bridge)
   Playback via YouTube IFrame Player (no stream URLs needed)
   ═══════════════════════════════════════════════════════════════ */

const API = (() => {

  // ── Search ─────────────────────────────────────────────────
  async function search(query) {
    if (!query || !query.trim()) return [];
    var q = query.trim();

    try {
      var results = await Piped.search(q);
      if (results.length > 0) {
        console.log('[API] Search: ' + results.length + ' results');
        return results;
      }
    } catch (err) {
      console.warn('[API] Search failed: ' + err.message);
    }

    return [];
  }

  // ── Trending ───────────────────────────────────────────────
  async function getTrending() {
    try {
      var results = await Piped.getTrending('IN');
      if (results.length > 0) {
        console.log('[API] Trending: ' + results.length + ' results');
        return results;
      }
    } catch (err) {
      console.warn('[API] Trending failed: ' + err.message);
    }
    return [];
  }

  // ── Get Video Info ─────────────────────────────────────────
  async function getInfo(videoId) {
    try {
      return await Piped.getStreamInfo(videoId);
    } catch (e) {
      return null;
    }
  }

  // ── Health Check ───────────────────────────────────────────
  async function healthCheck() {
    return await Piped.isAvailable();
  }

  // ── Compatibility stubs (for any UI code that still calls these) ──
  function getBaseUrl() { return ''; }
  function setBaseUrl() {}
  function getBackendUrl() { return ''; }
  function setBackendUrl() {}
  async function getStreamUrl(videoId) { return ''; }
  async function checkBackend() { return false; }
  function getMode() { return 'youtube'; }
  function setMode() {}

  return {
    search: search,
    getTrending: getTrending,
    getInfo: getInfo,
    healthCheck: healthCheck,
    getBaseUrl: getBaseUrl,
    setBaseUrl: setBaseUrl,
    getBackendUrl: getBackendUrl,
    setBackendUrl: setBackendUrl,
    getStreamUrl: getStreamUrl,
    checkBackend: checkBackend,
    getMode: getMode,
    setMode: setMode,
  };
})();
