const express = require('express');
const cors = require('cors');
const { exec, spawn } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');
const https = require('https');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const webPath = path.join(__dirname, '..', 'web');
app.use(express.static(webPath));

// Helper: Get yt-dlp binary path — checks PATH then common pip locations
function getYtDlpPath() {
    const candidates = [
        'yt-dlp',  // If on PATH
    ];
    if (process.platform === 'win32') {
        candidates.unshift('yt-dlp.exe');
        // Common pip install locations on Windows
        const home = process.env.USERPROFILE || process.env.HOME || '';
        const pipPaths = [
            path.join(home, 'AppData', 'Local', 'Packages', 'PythonSoftwareFoundation.Python.3.11_qbz5n2kfra8p0', 'LocalCache', 'local-packages', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Packages', 'PythonSoftwareFoundation.Python.3.12_qbz5n2kfra8p0', 'LocalCache', 'local-packages', 'Python312', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Packages', 'PythonSoftwareFoundation.Python.3.13_qbz5n2kfra8p0', 'LocalCache', 'local-packages', 'Python313', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Programs', 'Python', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Local', 'Programs', 'Python', 'Python312', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Roaming', 'Python', 'Python311', 'Scripts', 'yt-dlp.exe'),
            path.join(home, 'AppData', 'Roaming', 'Python', 'Python312', 'Scripts', 'yt-dlp.exe'),
        ];
        for (const p of pipPaths) {
            try { if (fs.existsSync(p)) { return p; } } catch {}
        }
    }
    return candidates[0];
}

// Cache the resolved yt-dlp path
let resolvedYtDlpPath = null;
function ytdlp() {
    if (!resolvedYtDlpPath) resolvedYtDlpPath = getYtDlpPath();
    return resolvedYtDlpPath;
}

// ─── API: Search YouTube ───────────────────────────────────────
app.get('/api/search', async (req, res) => {
    const query = req.query.q;
    if (!query) return res.status(400).json({ error: 'Query parameter "q" is required' });

    const args = [
        `ytsearch10:${query} song`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--default-search', 'ytsearch',
    ];

    console.log("Running yt-dlp...");
    const proc = spawn(ytdlp(), args);
    let output = '';
    let errorOutput = '';

    proc.stdout.on('data', (data) => { output += data.toString(); });
    proc.stderr.on('data', (data) => { errorOutput += data.toString(); });

    proc.on('close', (code) => {
        if (code !== 0) {
            console.error('yt-dlp search error:', errorOutput);
            return res.status(500).json({ error: 'Search failed', details: errorOutput });
        }

        try {
            const results = output
                .trim()
                .split('\n')
                .filter(line => line.trim())
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
                            url: data.url || data.webpage_url || `https://www.youtube.com/watch?v=${data.id}`,
                        };
                    } catch (e) {
                        return null;
                    }
                })
                .filter(Boolean);

            return res.json({ results });
        } catch (e) {
            console.error('Parse error:', e);
            return res.status(500).json({ error: 'Failed to parse results' });
        }
    });

    proc.on('error', (err) => {
        console.error("yt-dlp error:", err);
        return res.status(500).json({ error: 'yt-dlp not found' });
    });
});

// ─── Direct URL Cache ──────────────────────────────────────────
const urlCache = {}; // videoId -> { url, expireTime }

async function getDirectUrl(videoId) {
    if (urlCache[videoId] && urlCache[videoId].expireTime > Date.now()) {
        return urlCache[videoId].url;
    }
    return new Promise((resolve) => {
        const url = `https://www.youtube.com/watch?v=${videoId}`;
        const urlArgs = [url, '-f', 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio', '--get-url', '--no-warnings', '--no-playlist'];
        
        console.log("Running yt-dlp...");
        const urlProc = spawn(ytdlp(), urlArgs);
        let urlOutput = '';
        urlProc.stdout.on('data', (data) => { urlOutput += data.toString(); });
        urlProc.on('close', (code) => {
            const directUrl = urlOutput.trim();
            if (code === 0 && directUrl.startsWith('http')) {
                urlCache[videoId] = { url: directUrl, expireTime: Date.now() + 2 * 60 * 60 * 1000 };
                resolve(directUrl);
            } else {
                resolve(null);
            }
        });
        urlProc.on('error', (err) => {
            console.error("yt-dlp error:", err);
            resolve(null);
        });
    });
}

// ─── API: Stream Audio ──────────────────────────────────────────
// Proxies direct URL to support Range requests (seeking) and bypass CORS
app.get('/api/stream/:videoId', async (req, res) => {
    const { videoId } = req.params;
    console.log(`[STREAM] Request for: ${videoId}, Range: ${req.headers.range || 'none'}`);

    const directUrl = await getDirectUrl(videoId);
    if (!directUrl) {
        console.log(`[STREAM] Failed to get URL, falling back to pipe`);
        return pipeAudio(videoId, res);
    }

    const headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    };
    if (req.headers.range) {
        headers['Range'] = req.headers.range;
    }

    https.get(directUrl, { headers }, (proxyRes) => {
        // Forward HTTP status and headers
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res);
    }).on('error', (err) => {
        console.error('[PROXY] Error:', err);
        if (!res.headersSent) {
            return pipeAudio(videoId, res);
        }
    });
});

// ─── API: Pipe Audio (always pipe mode, no redirect) ────────────
// Used as fallback when redirect mode fails (e.g. CORS, CDN issues)
app.get('/api/pipe/:videoId', (req, res) => {
    const { videoId } = req.params;
    console.log(`[PIPE] Request for: ${videoId}`);
    return pipeAudio(videoId, res);
});

// ─── Shared pipe logic ──────────────────────────────────────────
function pipeAudio(videoId, res) {
    const url = `https://www.youtube.com/watch?v=${videoId}`;
    const pipeArgs = [
        url,
        '-f', 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
        '-o', '-',
        '--no-warnings',
        '--no-playlist',
        '--no-check-certificates',
    ];

    console.log("Running yt-dlp...");
    const pipeProc = spawn(ytdlp(), pipeArgs);
    let headersSent = false;
    let hasData = false;

    pipeProc.stdout.on('data', (chunk) => {
        if (!headersSent) {
            headersSent = true;
            hasData = true;
            // Detect format from first bytes
            const contentType = detectAudioType(chunk);
            res.setHeader('Content-Type', contentType);
            res.setHeader('Transfer-Encoding', 'chunked');
            res.setHeader('Cache-Control', 'no-cache');
            res.setHeader('Accept-Ranges', 'none');
        }
        res.write(chunk);
    });

    pipeProc.stderr.on('data', (data) => {
        const msg = data.toString();
        if (!msg.includes('[download]') && !msg.includes('Downloading')) {
            console.error('[PIPE] stderr:', msg.substring(0, 200));
        }
    });

    pipeProc.on('error', (err) => {
        console.error("yt-dlp error:", err);
        if (!headersSent) return res.status(500).json({ error: 'Stream failed' });
    });

    res.on('close', () => {
        console.log(`[PIPE] Client disconnected: ${videoId}`);
        pipeProc.kill();
    });

    pipeProc.on('close', (pipeCode) => {
        if (pipeCode !== 0 && !hasData) {
            console.error(`[PIPE] Failed with code ${pipeCode}`);
            if (!headersSent) return res.status(500).json({ error: 'Could not extract audio' });
        }
        if (headersSent) res.end();
        console.log(`[PIPE] Stream ended for: ${videoId}`);
    });
}

// Detect audio MIME type from first bytes
function detectAudioType(buffer) {
    if (buffer.length >= 4) {
        // Check for WebM magic bytes (0x1A 0x45 0xDF 0xA3)
        if (buffer[0] === 0x1A && buffer[1] === 0x45 && buffer[2] === 0xDF && buffer[3] === 0xA3) {
            return 'audio/webm';
        }
        // Check for MP4/M4A (ftyp box)
        if (buffer.length >= 8) {
            const ftyp = buffer.slice(4, 8).toString('ascii');
            if (ftyp === 'ftyp') return 'audio/mp4';
        }
        // Check for Ogg
        if (buffer.slice(0, 4).toString('ascii') === 'OggS') return 'audio/ogg';
    }
    // Default fallback
    return 'audio/mp4';
}

// ─── API: Get Video Info ────────────────────────────────────────
app.get('/api/info/:videoId', async (req, res) => {
    const { videoId } = req.params;
    const url = `https://www.youtube.com/watch?v=${videoId}`;

    const args = [url, '--dump-json', '--no-warnings', '--no-playlist'];
    
    console.log("Running yt-dlp...");
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
        console.error("yt-dlp error:", err);
        if (!res.headersSent) return res.status(500).json({ error: 'yt-dlp not found' });
    });
});

// ─── API: Trending ──────────────────────────────────────────────
app.get('/api/trending', async (req, res) => {
    const query = req.query.genre || 'Hindi pop hits 2024';
    const args = [
        `ytsearch8:${query}`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--no-check-certificates',
    ];

    console.log("Running yt-dlp...");
    const proc = spawn(ytdlp(), args);
    let output = '';
    let errorOutput = '';

    // Kill after 20s to avoid hanging
    const killTimer = setTimeout(() => {
        proc.kill();
        if (!res.headersSent) return res.json({ results: [] });
    }, 20000);

    proc.stdout.on('data', (data) => { output += data.toString(); });
    proc.stderr.on('data', (data) => { errorOutput += data.toString(); });

    proc.on('close', (code) => {
        clearTimeout(killTimer);
        if (res.headersSent) return;
        try {
            const results = output.trim().split('\n')
                .filter(l => l.trim())
                .map(line => {
                    try {
                        const data = JSON.parse(line);
                        return {
                            id: data.id,
                            title: data.title || 'Unknown',
                            artist: data.channel || data.uploader || 'Unknown',
                            thumbnail: `https://i.ytimg.com/vi/${data.id}/hqdefault.jpg`,
                            duration: data.duration || 0,
                            durationFormatted: formatDuration(data.duration),
                        };
                    } catch { return null; }
                })
                .filter(Boolean);
            return res.json({ results });
        } catch (e) {
            return res.json({ results: [] });
        }
    });

    proc.on('error', (err) => {
        console.error("yt-dlp error:", err);
        clearTimeout(killTimer);
        if (!res.headersSent) return res.json({ results: [] });
    });
});

// ─── API: Health Check ──────────────────────────────────────────
app.get('/api/health', (req, res) => {
    return res.json({ status: 'ok', ytdlp: ytdlp(), timestamp: new Date().toISOString() });
});

// ─── Helper ─────────────────────────────────────────────────────
function formatDuration(seconds) {
    if (!seconds) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

// ─── Start Server ───────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
});
