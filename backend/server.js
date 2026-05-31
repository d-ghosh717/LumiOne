const express = require('express');
const cors = require('cors');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const webPath = path.join(__dirname, '..', 'web');
app.use(express.static(webPath));

// ─── Helper: Get yt-dlp binary path ────────────────────────────
// Priority order:
//   1. ./bin/yt-dlp  — downloaded by Render build command via curl (no pip needed)
//   2. system PATH   — works locally if yt-dlp is installed globally
//   3. Windows pip install locations — for local Windows dev
function getYtDlpPath() {
    // 1. Check local ./bin/ directory first (Render deployment)
    const localBin = path.join(__dirname, 'bin', process.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp');
    try { if (fs.existsSync(localBin)) { console.log('[yt-dlp] Using local binary:', localBin); return localBin; } } catch {}

    // 2. Windows: check common pip install locations for local dev
    if (process.platform === 'win32') {
        const home = process.env.USERPROFILE || process.env.HOME || '';
        const pipPaths = [
            path.join(home, 'AppData', 'Local', 'Packages', 'PythonSoftwareFoundation.Python.3.11_qbz5n2kfra8p0', 'LocalCache', 'local-packages', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Packages', 'PythonSoftwareFoundation.Python.3.12_qbz5n2kfra8p0', 'LocalCache', 'local-packages', 'Python312', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Programs', 'Python', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Programs', 'Python', 'Python312', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Roaming', 'Python', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Roaming', 'Python', 'Python312', 'Scripts', 'yt-dlp.exe'),
        ];
        for (const p of pipPaths) {
            try { if (fs.existsSync(p)) { return p; } } catch {}
        }
    }

    // 3. Fall back to system PATH
    return 'yt-dlp';
}

let resolvedYtDlpPath = null;
function ytdlp() {
    if (!resolvedYtDlpPath) resolvedYtDlpPath = getYtDlpPath();
    return resolvedYtDlpPath;
}

// ─── Common yt-dlp args for bypassing bot detection ────────────
// These args make yt-dlp use the Android YouTube client which is
// less restricted and works from cloud/datacenter IPs on Render.
function getBotBypassArgs() {
    return [
        '--extractor-args', 'youtube:player_client=android,web',
        '--no-check-certificates',
        '--no-warnings',
        '--no-playlist',
    ];
}

// ─── Invidious API Search ───────────────────────────────────────
// Invidious is an open-source YouTube frontend with a public JSON API.
// It works from cloud/datacenter IPs — unlike yt-dlp ytsearch which
// gets throttled/blocked by YouTube on Render's servers.
//
// We try multiple public instances in order; if one is down we move on.
const https = require('https');

const HARDCODED_INVIDIOUS = [
    'https://inv.nadeko.net',
    'https://invidious.nerdvpn.de',
    'https://invidious.f5.si',
    'https://yt.chocolatemoo53.com',
    'https://inv.thepixora.com',
];

function getHealthyInvidiousInstances() {
    return new Promise((resolve) => {
        const req = https.get('https://api.invidious.io/instances.json', { timeout: 3000 }, (res) => {
            let raw = '';
            res.on('data', chunk => { raw += chunk; });
            res.on('end', () => {
                try {
                    const data = JSON.parse(raw);
                    const active = data
                        .filter(([_, info]) => info.type === 'https' && info.monitor && info.monitor.down === false && info.monitor.last_status === 200)
                        .map(([_, info]) => info.uri);
                    if (active.length > 0) {
                        console.log(`[INSTANCES] Loaded ${active.length} active Invidious instances from registry`);
                        resolve(active);
                        return;
                    }
                } catch {}
                resolve(HARDCODED_INVIDIOUS);
            });
        });
        req.on('error', () => resolve(HARDCODED_INVIDIOUS));
        req.on('timeout', () => { req.destroy(); resolve(HARDCODED_INVIDIOUS); });
    });
}

function invidiousSearch(query, instances, maxResults = 10) {
    return new Promise((resolve) => {
        const encoded = encodeURIComponent(query);
        let tried = 0;

        function tryNext() {
            if (tried >= instances.length) {
                resolve(null); // all instances exhausted
                return;
            }
            const instance = instances[tried++];
            const url = `${instance}/api/v1/search?q=${encoded}&type=video&fields=videoId,title,author,lengthSeconds,videoThumbnails`;
            console.log(`[SEARCH] Trying Invidious: ${instance}`);

            const req = https.get(url, { timeout: 8000 }, (res) => {
                let raw = '';
                res.on('data', chunk => { raw += chunk; });
                res.on('end', () => {
                    try {
                        const items = JSON.parse(raw);
                        if (!Array.isArray(items) || items.length === 0) { tryNext(); return; }
                        const results = items.slice(0, maxResults).map(v => ({
                            id: v.videoId,
                            title: v.title || 'Unknown Title',
                            artist: v.author || 'Unknown Artist',
                            thumbnail: (() => {
                                const thumbs = v.videoThumbnails || [];
                                const med = thumbs.find(t => t.quality === 'medium' || t.quality === 'high');
                                return (med || thumbs[0])?.url || `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`;
                            })(),
                            duration: v.lengthSeconds || 0,
                            durationFormatted: formatDuration(v.lengthSeconds),
                            views: v.viewCount || 0,
                            url: `https://www.youtube.com/watch?v=${v.videoId}`,
                        }));
                        console.log(`[SEARCH] Invidious OK: ${instance} (${results.length} results)`);
                        resolve(results);
                    } catch {
                        tryNext();
                    }
                });
            });
            req.on('error', (err) => {
                console.log(`[SEARCH] Invidious failed (${instance}): ${err.message}`);
                tryNext();
            });
            req.on('timeout', () => {
                req.destroy();
                console.log(`[SEARCH] Invidious timeout: ${instance}`);
                tryNext();
            });
        }

        tryNext();
    });
}

const PIPED_INSTANCES = [
    'https://api.piped.private.coffee',
    'https://pipedapi.leptons.xyz',
    'https://pipedapi-libre.kavin.rocks',
];

function pipedSearch(query, maxResults = 10) {
    return new Promise((resolve) => {
        const encoded = encodeURIComponent(query);
        let tried = 0;

        function tryNext() {
            if (tried >= PIPED_INSTANCES.length) {
                resolve(null); // all instances exhausted
                return;
            }
            const instance = PIPED_INSTANCES[tried++];
            const url = `${instance}/search?q=${encoded}&filter=videos`;
            console.log(`[SEARCH] Trying Piped: ${instance}`);

            const req = https.get(url, { timeout: 8000 }, (res) => {
                let raw = '';
                res.on('data', chunk => { raw += chunk; });
                res.on('end', () => {
                    try {
                        const data = JSON.parse(raw);
                        const items = data.items || [];
                        if (items.length === 0) { tryNext(); return; }
                        const results = items
                            .filter(v => v.type === 'stream')
                            .slice(0, maxResults)
                            .map(v => {
                                let videoId = '';
                                if (v.url) {
                                    const match = v.url.match(/[?&]v=([^&#]+)/) || v.url.match(/\/watch\?v=([^&#]+)/);
                                    videoId = match ? match[1] : v.url.replace('/watch?v=', '');
                                }
                                return {
                                    id: videoId,
                                    title: v.title || 'Unknown Title',
                                    artist: v.uploaderName || 'Unknown Artist',
                                    thumbnail: v.thumbnail || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                                    duration: v.duration || 0,
                                    durationFormatted: formatDuration(v.duration),
                                    views: v.views || 0,
                                    url: `https://www.youtube.com/watch?v=${videoId}`,
                                };
                            });
                        console.log(`[SEARCH] Piped OK: ${instance} (${results.length} results)`);
                        resolve(results);
                    } catch {
                        tryNext();
                    }
                });
            });
            req.on('error', (err) => {
                console.log(`[SEARCH] Piped failed (${instance}): ${err.message}`);
                tryNext();
            });
            req.on('timeout', () => {
                req.destroy();
                console.log(`[SEARCH] Piped timeout: ${instance}`);
                tryNext();
            });
        }

        tryNext();
    });
}

// ─── API: Search YouTube ────────────────────────────────────────
app.get('/api/search', async (req, res) => {
    const query = req.query.q;
    if (!query) return res.status(400).json({ error: 'Query parameter "q" is required' });

    console.log(`[SEARCH] Query: "${query}"`);

    // 1. Try Invidious API (fast, works on cloud IPs, no yt-dlp needed)
    const invInstances = await getHealthyInvidiousInstances();
    const invResults = await invidiousSearch(query, invInstances);
    if (invResults && invResults.length > 0) {
        return res.json({ results: invResults });
    }

    // 2. Fallback: Piped API
    console.log('[SEARCH] Invidious failed, falling back to Piped...');
    const pipedResults = await pipedSearch(query);
    if (pipedResults && pipedResults.length > 0) {
        return res.json({ results: pipedResults });
    }

    // 3. Fallback: yt-dlp ytsearch (slower, may fail on cloud IPs but worth trying)
    console.log('[SEARCH] Piped failed, falling back to yt-dlp...');
    const args = [
        `ytsearch10:${query} song`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--default-search', 'ytsearch',
        '--extractor-args', 'youtube:player_client=android,web',
        '--no-check-certificates',
    ];

    const proc = spawn(ytdlp(), args);
    let output = '';
    let errorOutput = '';
    let done = false;

    // Kill after 25s to avoid Render request timeout
    const killTimer = setTimeout(() => {
        if (!done) { proc.kill(); if (!res.headersSent) res.status(504).json({ error: 'Search timed out' }); }
    }, 25000);

    proc.stdout.on('data', (data) => { output += data.toString(); });
    proc.stderr.on('data', (data) => { errorOutput += data.toString(); });

    proc.on('close', (code) => {
        done = true;
        clearTimeout(killTimer);
        if (res.headersSent) return;
        if (code !== 0) {
            console.error('[SEARCH] yt-dlp fallback error:', errorOutput.substring(0, 300));
            return res.status(500).json({ error: 'Search failed' });
        }
        try {
            const results = output.trim().split('\n')
                .filter(l => l.trim())
                .map(line => {
                    try {
                        const data = JSON.parse(line);
                        return {
                            id: data.id,
                            title: data.title || 'Unknown Title',
                            artist: data.channel || data.uploader || 'Unknown Artist',
                            thumbnail: data.thumbnails
                                ? (data.thumbnails.find(t => t.width >= 300) || data.thumbnails[data.thumbnails.length - 1])?.url
                                : `https://i.ytimg.com/vi/${data.id}/hqdefault.jpg`,
                            duration: data.duration || 0,
                            durationFormatted: formatDuration(data.duration),
                            views: data.view_count || 0,
                            url: `https://www.youtube.com/watch?v=${data.id}`,
                        };
                    } catch { return null; }
                })
                .filter(Boolean);
            return res.json({ results });
        } catch (e) {
            return res.status(500).json({ error: 'Failed to parse results' });
        }
    });

    proc.on('error', (err) => {
        done = true;
        clearTimeout(killTimer);
        console.error('[SEARCH] spawn error:', err);
        if (!res.headersSent) return res.status(500).json({ error: 'yt-dlp not found' });
    });
});


// ─── API: Stream Audio (pipe mode — works on Render) ────────────
// We always pipe through yt-dlp instead of redirecting to the CDN URL.
// Reason: YouTube CDN URLs are IP-bound. Render runs on cloud IPs that
// YouTube blocks for direct streaming. yt-dlp uses the android player
// client which bypasses these restrictions.
app.get('/api/stream/:videoId', (req, res) => {
    const { videoId } = req.params;
    console.log(`[STREAM] Request for: ${videoId}`);
    return pipeAudio(videoId, req, res);
});

// ─── API: Pipe endpoint (legacy, same as stream now) ────────────
app.get('/api/pipe/:videoId', (req, res) => {
    const { videoId } = req.params;
    console.log(`[PIPE] Request for: ${videoId}`);
    return pipeAudio(videoId, req, res);
});

// ─── Core pipe logic ────────────────────────────────────────────
function pipeAudio(videoId, req, res) {
    const url = `https://www.youtube.com/watch?v=${videoId}`;

    // Format selection: prefer opus/webm (smallest, fastest start)
    // fall back to m4a, then any best audio
    const formatSelector = 'bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio';

    const args = [
        url,
        '-f', formatSelector,
        '-o', '-',              // output to stdout
        '--extractor-args', 'youtube:player_client=android,web',
        '--no-check-certificates',
        '--no-warnings',
        '--no-playlist',
        '--buffer-size', '16K',
    ];

    console.log(`[PIPE] Starting yt-dlp for: ${videoId}`);
    const proc = spawn(ytdlp(), args);
    let headersSent = false;
    let hasData = false;
    let finished = false;

    proc.stdout.on('data', (chunk) => {
        if (finished) return;
        if (!headersSent) {
            headersSent = true;
            hasData = true;
            const contentType = detectAudioType(chunk);
            console.log(`[PIPE] Detected content type: ${contentType} for ${videoId}`);
            res.setHeader('Content-Type', contentType);
            res.setHeader('Transfer-Encoding', 'chunked');
            res.setHeader('Cache-Control', 'no-cache, no-store');
            res.setHeader('Accept-Ranges', 'none');
            res.setHeader('X-Content-Type-Options', 'nosniff');
        }
        if (!res.writableEnded) {
            res.write(chunk);
        }
    });

    proc.stderr.on('data', (data) => {
        const msg = data.toString();
        // Only log non-progress lines
        if (!msg.includes('[download]') && !msg.includes('ETA') && !msg.includes('%')) {
            console.error(`[PIPE] stderr (${videoId}):`, msg.substring(0, 200));
        }
    });

    proc.on('error', (err) => {
        console.error('[PIPE] spawn error:', err);
        if (!headersSent && !res.headersSent) {
            res.status(500).json({ error: 'yt-dlp not found or failed to start' });
        }
        finished = true;
    });

    // Kill yt-dlp if client disconnects
    req.on('close', () => {
        if (!finished) {
            console.log(`[PIPE] Client disconnected: ${videoId} — killing yt-dlp`);
            finished = true;
            proc.kill('SIGKILL');
        }
    });

    proc.on('close', (code) => {
        finished = true;
        if (!hasData) {
            console.error(`[PIPE] yt-dlp exited with code ${code} and no data for: ${videoId}`);
            if (!headersSent && !res.headersSent) {
                return res.status(500).json({ error: 'Could not extract audio — video may be unavailable or geo-blocked' });
            }
        } else {
            console.log(`[PIPE] Stream complete for: ${videoId} (exit code: ${code})`);
        }
        if (!res.writableEnded) {
            res.end();
        }
    });
}

// ─── Detect audio MIME from first bytes ─────────────────────────
function detectAudioType(buffer) {
    if (buffer.length >= 4) {
        // WebM magic bytes: 0x1A 0x45 0xDF 0xA3
        if (buffer[0] === 0x1A && buffer[1] === 0x45 && buffer[2] === 0xDF && buffer[3] === 0xA3) {
            return 'audio/webm; codecs=opus';
        }
        // MP4/M4A: check for 'ftyp' box at offset 4
        if (buffer.length >= 8) {
            const ftyp = buffer.slice(4, 8).toString('ascii');
            if (ftyp === 'ftyp') return 'audio/mp4';
        }
        // Ogg
        if (buffer.slice(0, 4).toString('ascii') === 'OggS') return 'audio/ogg';
    }
    return 'audio/webm; codecs=opus'; // Default: most likely on Render
}

// ─── API: Get Video Info ─────────────────────────────────────────
app.get('/api/info/:videoId', async (req, res) => {
    const { videoId } = req.params;
    const url = `https://www.youtube.com/watch?v=${videoId}`;

    const args = [
        url,
        '--dump-json',
        '--no-warnings',
        '--no-playlist',
        '--extractor-args', 'youtube:player_client=android,web',
        '--no-check-certificates',
    ];

    console.log(`[INFO] Fetching info for: ${videoId}`);
    const proc = spawn(ytdlp(), args);
    let output = '';

    proc.stdout.on('data', (data) => { output += data.toString(); });

    proc.on('close', (code) => {
        if (code !== 0) return res.status(500).json({ error: 'Failed to get info' });
        try {
            const data = JSON.parse(output);
            return res.json({
                id: data.id,
                title: data.title,
                artist: data.channel || data.uploader,
                thumbnail: data.thumbnail || `https://i.ytimg.com/vi/${data.id}/maxresdefault.jpg`,
                duration: data.duration,
                durationFormatted: formatDuration(data.duration),
                views: data.view_count,
                description: data.description?.substring(0, 200),
            });
        } catch (e) {
            return res.status(500).json({ error: 'Failed to parse info' });
        }
    });

    proc.on('error', (err) => {
        console.error('[INFO] spawn error:', err);
        if (!res.headersSent) return res.status(500).json({ error: 'yt-dlp not found' });
    });
});

// ─── API: Trending ───────────────────────────────────────────────
// Uses Invidious trending (music category) instead of yt-dlp search,
// which gets blocked from cloud IPs.
app.get('/api/trending', async (req, res) => {
    const genre = req.query.genre || null;
    console.log('[TRENDING] Fetching via Invidious...');

    const invInstances = await getHealthyInvidiousInstances();

    // Try Invidious trending endpoint first (music category, India region)
    if (!genre) {
        for (const instance of invInstances) {
            try {
                const results = await new Promise((resolve, reject) => {
                    const url = `${instance}/api/v1/trending?type=music&region=IN&fields=videoId,title,author,lengthSeconds,videoThumbnails`;
                    const req2 = https.get(url, { timeout: 8000 }, (r) => {
                        let raw = '';
                        r.on('data', c => { raw += c; });
                        r.on('end', () => {
                            try {
                                const items = JSON.parse(raw);
                                if (!Array.isArray(items) || !items.length) { reject(new Error('empty')); return; }
                                resolve(items.slice(0, 12).map(v => ({
                                    id: v.videoId,
                                    title: v.title || 'Unknown',
                                    artist: v.author || 'Unknown',
                                    thumbnail: (() => {
                                        const thumbs = v.videoThumbnails || [];
                                        const t = thumbs.find(t => t.quality === 'medium') || thumbs[0];
                                        return t?.url || `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`;
                                    })(),
                                    duration: v.lengthSeconds || 0,
                                    durationFormatted: formatDuration(v.lengthSeconds),
                                })));
                            } catch { reject(new Error('parse error')); }
                        });
                    });
                    req2.on('error', reject);
                    req2.on('timeout', () => { req2.destroy(); reject(new Error('timeout')); });
                });
                console.log(`[TRENDING] OK from ${instance}: ${results.length} items`);
                return res.json({ results });
            } catch (err) {
                console.log(`[TRENDING] Instance ${instance} failed: ${err.message}`);
            }
        }
    }

    // Fallback: use invidiousSearch with a genre query
    const query = genre || 'top Hindi pop songs 2024';
    console.log(`[TRENDING] Falling back to Invidious search: "${query}"`);
    const results = await invidiousSearch(query, invInstances, 12);
    if (results && results.length > 0) {
        return res.json({ results });
    }

    // Last resort: return empty (better than hanging)
    console.error('[TRENDING] All sources failed, returning empty');
    return res.json({ results: [] });
});


// ─── API: Health Check ───────────────────────────────────────────
app.get('/api/health', (req, res) => {
    return res.json({
        status: 'ok',
        ytdlp: ytdlp(),
        platform: process.platform,
        timestamp: new Date().toISOString()
    });
});

// ─── Helper ─────────────────────────────────────────────────────
function formatDuration(seconds) {
    if (!seconds) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

// ─── Start Server ────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
    console.log(`LumiOne backend running on port ${PORT}`);
    console.log(`yt-dlp path: ${ytdlp()}`);
});
