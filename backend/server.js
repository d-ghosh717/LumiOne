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

// ─── Direct YouTube Metadata Search ───────────────────────────────
function youtubeSearch(query, maxResults = 15) {
    return new Promise((resolve) => {
        if (!query || typeof query !== 'string') {
            resolve([]);
            return;
        }

        const postData = JSON.stringify({
            context: {
                client: {
                    clientName: 'WEB',
                    clientVersion: '2.20240101.00.00',
                    hl: 'en',
                    gl: 'US'
                }
            },
            query: query
        });

        const options = {
            hostname: 'www.youtube.com',
            port: 443,
            path: '/youtubei/v1/search?prettyPrint=false',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData),
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
            },
            timeout: 8000
        };

        const req = https.request(options, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                try {
                    const json = JSON.parse(data);
                    const contents = json?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents || [];
                    const items = [];
                    for (const section of contents) {
                        const itemSection = section?.itemSectionRenderer?.contents || [];
                        for (const item of itemSection) {
                            const video = item.videoRenderer;
                            if (video && video.videoId) {
                                const title = video.title?.runs?.map(r => r.text).join('') || video.title?.simpleText || 'Unknown Title';
                                const artist = video.ownerText?.runs?.map(r => r.text).join('') || 
                                               video.shortBylineText?.runs?.map(r => r.text).join('') || 'Unknown Artist';
                                const durationText = video.lengthText?.simpleText || video.lengthText?.runs?.map(r => r.text).join('') || '0:00';
                                
                                const parts = durationText.split(':').map(Number);
                                let durationSec = 0;
                                if (parts.length === 2) durationSec = (parts[0] || 0) * 60 + (parts[1] || 0);
                                else if (parts.length === 3) durationSec = (parts[0] || 0) * 3600 + (parts[1] || 0) * 60 + (parts[2] || 0);

                                items.push({
                                    id: video.videoId,
                                    youtubeVideoId: video.videoId,
                                    title: title,
                                    artist: artist,
                                    thumbnail: `https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg`,
                                    duration: durationSec,
                                    durationFormatted: durationText,
                                    provider: 'lumione',
                                    providerId: video.videoId
                                });
                                if (items.length >= maxResults) break;
                            }
                        }
                        if (items.length >= maxResults) break;
                    }
                    resolve(items);
                } catch (err) {
                    console.error('[SEARCH] Parse error:', err.message);
                    resolve([]);
                }
            });
        });

        req.on('error', (err) => {
            console.error('[SEARCH] Request error:', err.message);
            resolve([]);
        });
        req.on('timeout', () => {
            req.destroy();
            console.error('[SEARCH] Request timeout');
            resolve([]);
        });
        req.write(postData);
        req.end();
    });
}

// ─── API: Authenticated Music Search ────────────────────────────
app.get('/api/search', requireFirebaseAuth, async (req, res) => {
    const query = req.query.q;
    if (!query) return res.status(400).json({ error: 'Query parameter "q" is required' });

    console.log(`[SEARCH] Authenticated query from ${req.user.email || req.user.uid}: "${query}"`);
    const results = await youtubeSearch(query, 15);
    return res.json({ results });
});

// ─── API: Authenticated Trending ─────────────────────────────────
app.get('/api/trending', requireFirebaseAuth, async (req, res) => {
    console.log(`[TRENDING] Authenticated request from ${req.user.email || req.user.uid}`);
    const results = await youtubeSearch('top music hits 2026', 15);
    return res.json({ results });
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
