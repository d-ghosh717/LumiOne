/* ═══════════════════════════════════════════════════════════════
   LUMIONE — YouTube InnerTube API + Native Bridge
   Uses Android's native HTTP client to bypass CORS
   ═══════════════════════════════════════════════════════════════ */

console.log('[YT] Loading...');

// ── Native HTTP Bridge (CORS-free) ───────────────────────────
const NativeBridge = (() => {
  const pending = {};
  let counter = 0;

  // Called by native Android code when response arrives
  window._onNativeResponse = function(id, base64Data, statusCode) {
    // Check if this is a pending piped/yt request (used by player.js fallback)
    if (window._pendingPiped && window._pendingPiped[id]) {
      try {
        var text = atob(base64Data);
        var data = JSON.parse(text);
        if (statusCode >= 200 && statusCode < 300) {
          window._pendingPiped[id].resolve(data);
        } else {
          window._pendingPiped[id].reject(new Error('HTTP ' + statusCode));
        }
      } catch (e) {
        window._pendingPiped[id].reject(e);
      }
      clearTimeout(window._pendingPiped[id].timeout);
      delete window._pendingPiped[id];
      return;
    }

    // Standard bridge response
    if (!pending[id]) return;
    try {
      const text = atob(base64Data);
      if (statusCode >= 200 && statusCode < 300) {
        pending[id].resolve(JSON.parse(text));
      } else {
        pending[id].reject(new Error('HTTP ' + statusCode));
      }
    } catch (e) {
      pending[id].reject(e);
    }
    delete pending[id];
  };

  window._onNativeError = function(id, message) {
    // Check pending piped/yt requests first
    if (window._pendingPiped && window._pendingPiped[id]) {
      clearTimeout(window._pendingPiped[id].timeout);
      window._pendingPiped[id].reject(new Error(message));
      delete window._pendingPiped[id];
      return;
    }

    if (!pending[id]) return;
    pending[id].reject(new Error(message));
    delete pending[id];
  };

  function post(url, bodyObj, extraHeaders) {
    return new Promise(function(resolve, reject) {
      var id = 'r' + (++counter);
      pending[id] = { resolve: resolve, reject: reject };

      // Timeout after 20s
      setTimeout(function() {
        if (pending[id]) {
          pending[id].reject(new Error('Native request timeout'));
          delete pending[id];
        }
      }, 20000);

      try {
        window.NativeHttp.post(id, url, JSON.stringify(bodyObj), extraHeaders ? JSON.stringify(extraHeaders) : '');
      } catch (e) {
        delete pending[id];
        reject(new Error('NativeHttp bridge unavailable: ' + e.message));
      }
    });
  }

  function get(url) {
    return new Promise(function(resolve, reject) {
      var id = 'r' + (++counter);
      pending[id] = { resolve: resolve, reject: reject };

      setTimeout(function() {
        if (pending[id]) {
          pending[id].reject(new Error('Native request timeout'));
          delete pending[id];
        }
      }, 20000);

      try {
        window.NativeHttp.get(id, url);
      } catch (e) {
        delete pending[id];
        reject(new Error('NativeHttp bridge unavailable: ' + e.message));
      }
    });
  }

  function isAvailable() {
    return !!(window.NativeHttp && window.NativeHttp.post);
  }

  function getYouTubeAudio(videoId) {
    return new Promise(function(resolve, reject) {
      var id = 'r' + (++counter);
      pending[id] = { resolve: resolve, reject: reject };

      setTimeout(function() {
        if (pending[id]) {
          pending[id].reject(new Error('YouTube extraction timeout'));
          delete pending[id];
        }
      }, 30000);

      try {
        window.NativeHttp.getYouTubeAudio(id, videoId);
      } catch (e) {
        delete pending[id];
        reject(new Error('getYouTubeAudio unavailable: ' + e.message));
      }
    });
  }

  return { post: post, get: get, isAvailable: isAvailable, getYouTubeAudio: getYouTubeAudio };
})();

console.log('[YT] Native bridge: ' + (NativeBridge.isAvailable() ? 'available ✓' : 'NOT available'));

// ── YouTube InnerTube API Client ─────────────────────────────
const Piped = (() => {
  const INNERTUBE_KEY = 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8';
  const INNERTUBE_BASE = 'https://www.youtube.com/youtubei/v1';

  const WEB_CLIENT = {
    clientName: 'WEB',
    clientVersion: '2.20240101.00.00',
    platform: 'DESKTOP',
  };

  const ANDROID_CLIENT = {
    clientName: 'ANDROID',
    clientVersion: '19.09.37',
    androidSdkVersion: 30,
    platform: 'MOBILE',
  };

  const TV_EMBED_CLIENT = {
    clientName: 'TVHTML5_SIMPLY_EMBEDDED_PLAYER',
    clientVersion: '2.0',
  };

  // ── Generic InnerTube POST ─────────────────────────────────
  async function innertubePost(endpoint, body, clientContext) {
    var ctx = clientContext || WEB_CLIENT;
    var url = INNERTUBE_BASE + '/' + endpoint + '?key=' + INNERTUBE_KEY + '&prettyPrint=false';

    var payload = { context: { client: ctx } };
    var keys = Object.keys(body);
    for (var i = 0; i < keys.length; i++) {
      payload[keys[i]] = body[keys[i]];
    }

    if (NativeBridge.isAvailable()) {
      return await NativeBridge.post(url, payload);
    }

    var res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json();
  }

  // ── Search ─────────────────────────────────────────────────
  async function search(query) {
    console.log('[YT] Searching: "' + query + '"');

    var data = await innertubePost('search', {
      query: query,
      params: 'EgWKAQIIAQ%3D%3D',
    });

    var results = [];
    var contents = null;
    try {
      contents = data.contents.twoColumnSearchResultsRenderer.primaryContents
        .sectionListRenderer.contents;
    } catch (e) {}

    if (!contents) {
      console.warn('[YT] No search contents in response');
      return [];
    }

    for (var s = 0; s < contents.length; s++) {
      var items = null;
      try { items = contents[s].itemSectionRenderer.contents; } catch (e) {}
      if (!items) continue;

      for (var j = 0; j < items.length; j++) {
        var video = items[j].videoRenderer;
        if (!video || !video.videoId) continue;

        if (video.badges) {
          var isLive = false;
          for (var b = 0; b < video.badges.length; b++) {
            var label = '';
            try { label = video.badges[b].metadataBadgeRenderer.label; } catch (e) {}
            if (label === 'LIVE' || label === 'PREMIERE') { isLive = true; break; }
          }
          if (isLive) continue;
        }

        var title = '';
        try { title = video.title.runs.map(function(r) { return r.text; }).join(''); } catch (e) {}
        var artist = '';
        try { artist = video.ownerText.runs.map(function(r) { return r.text; }).join(''); } catch (e) {}
        var durationText = '';
        try { durationText = video.lengthText.simpleText; } catch (e) {}
        var duration = parseDuration(durationText);
        var views = 0;
        try { views = parseViewCount(video.viewCountText.simpleText); } catch (e) {}
        var thumbnail = 'https://i.ytimg.com/vi/' + video.videoId + '/hqdefault.jpg';
        try {
          var thumbs = video.thumbnail.thumbnails;
          thumbnail = thumbs[thumbs.length - 1].url;
        } catch (e) {}

        results.push({
          id: video.videoId,
          title: title,
          artist: artist,
          thumbnail: thumbnail,
          duration: duration,
          durationFormatted: durationText,
          views: views,
        });
      }
    }

    console.log('[YT] Found ' + results.length + ' results');
    return results;
  }

  // ── Get Related Videos ─────────────────────────────────────
  async function getRelated(videoId) {
    console.log('[YT] Getting related for: ' + videoId);

    const pipedInstances = [
      'https://pipedapi.kavin.rocks',
      'https://pipedapi.r4fo.com',
      'https://api.piped.privacyredirect.com',
      'https://pipedapi.darkness.services',
      'https://pipedapi.in.projectsegfault.com'
    ];

    for (let i = 0; i < pipedInstances.length; i++) {
      try {
        let url = pipedInstances[i] + '/streams/' + videoId;
        console.log('[YT] Trying related from: ' + url);
        let res = await fetch(url);
        if (!res.ok) continue;
        
        let data = await res.json();
        let related = data.relatedStreams;
        if (!related || related.length === 0) continue;

        let results = [];
        for (let j = 0; j < related.length; j++) {
          let video = related[j];
          let vidId = video.url.split('v=')[1];
          if (!vidId) continue;
          
          let duration = video.duration || 0;
          if (duration > 30 && duration < 600) {
            results.push({
              id: vidId,
              title: video.title || '',
              artist: video.uploaderName || '',
              thumbnail: video.thumbnail || ('https://i.ytimg.com/vi/' + vidId + '/hqdefault.jpg'),
              duration: duration,
              durationFormatted: formatDuration(duration),
              views: video.views || 0,
              _fromRelated: true,
            });
          }
        }
        
        console.log('[YT] Related: ' + results.length + ' videos found via ' + pipedInstances[i]);
        if (results.length > 0) return results;
      } catch (err) {
        console.warn('[YT] Related failed on ' + pipedInstances[i] + ': ' + err.message);
      }
    }
    return [];
  }

  function formatDuration(seconds) {
    let m = Math.floor(seconds / 60);
    let s = seconds % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
  }


  // ── Trending ───────────────────────────────────────────────
  async function getTrending(region) {
    try {
      var results = await search('trending new songs ' + (region || 'India') + ' 2026');
      return results.slice(0, 10);
    } catch (e) {
      return [];
    }
  }

  // ── Stream Info ────────────────────────────────────────────
  async function getStreamInfo(videoId) {
    try {
      return await innertubePost('player', { videoId: videoId, contentCheckOk: true, racyCheckOk: true });
    } catch (e) {
      return null;
    }
  }

  // ── Health Check ───────────────────────────────────────────
  async function isAvailable() {
    try {
      var data = await innertubePost('search', { query: 'test' });
      return !!(data && data.contents);
    } catch (e) {
      return false;
    }
  }

  function getInstance() {
    return 'YouTube InnerTube (native bridge)';
  }

  // ── Helpers ────────────────────────────────────────────────
  function parseDuration(text) {
    if (!text) return 0;
    var parts = text.split(':');
    if (parts.length === 3) return parseInt(parts[0]) * 3600 + parseInt(parts[1]) * 60 + parseInt(parts[2]);
    if (parts.length === 2) return parseInt(parts[0]) * 60 + parseInt(parts[1]);
    return parseInt(parts[0]) || 0;
  }

  function parseViewCount(text) {
    if (!text) return 0;
    var num = text.replace(/[^0-9.]/g, '');
    if (text.indexOf('B') >= 0 || text.indexOf('b') >= 0) return parseFloat(num) * 1000000000;
    if (text.indexOf('M') >= 0 || text.indexOf('m') >= 0) return parseFloat(num) * 1000000;
    if (text.indexOf('K') >= 0 || text.indexOf('k') >= 0) return parseFloat(num) * 1000;
    return parseInt(num) || 0;
  }

  console.log('[YT] InnerTube client ready ✓');

  return {
    search: search,
    getStreamInfo: getStreamInfo,
    getRelated: getRelated,
    getTrending: getTrending,
    isAvailable: isAvailable,
    getInstance: getInstance,
  };
})();
