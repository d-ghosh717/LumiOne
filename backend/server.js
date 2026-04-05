const express = require('express');
const cors = require('cors');
const { exec, spawn } = require('child_process');
const path = require('path');
const os = require('os');

const app = express();
const PORT = 3000;

app.use(cors());
app.use(express.json());

// Serve frontend assets for browser testing
const assetsPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets');
app.use(express.static(assetsPath));

// Helper: Get yt-dlp binary path — checks PATH then common pip locations
function getYtDlpPath() {
    const fs = require('fs');
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
            try { if (fs.existsSync(p)) { console.log(`[yt-dlp] Found at: ${p}`); return p; } } catch {}
        }
    }
    return candidates[0];
}

// ─── API: Search YouTube ───────────────────────────────────────
app.get('/api/search', async (req, res) => {
    const query = req.query.q;
    if (!query) return res.status(400).json({ error: 'Query parameter "q" is required' });

    const ytdlp = getYtDlpPath();
    const args = [
        `ytsearch10:${query} song`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--default-search', 'ytsearch',
    ];

    const proc = spawn(ytdlp, args);
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

            res.json({ results });
        } catch (e) {
            console.error('Parse error:', e);
            res.status(500).json({ error: 'Failed to parse results' });
        }
    });

    proc.on('error', (err) => {
        console.error('yt-dlp spawn error:', err);
        res.status(500).json({ error: 'yt-dlp not found. Install it: pip install yt-dlp' });
    });
});

// ─── API: Stream Audio ─────────────────────────────────────────
// Strategy: Get direct audio URL from YouTube, redirect client to it (fastest)
// Fallback: Pipe through yt-dlp if URL extraction fails
app.get('/api/stream/:videoId', async (req, res) => {
    const { videoId } = req.params;
    const url = `https://www.youtube.com/watch?v=${videoId}`;
    const ytdlp = getYtDlpPath();

    console.log(`[STREAM] Request for: ${videoId}`);

    // Phase 1: Try to get direct audio URL (fastest — client fetches directly)
    const urlArgs = [
        url,
        '-f', 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
        '--get-url',
        '--no-warnings',
        '--no-playlist',
        '--no-check-certificates',
    ];

    const urlProc = spawn(ytdlp, urlArgs);
    let urlOutput = '';
    let urlError = '';

    urlProc.stdout.on('data', (data) => { urlOutput += data.toString(); });
    urlProc.stderr.on('data', (data) => { urlError += data.toString(); });

    urlProc.on('close', (code) => {
        const directUrl = urlOutput.trim();
        
        if (code === 0 && directUrl && directUrl.startsWith('http')) {
            console.log(`[STREAM] Got direct URL for ${videoId}, redirecting`);
            // Redirect client to the direct YouTube audio URL
            return res.redirect(directUrl);
        }

        console.log(`[STREAM] URL extraction failed (code: ${code}), falling back to pipe mode`);
        if (urlError) console.error('[STREAM] stderr:', urlError.substring(0, 300));

        // Phase 2: Fallback — pipe raw audio through yt-dlp
        const pipeArgs = [
            url,
            '-f', 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
            '-o', '-',
            '--no-warnings',
            '--no-playlist',
            '--no-check-certificates',
        ];

        const pipeProc = spawn(ytdlp, pipeArgs);

        res.setHeader('Content-Type', 'audio/webm');
        res.setHeader('Transfer-Encoding', 'chunked');
        res.setHeader('Cache-Control', 'no-cache');

        pipeProc.stdout.pipe(res);

        pipeProc.stderr.on('data', (data) => {
            const msg = data.toString();
            if (!msg.includes('[download]') && !msg.includes('Downloading')) {
                console.error('[STREAM] pipe stderr:', msg.substring(0, 200));
            }
        });

        pipeProc.on('error', (err) => {
            console.error('[STREAM] pipe error:', err.message);
            if (!res.headersSent) res.status(500).json({ error: 'Stream failed' });
        });

        res.on('close', () => {
            console.log(`[STREAM] Client disconnected: ${videoId}`);
            pipeProc.kill();
        });

        pipeProc.on('close', (pipeCode) => {
            if (pipeCode !== 0) console.error(`[STREAM] pipe exited with code ${pipeCode}`);
            console.log(`[STREAM] Stream ended for: ${videoId}`);
        });
    });

    urlProc.on('error', (err) => {
        console.error('[STREAM] yt-dlp spawn error:', err.message);
        if (!res.headersSent) {
            res.status(500).json({ error: 'yt-dlp not found. Install: pip install yt-dlp' });
        }
    });
});

// ─── API: Get Video Info ────────────────────────────────────────
app.get('/api/info/:videoId', async (req, res) => {
    const { videoId } = req.params;
    const url = `https://www.youtube.com/watch?v=${videoId}`;
    const ytdlp = getYtDlpPath();

    const args = [url, '--dump-json', '--no-warnings', '--no-playlist'];
    const proc = spawn(ytdlp, args);
    let output = '';

    proc.stdout.on('data', (data) => { output += data.toString(); });

    proc.on('close', (code) => {
        if (code !== 0) return res.status(500).json({ error: 'Failed to get info' });

        try {
            const data = JSON.parse(output);
            res.json({
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
            res.status(500).json({ error: 'Failed to parse info' });
        }
    });
});

// ─── API: Trending ──────────────────────────────────────────────
// Uses ytsearch for speed — playlist fetching is too slow
app.get('/api/trending', async (req, res) => {
    const ytdlp = getYtDlpPath();
    const query = req.query.genre || 'Hindi pop hits 2024';
    const args = [
        `ytsearch8:${query}`,
        '--dump-json',
        '--flat-playlist',
        '--no-warnings',
        '--no-check-certificates',
    ];

    const proc = spawn(ytdlp, args);
    let output = '';
    let errorOutput = '';

    // Kill after 20s to avoid hanging
    const killTimer = setTimeout(() => {
        proc.kill();
        if (!res.headersSent) res.json({ results: [] });
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
            res.json({ results });
        } catch (e) {
            res.json({ results: [] });
        }
    });

    proc.on('error', (err) => {
        clearTimeout(killTimer);
        if (!res.headersSent) res.json({ results: [] });
    });
});

// ─── API: Health Check ──────────────────────────────────────────
app.get('/api/health', (req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// ─── Helper ─────────────────────────────────────────────────────
function formatDuration(seconds) {
    if (!seconds) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

// ─── Get local IP for display ───────────────────────────────────
function getLocalIP() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                return iface.address;
            }
        }
    }
    return '127.0.0.1';
}

// ─── Start Server ───────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
    const localIP = getLocalIP();
    console.log('');
    console.log('╔══════════════════════════════════════════════════╗');
    console.log('║       🎵 Luminous Player Backend v1.0           ║');
    console.log('╠══════════════════════════════════════════════════╣');
    console.log(`║  Local:   http://localhost:${PORT}                  ║`);
    console.log(`║  Network: http://${localIP}:${PORT}            ║`);
    console.log('║                                                  ║');
    console.log('║  Use the Network URL in the Android app!         ║');
    console.log('╚══════════════════════════════════════════════════╝');
    console.log('');
});
