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
function getYtDlpPath() {
    const candidates = ['yt-dlp'];
    if (process.platform === 'win32') {
        candidates.unshift('yt-dlp.exe');
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
            try { if (fs.existsSync(p)) return p; } catch {}
        }
    }
    return candidates[0];
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

// ─── API: Search YouTube ────────────────────────────────────────
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

    console.log('[SEARCH] Running yt-dlp search...');
    const proc = spawn(ytdlp(), args);
    let output = '';
    let errorOutput = '';

    proc.stdout.on('data', (data) => { output += data.toString(); });
    proc.stderr.on('data', (data) => { errorOutput += data.toString(); });

    proc.on('close', (code) => {
        if (code !== 0) {
            console.error('[SEARCH] yt-dlp error:', errorOutput.substring(0, 300));
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
                    } catch (e) { return null; }
                })
                .filter(Boolean);
            return res.json({ results });
        } catch (e) {
            console.error('[SEARCH] Parse error:', e);
            return res.status(500).json({ error: 'Failed to parse results' });
        }
    });

    proc.on('error', (err) => {
        console.error('[SEARCH] spawn error:', err);
        return res.status(500).json({ error: 'yt-dlp not found' });
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
app.get('/api/trending', async (req, res) => {
    const query = req.query.genre || 'Hindi pop hits 2024';
    const args = [
        `ytsearch8:${query}`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--no-check-certificates',
    ];

    console.log('[TRENDING] Running yt-dlp...');
    const proc = spawn(ytdlp(), args);
    let output = '';
    let errorOutput = '';

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
        console.error('[TRENDING] spawn error:', err);
        clearTimeout(killTimer);
        if (!res.headersSent) return res.json({ results: [] });
    });
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
