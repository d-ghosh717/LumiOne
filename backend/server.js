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

const NON_MUSIC_REGEX = /\b(gameplay|walkthrough|playthrough|let'?s play|gta|roblox|minecraft|fortnite|pubg|free\s*fire|bgmi|slime|asmr|mukbang|unboxing|prank|comedy video|funny video|puzzle|riddle|challenge|diy|crafts|life\s*hacks|vlog|daily vlog|reaction to|roast|news live|live stream|livestream|full movie|full episode|episode \d+|season \d+)\b/i;

function isMusicVideo(title, artist, durationSec) {
    if (!title || typeof title !== 'string') return false;
    if (durationSec > 900) return false;
    if (durationSec > 0 && durationSec < 30) return false;
    const combined = `${title} ${artist || ''}`;
    return !NON_MUSIC_REGEX.test(combined);
}

function cleanHtmlEntities(text) {
    if (!text) return '';
    return text.replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&#039;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

function fetchJsonUrl(url) {
    return new Promise((resolve) => {
        https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' }, timeout: 7000 }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try { resolve(JSON.parse(data)); } catch (e) { resolve(null); }
            });
        }).on('error', () => resolve(null)).on('timeout', function() { this.destroy(); resolve(null); });
    });
}

async function fetchJioSaavnCharts() {
    try {
        const p1 = 'https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=110858205&_format=json&_marker=0&ctx=web6dot0';
        const p2 = 'https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=1081991857&_format=json&_marker=0&ctx=web6dot0';
        const [tHindi, tEnglish] = await Promise.all([fetchJsonUrl(p1), fetchJsonUrl(p2)]);

        const hindiSongs = tHindi?.songs || tHindi?.list || [];
        const englishSongs = tEnglish?.songs || tEnglish?.list || [];

        const combined = [];
        const max = Math.max(hindiSongs.length, englishSongs.length);
        for (let i = 0; i < max && combined.length < 30; i++) {
            if (i < englishSongs.length) combined.push(englishSongs[i]);
            if (i < hindiSongs.length) combined.push(hindiSongs[i]);
        }

        return combined.map(s => {
            let img = s.image || '';
            if (img.includes('150x150')) img = img.replace('150x150', '500x500');
            const durationSec = Number(s.duration) || 0;
            return {
                id: s.id,
                youtubeVideoId: '',
                title: cleanHtmlEntities(s.song || s.title || 'Unknown Title'),
                artist: cleanHtmlEntities(s.primary_artists || s.singers || 'Unknown Artist'),
                thumbnail: img,
                duration: durationSec,
                durationFormatted: formatDuration(durationSec),
                provider: 'saavn',
                providerId: s.id
            };
        }).filter(item => isMusicVideo(item.title, item.artist, item.duration));
    } catch (e) {
        console.error('[TRENDING] Error fetching JioSaavn charts:', e.message);
        return [];
    }
}

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
                                // Skip livestreams
                                const badges = video.badges || [];
                                const isLive = badges.some(b => {
                                    const style = b?.metadataBadgeRenderer?.style || '';
                                    const label = b?.metadataBadgeRenderer?.label || '';
                                    return style.includes('LIVE') || label.toUpperCase().includes('LIVE');
                                });
                                if (isLive) continue;

                                const title = video.title?.runs?.map(r => r.text).join('') || video.title?.simpleText || 'Unknown Title';
                                const artist = video.ownerText?.runs?.map(r => r.text).join('') || 
                                               video.shortBylineText?.runs?.map(r => r.text).join('') || 'Unknown Artist';
                                const durationText = video.lengthText?.simpleText || video.lengthText?.runs?.map(r => r.text).join('') || '0:00';
                                
                                const parts = durationText.split(':').map(Number);
                                let durationSec = 0;
                                if (parts.length === 2) durationSec = (parts[0] || 0) * 60 + (parts[1] || 0);
                                else if (parts.length === 3) durationSec = (parts[0] || 0) * 3600 + (parts[1] || 0) * 60 + (parts[2] || 0);

                                if (!isMusicVideo(title, artist, durationSec)) continue;

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
    try {
        const saavnTracks = await fetchJioSaavnCharts();
        if (saavnTracks && saavnTracks.length > 0) {
            console.log(`[TRENDING] Returning ${saavnTracks.length} verified music tracks from official charts`);
            return res.json({ results: saavnTracks });
        }
    } catch (err) {
        console.error('[TRENDING] JioSaavn charts failed:', err.message);
    }

    const fallbackResults = await youtubeSearch('top music hits official audio', 15);
    return res.json({ results: fallbackResults });
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

if (require.main === module) {
    app.listen(PORT, '0.0.0.0', () => {
        console.log(`LumiOne metadata service running on port ${PORT}`);
    });
}

module.exports = { app, isMusicVideo, fetchJioSaavnCharts, youtubeSearch, formatDuration };

