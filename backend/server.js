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

async function optionalFirebaseAuth(req, res, next) {
    const authHeader = req.headers.authorization || '';
    if (authHeader.startsWith('Bearer ')) {
        const idToken = authHeader.split('Bearer ')[1].trim();
        if (idToken) {
            try {
                const decodedToken = await admin.auth().verifyIdToken(idToken);
                req.user = decodedToken;
            } catch (error) {
                console.warn(`[AUTH] Optional token verification bypassed: ${error.message}`);
            }
        }
    }
    return next();
}

const webPath = path.join(__dirname, '..', 'web');
app.use(express.static(webPath));

const recommender = require('./recommender');

const NON_MUSIC_REGEX = recommender.NON_MUSIC_REGEX;

function isMusicVideo(title, artist, durationSec) {
    return recommender.isMusicContent(title, artist, durationSec);
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

// ─── API: Explainable Hybrid Recommendations ─────────────────────
app.post('/api/recommendations', optionalFirebaseAuth, async (req, res) => {
    const userIdentifier = req.user ? (req.user.email || req.user.uid) : 'Guest';
    console.log(`[RECOMMENDATIONS] Request received for: ${userIdentifier}`);

    const userProfile = req.body || {};
    const limit = Math.min(Math.max(Number(userProfile.limit) || 20, 5), 50);

    const candidates = [];

    // 1. Fetch verified official charts (JioSaavn)
    try {
        const chartTracks = await fetchJioSaavnCharts();
        if (chartTracks && chartTracks.length > 0) {
            candidates.push(...chartTracks);
        }
    } catch (e) {
        console.warn(`[RECOMMENDATIONS] JioSaavn chart fetch warning: ${e.message}`);
    }

    // 2. Fetch targeted candidates for seed / favorite artists if provided
    const targetArtists = [...new Set([
        ...(userProfile.seedArtists || []),
        ...(userProfile.likedArtists || []),
        ...(userProfile.topPlayedArtists || [])
    ])].filter(Boolean).slice(0, 3);

    if (targetArtists.length > 0) {
        try {
            const artistPromises = targetArtists.map(artist =>
                youtubeSearch(`${artist} top songs official audio`, 8)
            );
            const artistResults = await Promise.all(artistPromises);
            for (const tracks of artistResults) {
                if (Array.isArray(tracks)) {
                    candidates.push(...tracks);
                }
            }
        } catch (e) {
            console.warn(`[RECOMMENDATIONS] Artist seed query warning: ${e.message}`);
        }
    }

    // Fallback if candidates pool is low
    if (candidates.length < 10) {
        try {
            const fallbackHits = await youtubeSearch('top music hits 2026 official audio', 15);
            candidates.push(...fallbackHits);
        } catch (_) {}
    }

    // Rank candidates using our hybrid scoring engine with diversity and deduplication
    const ranked = recommender.rankRecommendations(candidates, userProfile, limit);
    return res.json({
        results: ranked,
        total: ranked.length,
        user: userIdentifier
    });
});

// ─── Direct YouTube Watch Next (Up Next / Related Music) ───────────
function fetchYouTubeWatchNext(videoId, maxResults = 15) {
    return new Promise((resolve) => {
        if (!videoId || typeof videoId !== 'string') {
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
            videoId: videoId
        });

        const options = {
            hostname: 'www.youtube.com',
            port: 443,
            path: '/youtubei/v1/next?prettyPrint=false',
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
                    const secondaryResults = json?.contents?.twoColumnWatchNextResults?.secondaryResults?.secondaryResults?.results || [];
                    const items = [];

                    for (const r of secondaryResults) {
                        const lockup = r?.lockupViewModel;
                        if (lockup && lockup.contentId) {
                            const vId = lockup.contentId;
                            // Reject YouTube Mix / Radio playlist IDs (start with RD or not 11 chars)
                            if (!vId || vId.startsWith('RD') || vId.length !== 11) continue;

                            const title = lockup.metadata?.lockupMetadataViewModel?.title?.content || 'Unknown Title';
                            if (title.startsWith('Mix - ')) continue;

                            const metaRows = lockup.metadata?.lockupMetadataViewModel?.metadata?.contentMetadataViewModel?.metadataRows || [];
                            const artist = metaRows[0]?.metadataParts?.[0]?.text?.content || 'Unknown Artist';

                            // Parse duration from accessibility label (e.g. "Alan Walker - Alone 2 minutes, 44 seconds")
                            const label = lockup.rendererContext?.accessibilityContext?.label || '';
                            let durationSec = 210;
                            const minMatch = label.match(/(\d+)\s*minute/i);
                            const secMatch = label.match(/(\d+)\s*second/i);
                            const hrMatch = label.match(/(\d+)\s*hour/i);
                            if (minMatch || secMatch || hrMatch) {
                                durationSec = ((hrMatch ? parseInt(hrMatch[1], 10) : 0) * 3600) +
                                              ((minMatch ? parseInt(minMatch[1], 10) : 0) * 60) +
                                              (secMatch ? parseInt(secMatch[1], 10) : 0);
                            }

                            if (isMusicVideo(title, artist, durationSec)) {
                                items.push({
                                    id: vId,
                                    youtubeVideoId: vId,
                                    title: title,
                                    artist: artist,
                                    thumbnail: `https://i.ytimg.com/vi/${vId}/hqdefault.jpg`,
                                    duration: durationSec,
                                    durationFormatted: formatDuration(durationSec),
                                    provider: 'lumione',
                                    providerId: vId,
                                    source: 'watch_next',
                                    explanation: 'From YouTube Watch Next related mix'
                                });
                            }
                        }

                        const compact = r?.compactVideoRenderer;
                        if (compact && compact.videoId) {
                            const vId = compact.videoId;
                            if (!vId || vId.startsWith('RD') || vId.length !== 11) continue;

                            const title = compact.title?.simpleText || compact.title?.runs?.map(x => x.text).join('') || 'Unknown Title';
                            if (title.startsWith('Mix - ')) continue;

                            const artist = compact.shortBylineText?.runs?.map(x => x.text).join('') || 'Unknown Artist';
                            const durationText = compact.lengthText?.simpleText || '3:00';
                            const parts = durationText.split(':').map(Number);
                            let durationSec = 180;
                            if (parts.length === 2) durationSec = (parts[0] || 0) * 60 + (parts[1] || 0);
                            else if (parts.length === 3) durationSec = (parts[0] || 0) * 3600 + (parts[1] || 0) * 60 + (parts[2] || 0);

                            if (isMusicVideo(title, artist, durationSec)) {
                                items.push({
                                    id: vId,
                                    youtubeVideoId: vId,
                                    title: title,
                                    artist: artist,
                                    thumbnail: `https://i.ytimg.com/vi/${vId}/hqdefault.jpg`,
                                    duration: durationSec,
                                    durationFormatted: durationText,
                                    provider: 'lumione',
                                    providerId: vId,
                                    source: 'watch_next',
                                    explanation: 'From YouTube Watch Next related mix'
                                });
                            }
                        }

                        if (items.length >= maxResults) break;
                    }
                    resolve(items);
                } catch (e) {
                    console.error('[WATCH_NEXT] Parse error:', e.message);
                    resolve([]);
                }
            });
        });

        req.on('error', (err) => {
            console.error('[WATCH_NEXT] Request error:', err.message);
            resolve([]);
        });
        req.on('timeout', () => {
            req.destroy();
            resolve([]);
        });
        req.write(postData);
        req.end();
    });
}

async function fetchJioSaavnArtistSongs(artist, maxResults = 10) {
    if (!artist) return [];
    try {
        const encoded = encodeURIComponent(`${artist} songs`);
        const endpoint = `https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&p=1&n=${maxResults}&q=${encoded}`;
        const data = await fetchJsonUrl(endpoint);
        const results = data?.results || [];
        return results.map(s => {
            let img = s.image || '';
            if (img.includes('150x150')) img = img.replace('150x150', '500x500');
            const durationSec = Number(s.duration) || 0;
            return {
                id: s.id,
                youtubeVideoId: '',
                title: cleanHtmlEntities(s.song || s.title || 'Unknown Title'),
                artist: cleanHtmlEntities(s.primary_artists || s.singers || artist),
                thumbnail: img,
                duration: durationSec,
                durationFormatted: formatDuration(durationSec),
                provider: 'saavn',
                providerId: s.id,
                source: 'seed_artist',
                explanation: `Popular track by ${artist}`
            };
        }).filter(item => isMusicVideo(item.title, item.artist, item.duration));
    } catch (e) {
        return [];
    }
}

// ─── API: Related Tracks for Currently Playing Song (Up Next) ─────
app.get('/api/recommendations/related', optionalFirebaseAuth, async (req, res) => {
    const startTime = Date.now();
    const rawTitle = req.query.title || '';
    const rawArtist = req.query.artist || '';
    const videoId = req.query.videoId || '';
    const limit = Math.min(Math.max(Number(req.query.limit) || 12, 5), 25);

    if (!rawTitle && !rawArtist && !videoId) {
        return res.status(400).json({ error: 'At least title, artist, or videoId must be provided.' });
    }

    let { artist: effectiveArtist, title: effectiveTitle } = recommender.parseArtistAndTitle(rawTitle, rawArtist);
    if (!effectiveArtist && rawArtist) effectiveArtist = rawArtist;
    if (!effectiveTitle && rawTitle) effectiveTitle = rawTitle;

    // Phase 2: Build structured TrackProfile for seed track
    const seedProfile = recommender.buildTrackProfile({
        title: effectiveTitle || rawTitle,
        artist: effectiveArtist || rawArtist,
        videoId,
        durationSec: Number(req.query.duration) || 210
    });

    console.log(`[RELATED] Finding Up Next music for '${effectiveTitle}' by '${effectiveArtist}' (genre=${seedProfile.primaryGenre}, energy=${seedProfile.energy}, mood=${seedProfile.moodTags.join('/')}, videoId=${videoId})`);

    const seedKey = recommender.extractCanonicalKey(effectiveTitle || rawTitle, effectiveArtist || rawArtist);
    const candidatePromises = [];

    // 1. YouTube Watch Next / algorithmic related videos if videoId present
    if (videoId && videoId.length === 11 && !videoId.startsWith('RD')) {
        candidatePromises.push(
            fetchYouTubeWatchNext(videoId, 20).then(items =>
                items.map(it => ({ ...it, source: 'watch_next', explanation: 'From YouTube Watch Next related mix' }))
            )
        );
    }

    // 2. Artist catalog queries
    if (effectiveArtist) {
        candidatePromises.push(
            youtubeSearch(`${effectiveArtist} top songs official audio`, 10).then(items =>
                items.map(it => ({ ...it, source: 'seed_artist', explanation: `Top track by ${effectiveArtist}` }))
            )
        );

        // Only query JioSaavn when seed is from an Indian/Desi/Devotional catalog
        const isIndianOrDevotional = [
            recommender.GENRE_FAMILIES.BOLLYWOOD_DESI,
            recommender.GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
            recommender.GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL
        ].includes(seedProfile.primaryGenre);

        if (isIndianOrDevotional) {
            candidatePromises.push(fetchJioSaavnArtistSongs(effectiveArtist, 10));
        }
    }

    // 3. Related Artists queries (based on taxonomy linkages)
    const relatedArtists = seedProfile.relatedArtists || [];
    if (relatedArtists.length > 0) {
        const topRelated = relatedArtists.slice(0, 3);
        for (const relArtist of topRelated) {
            candidatePromises.push(
                youtubeSearch(`${relArtist} top hits official audio`, 6).then(items =>
                    items.map(it => ({
                        ...it,
                        source: 'related_artist',
                        explanation: `Similar artist: ${relArtist}`
                    }))
                )
            );
        }
    }

    // 4. Genre & Style queries based on seedProfile
    const genreKeywords = (seedProfile.primaryGenre || 'music').replace(/_/g, ' ');
    const primarySubgenre = (seedProfile.subgenres && seedProfile.subgenres[0]) || '';
    candidatePromises.push(
        youtubeSearch(`${genreKeywords} ${primarySubgenre} top songs official audio`, 8).then(items =>
            items.map(it => ({
                ...it,
                source: 'related_search',
                explanation: `Similar ${genreKeywords} music`
            }))
        )
    );

    // Notice: fetchJioSaavnCharts() is intentionally NOT included here.
    // Up Next must contain strictly related music, never generic trending charts.

    const settled = await Promise.allSettled(candidatePromises);
    const candidates = [];
    const sourceCounts = {};
    for (const r of settled) {
        if (r.status === 'fulfilled' && Array.isArray(r.value)) {
            for (const item of r.value) {
                candidates.push(item);
                sourceCounts[item.source || 'unknown'] = (sourceCounts[item.source || 'unknown'] || 0) + 1;
            }
        }
    }

    // Build user profile constraints
    let recentKeys = [seedKey];
    if (req.query.recentKeys) {
        try {
            const parsed = JSON.parse(req.query.recentKeys);
            if (Array.isArray(parsed)) recentKeys.push(...parsed);
        } catch (_) {
            if (typeof req.query.recentKeys === 'string') {
                recentKeys.push(...req.query.recentKeys.split(','));
            }
        }
    }

    const userProfile = {
        seedProfile,
        seedTitle: effectiveTitle || rawTitle,
        seedArtists: [effectiveArtist, rawArtist].filter(Boolean),
        recentTrackKeys: recentKeys,
        excludeKeys: [seedKey]
    };

    const ranked = recommender.rankRecommendations(candidates, userProfile, limit);
    const latencyMs = Date.now() - startTime;

    return res.json({
        seedProfile,
        results: ranked,
        diagnostics: {
            latencyMs,
            totalRetrieved: candidates.length,
            candidateSourceCounts: sourceCounts,
            ...(ranked.diagnostics || {})
        },
        totalCandidates: candidates.length,
        returnedCount: ranked.length
    });
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

module.exports = { app, isMusicVideo, fetchJioSaavnCharts, youtubeSearch, fetchYouTubeWatchNext, fetchJioSaavnArtistSongs, formatDuration, recommender };

