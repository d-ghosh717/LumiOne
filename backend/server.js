const express = require('express');
const cors = require('cors');
const path = require('path');
const https = require('https');
const admin = require('firebase-admin');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// ─── Firebase Admin Setup ───────────────────────────────────────
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || 'lumione-1278a';

try {
    if (!admin.apps.length) {
        if (process.env.FIREBASE_SERVICE_ACCOUNT) {
            const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
            admin.initializeApp({
                credential: admin.credential.cert(serviceAccount),
                projectId: FIREBASE_PROJECT_ID
            });
            console.log(`[FIREBASE] Initialized with Service Account for ${FIREBASE_PROJECT_ID}`);
        } else {
            admin.initializeApp({
                projectId: FIREBASE_PROJECT_ID
            });
            console.log(`[FIREBASE] Initialized with Project ID: ${FIREBASE_PROJECT_ID}`);
        }
    }
} catch (err) {
    console.error('[FIREBASE] Initialization error:', err.message);
}

// ─── Authentication Middleware ─────────────────────────────────
async function requireFirebaseAuth(req, res, next) {
    const authHeader = req.headers.authorization || '';
    if (!authHeader.startsWith('Bearer ')) {
        return res.status(401).json({
            error: 'Unauthorized',
            message: 'Authentication required. Missing or invalid Authorization header (expected Bearer <firebase_id_token>).'
        });
    }

    const idToken = authHeader.split('Bearer ')[1].trim();
    if (!idToken) {
        return res.status(401).json({
            error: 'Unauthorized',
            message: 'Empty Firebase ID token provided.'
        });
    }

    try {
        const decodedToken = await admin.auth().verifyIdToken(idToken);
        req.user = decodedToken;
        return next();
    } catch (error) {
        console.error(`[AUTH] Token verification failed: ${error.message}`);
        return res.status(401).json({
            error: 'Unauthorized',
            message: `Invalid or expired Firebase ID token: ${error.message}`
        });
    }
}

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
                            provider: 'lumione',
                            providerId: v.videoId,
                        }));
                        console.log(`[SEARCH] Search provider OK: ${instance} (${results.length} results) for user ${reqUser(query)}`);
                        resolve(results);
                    } catch {
                        tryNext();
                    }
                });
            });
            req.on('error', (err) => {
                console.log(`[SEARCH] Search provider failed (${instance}): ${err.message}`);
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

function reqUser(q) {
    return 'authenticated';
}

// ─── API: Authenticated Music Search ────────────────────────────
app.get('/api/search', requireFirebaseAuth, async (req, res) => {
    const query = req.query.q;
    if (!query) return res.status(400).json({ error: 'Query parameter "q" is required' });

    console.log(`[SEARCH] Authenticated query from ${req.user.email || req.user.uid}: "${query}"`);
    const invInstances = await getHealthyInvidiousInstances();
    const invResults = await invidiousSearch(query, invInstances);
    if (invResults && invResults.length > 0) {
        return res.json({ results: invResults });
    }

    return res.json({ results: [] });
});

// ─── API: Authenticated Trending ─────────────────────────────────
app.get('/api/trending', requireFirebaseAuth, async (req, res) => {
    console.log(`[TRENDING] Authenticated request from ${req.user.email || req.user.uid}`);
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
                                provider: 'lumione',
                                providerId: v.videoId,
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
