const express = require('express');
const cors = require('cors');
const path = require('path');
const https = require('https');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const webPath = path.join(__dirname, '..', 'web');
app.use(express.static(webPath));

// ─── Invidious API Search ───────────────────────────────────────
const HARDCODED_INVIDIOUS = [
    'https://invidious.f5.si',
    'https://invidious.nerdvpn.de',
    'https://inv.thepixora.com',
    'https://yt.chocolatemoo53.com',
    'https://inv.nadeko.net',
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

function invidiousSearch(query, instances, maxResults = 15) {
    return new Promise((resolve) => {
        const encoded = encodeURIComponent(query);
        let tried = 0;

        function tryNext() {
            if (tried >= instances.length) {
                resolve(null);
                return;
            }
            const instance = instances[tried++];
            const url = `${instance}/api/v1/search?q=${encoded}&type=video&fields=videoId,title,author,lengthSeconds,videoThumbnails,viewCount`;
            console.log(`[SEARCH] Trying Invidious: ${instance}`);

            const req = https.get(url, { timeout: 7000 }, (res) => {
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
    const invInstances = await getHealthyInvidiousInstances();
    const invResults = await invidiousSearch(query, invInstances);
    if (invResults && invResults.length > 0) {
        return res.json({ results: invResults });
    }

    return res.json({ results: [] });
});

// ─── API: Trending ───────────────────────────────────────────────
app.get('/api/trending', async (req, res) => {
    console.log('[TRENDING] Fetching trending music...');
    const invInstances = await getHealthyInvidiousInstances();

    for (const instance of invInstances) {
        try {
            const results = await new Promise((resolve, reject) => {
                const url = `${instance}/api/v1/trending?type=music&region=US&fields=videoId,title,author,lengthSeconds,videoThumbnails`;
                const req2 = https.get(url, { timeout: 6000 }, (r) => {
                    let raw = '';
                    r.on('data', c => { raw += c; });
                    r.on('end', () => {
                        try {
                            const items = JSON.parse(raw);
                            if (!Array.isArray(items) || !items.length) { reject(new Error('empty')); return; }
                            resolve(items.slice(0, 15).map(v => ({
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
            return res.json({ results });
        } catch (err) {
            console.log(`[TRENDING] Instance ${instance} failed: ${err.message}`);
        }
    }

    // Fallback search
    const results = await invidiousSearch('top hit songs 2026', invInstances, 12);
    return res.json({ results: results || [] });
});

// ─── API: Health Check ───────────────────────────────────────────
app.get('/api/health', (req, res) => {
    return res.json({
        status: 'ok',
        mode: 'youtube_iframe_metadata',
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

app.listen(PORT, '0.0.0.0', () => {
    console.log(`LumiOne metadata service running on port ${PORT}`);
});
