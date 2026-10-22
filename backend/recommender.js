/**
 * LumiOne Music Recommendation Engine
 *
 * Implements a transparent, explainable hybrid music recommender:
 * 1. Metadata normalization (canonical song keys, publishing artifacts removal, artist cleaning)
 * 2. Non-music video rejection (filtering gaming, ASMR, vlogs, meme loops, commentary)
 * 3. Alternate-version detection and deduplication (slowed, sped-up, lo-fi, remixes, covers)
 * 4. Multi-factor scoring (artist affinity, behavior signals, discovery bonus, saturation penalties)
 * 5. Cold-start fallback (curated chart discovery with maximum artist diversity)
 */

// ─── Non-Music Video Filter ─────────────────────────────────────────────
const NON_MUSIC_REGEX = /\b(gameplay|walkthrough|playthrough|let'?s play|lets play|gta\s*[5v]|gta\s*online|fortnite|roblox|minecraft|free\s*fire|pubg|call\s*of\s*duty|cod|valorant|apex\s*legends|speedrun|cutscenes?|game\s*trailer|asmr|mukbang|slime|satisfying|unboxing|prank|comedy video|funny video|funny\s*moments|puzzle|riddle|challenge|diy|crafts|life\s*hacks|vlog|daily vlog|reaction to|reacting|roast|news live|live stream|livestream|full movie|full episode|episode \d+|season \d+|tier list|audiobook|podcast|interview|shorts|meme|compilation|sfx|sound effects?|10 hour|1 hour loop|24\/7|karaoke backing track|full album|best of [a-zA-Z0-9\s]+ full album|greatest hits full album|album mix|iconic hits & new songs)\b/i;

function isMusicContent(title, artist, durationSec) {
    if (!title || typeof title !== 'string' || title.trim().length === 0) return false;
    // Standard songs are between 35s and 600s (10 min). Album mixes/compilations exceed this.
    if (durationSec > 600) return false;
    if (durationSec > 0 && durationSec < 35) return false;
    const combined = `${title} ${artist || ''}`;
    return !NON_MUSIC_REGEX.test(combined);
}

// ─── Alternate Version & Modifier Detection ─────────────────────────────
const PUBLISHING_TAGS_REGEX = /\b(official\s*(music\s*)?video|official\s*audio|official\s*lyric\s*video|official\s*visualizer|lyrics?|visualizer|hd|4k|remaster(ed)?(\s*\d{4})?|explicit|audio|video|original mix)\b/gi;

const MODIFIER_PATTERNS = [
    { type: 'slowed', regex: /\b(slowed(\s*\+\s*reverb)?|reverb)\b/i },
    { type: 'sped_up', regex: /\b(sped\s*up|speed\s*up|nightcore|daycore)\b/i },
    { type: 'lofi', regex: /\b(lo-?fi|lo\s*fi)(\s*remix|\s*chill(\s*beats)?)?\b/i },
    { type: 'remix', regex: /\b(remix|rework|bootleg|flip|vip|mashup)\b/i },
    { type: 'cover', regex: /\b(cover|acoustic|piano\s*cover|guitar\s*cover|violin\s*cover|orchestral?)\b/i },
    { type: 'instrumental', regex: /\b(instrumental(\s*version)?|karaoke)\b/i },
    { type: 'live', regex: /\b(live(\s*in|\s*at|\s*session)?)\b/i },
    { type: 'bass_boosted', regex: /\b(bass\s*boosted|8d(\s*audio)?)\b/i },
    { type: 'loop', regex: /\b(1\s*hour|10\s*hours?|loop)\b/i }
];

const ALL_MODIFIERS_REGEX = /\b(slowed|reverb|sped\s*up|speed\s*up|nightcore|daycore|lo-?fi|lo\s*fi|chill\s*beats|remix|rework|bootleg|flip|vip|mashup|cover|acoustic|piano\s*cover|guitar\s*cover|violin\s*cover|orchestral?|instrumental(\s*version)?|karaoke|live(\s*in|\s*at|\s*session)?|bass\s*boosted|8d(\s*audio)?|1\s*hour|10\s*hours?|loop)\b/gi;

function detectModifier(title) {
    if (!title || typeof title !== 'string') return null;
    for (const { type, regex } of MODIFIER_PATTERNS) {
        if (regex.test(title)) {
            return type;
        }
    }
    return null;
}

// ─── Metadata Normalization ─────────────────────────────────────────────
function cleanTitle(rawTitle) {
    if (!rawTitle) return '';
    let t = rawTitle;
    // Strip bracketed featured artist tags e.g. (feat. Bruno Mars)
    t = t.replace(/\([^)\]]*(feat\.?|ft\.?|featuring)[^)\]]*\)/gi, ' ');
    t = t.replace(/\[[^)\]]*(feat\.?|ft\.?|featuring)[^)\]]*\]/gi, ' ');
    // Strip standalone feat
    t = t.replace(/\b(feat\.?|ft\.?|featuring)\b.*$/i, ' ');
    // Strip publishing tags
    t = t.replace(PUBLISHING_TAGS_REGEX, ' ');
    // Clean punctuation
    t = t.replace(/[([{\]}]/g, ' ')
        .replace(/["']/g, '')
        .replace(/[^a-zA-Z0-9\s]/g, ' ')
        .toLowerCase()
        .replace(/\s+/g, ' ')
        .trim();
    return t;
}

function cleanArtist(rawArtist) {
    if (!rawArtist) return '';
    return rawArtist
        .replace(/\([^)\]]*(feat\.?|ft\.?|featuring)[^)\]]*\)/gi, ' ')
        .replace(/\[[^)\]]*(feat\.?|ft\.?|featuring)[^)\]]*\]/gi, ' ')
        .replace(/\b(feat\.?|ft\.?|featuring)\b.*$/i, ' ')
        .replace(/\b(vevo|topic|records|music|official|channel)\b/gi, ' ')
        .replace(/[^a-zA-Z0-9\s]/g, ' ')
        .toLowerCase()
        .replace(/\s+/g, ' ')
        .trim();
}

/**
 * Strips modifier keywords from a cleaned title to retrieve the core underlying song title.
 * e.g. "faded slowed reverb" -> "faded"
 */
function extractCoreTitle(title) {
    let c = cleanTitle(title);
    c = c.replace(ALL_MODIFIERS_REGEX, ' ');
    return c.replace(/\s+/g, ' ').trim();
}

const TITLE_SEPARATOR_REGEX = /\s+[-—–|:]\s+/;

/**
 * Resolves the real performing artist and song title from raw YouTube metadata.
 * In third-party uploads, the channel is often a curator (e.g. "S o u n d s ."), NOT the artist.
 */
function parseArtistAndTitle(rawTitle, channelName = '') {
    let detectedArtist = (channelName || '').trim();
    let detectedTitle = (rawTitle || '').trim();

    if (TITLE_SEPARATOR_REGEX.test(rawTitle)) {
        const parts = rawTitle.split(TITLE_SEPARATOR_REGEX).map(s => s.trim()).filter(Boolean);
        if (parts.length >= 2) {
            const part0 = parts[0];
            const part1 = parts.slice(1).join(' - ');

            const part0HasModifier = detectModifier(part0) !== null;
            const part1HasModifier = detectModifier(part1) !== null;

            if (part0HasModifier && !part1HasModifier) {
                // "Title (Modifier) - Artist"
                detectedTitle = part0;
                detectedArtist = part1;
            } else {
                // "Artist - Title"
                detectedArtist = part0;
                detectedTitle = part1;
            }
        }
    } else if (channelName.toLowerCase().endsWith('- topic')) {
        detectedArtist = channelName.replace(/- topic$/i, '').trim();
    }

    return { artist: detectedArtist, title: detectedTitle };
}

/**
 * Extracts a stable canonical key representing the underlying song.
 * e.g. "LONOWN - worry (Slowed)" -> "lonown:::worry"
 * e.g. "Alan Walker - Faded" -> "alanwalker:::faded"
 * e.g. "ZHU - Faded" -> "zhu:::faded"
 */
function extractCanonicalKey(title, artist) {
    const { artist: parsedArtist, title: parsedTitle } = parseArtistAndTitle(title, artist);
    const cArtist = cleanArtist(parsedArtist);
    let cCoreTitle = extractCoreTitle(parsedTitle);

    if (cArtist && cCoreTitle.includes(cArtist)) {
        cCoreTitle = cCoreTitle.replace(cArtist, ' ').replace(/\s+/g, ' ').trim();
    }

    if (!cCoreTitle) cCoreTitle = cleanTitle(parsedTitle);
    return `${cArtist}:::${cCoreTitle}`;
}

/**
 * Unique key identifying the specific version / recording.
 * e.g. "LONOWN - worry (Slowed)" -> "lonown:::worry:::slowed"
 */
function extractRecordingKey(title, artist) {
    const canonicalKey = extractCanonicalKey(title, artist);
    const modifier = detectModifier(title) || 'original';
    return `${canonicalKey}:::${modifier}`;
}

// ─── Deduplication & Alternate-Version Suppression ───────────────────────
/**
 * Deduplicates candidate tracks by clustering alternate versions of the same song.
 * In discovery mode, picks the best canonical version (original/official) and discards
 * slowed, sped-up, lo-fi, and duplicate uploads of that same track.
 */
function deduplicateAndPruneVersions(candidates, options = {}) {
    const {
        allowAlternateVersions = false,
        maxPerArtist = 2,
        seedKeysToExclude = new Set()
    } = options;

    const clusters = new Map(); // clusterKey -> Array<candidate>
    const validCandidates = [];

    // Step 1: Filter non-music and group by canonicalKey
    for (const track of candidates) {
        const title = track.title || '';
        const artist = track.artist || '';
        const durationSec = Number(track.duration) || 0;

        if (!isMusicContent(title, artist, durationSec)) continue;

        const canonicalKey = extractCanonicalKey(title, artist);
        if (seedKeysToExclude.has(canonicalKey)) continue;

        const modifier = detectModifier(title);
        const enriched = {
            ...track,
            canonicalKey,
            modifier,
            isAlternate: modifier !== null
        };

        if (!clusters.has(canonicalKey)) {
            clusters.set(canonicalKey, []);
        }
        clusters.get(canonicalKey).push(enriched);
    }

    // Step 2: Select the best representative track for each canonical song cluster
    const artistCountMap = new Map();

    for (const [, variants] of clusters.entries()) {
        // Sort variants so the canonical original comes first:
        // 1. Non-alternate (no modifier) first
        // 2. Verified provider (saavn > lumione > others)
        // 3. Normal song duration (180s - 300s preferred)
        variants.sort((a, b) => {
            if (a.isAlternate !== b.isAlternate) {
                return a.isAlternate ? 1 : -1;
            }
            if (a.provider === 'saavn' && b.provider !== 'saavn') return -1;
            if (b.provider === 'saavn' && a.provider !== 'saavn') return 1;

            const aDurDiff = Math.abs((a.duration || 210) - 210);
            const bDurDiff = Math.abs((b.duration || 210) - 210);
            return aDurDiff - bDurDiff;
        });

        const bestTrack = variants[0];
        const { artist: parsedArtist } = parseArtistAndTitle(bestTrack.title, bestTrack.artist);
        const rawArtistList = extractArtistList(bestTrack.artist);
        const artistKeys = rawArtistList.length > 0 ? rawArtistList : [cleanArtist(parsedArtist)];

        // Artist saturation limit across all participating artists in track
        const maxSeen = Math.max(...artistKeys.map(k => artistCountMap.get(k) || 0), 0);
        if (maxSeen < maxPerArtist) {
            validCandidates.push(bestTrack);
            for (const k of artistKeys) {
                artistCountMap.set(k, (artistCountMap.get(k) || 0) + 1);
            }
        }

        // If user explicitly allowed alternate versions (e.g. remix search), keep up to 1 alternate
        if (allowAlternateVersions && variants.length > 1) {
            const seenMods = new Set([detectModifier(bestTrack.title) || 'original']);
            for (let i = 1; i < variants.length; i++) {
                const variant = variants[i];
                const mod = detectModifier(variant.title) || 'original';
                if (!seenMods.has(mod)) {
                    seenMods.add(mod);
                    const vSeen = Math.max(...artistKeys.map(k => artistCountMap.get(k) || 0), 0);
                    if (vSeen < maxPerArtist) {
                        validCandidates.push(variant);
                        for (const k of artistKeys) {
                            artistCountMap.set(k, (artistCountMap.get(k) || 0) + 1);
                        }
                    }
                }
            }
        }
    }

    return validCandidates;
}

/**
 * Deduplicates search or discovery results for playback queue insertion.
 */
function deduplicateForQueue(candidates, selectedTrackKey = '', maxPerArtist = 3) {
    const seenVideoIds = new Set();
    const seenSongKeys = new Set();
    if (selectedTrackKey) {
        seenSongKeys.add(selectedTrackKey);
    }

    const artistCountMap = new Map();
    const filtered = [];

    for (const item of candidates) {
        const title = item.title || '';
        const artist = item.artist || '';
        const durationSec = Number(item.duration) || 0;
        if (!isMusicContent(title, artist, durationSec)) continue;
        if (item.videoId && seenVideoIds.has(item.videoId)) continue;

        const canonicalKey = extractCanonicalKey(title, artist);
        if (seenSongKeys.has(canonicalKey)) continue;

        const { artist: parsedArtist } = parseArtistAndTitle(title, artist);
        const artistKey = cleanArtist(parsedArtist);
        const count = artistCountMap.get(artistKey) || 0;
        if (count >= maxPerArtist) continue;

        if (item.videoId) seenVideoIds.add(item.videoId);
        seenSongKeys.add(canonicalKey);
        artistCountMap.set(artistKey, count + 1);
        filtered.push(item);
    }

    return filtered;
}

// ─── Hybrid Scoring Engine ───────────────────────────────────────────────
function scoreCandidate(candidate, userProfile, selectedSoFar = []) {
    let score = 5.0; // Base baseline score
    const explanations = [];

    const candidateArtist = cleanArtist(candidate.artist);
    const candidateKey = candidate.canonicalKey || extractCanonicalKey(candidate.title, candidate.artist);

    const likedArtists = (userProfile.likedArtists || []).map(cleanArtist);
    const seedArtists = (userProfile.seedArtists || []).map(cleanArtist);
    const skippedArtists = (userProfile.skippedArtists || []).map(cleanArtist);
    const topPlayedArtists = (userProfile.topPlayedArtists || []).map(cleanArtist);
    const likedTrackKeys = new Set((userProfile.likedTrackKeys || []).map(k => k.toLowerCase()));
    const recentTrackKeys = new Set((userProfile.recentTrackKeys || []).map(k => k.toLowerCase()));
    const skippedTrackKeys = new Set((userProfile.skippedTrackKeys || []).map(k => k.toLowerCase()));

    // 1. Source & Artist Affinity
    if (likedArtists.includes(candidateArtist) || topPlayedArtists.includes(candidateArtist)) {
        score += 3.5;
        explanations.push(`Favorite artist: ${candidate.artist}`);
    } else if (seedArtists.includes(candidateArtist) || candidate.source === 'seed_artist') {
        score += 3.0;
        explanations.push(`More from ${candidate.artist}`);
    } else if (candidate.source === 'watch_next') {
        // High confidence algorithmic relation from YouTube Watch Next graph
        score += 2.6;
        explanations.push(candidate.explanation || 'Related via music recommendation network');
    } else if (candidate.source === 'related_search') {
        score += 2.2;
        explanations.push(candidate.explanation || 'Similar style music');
    } else {
        // Unverified discovery candidate (do NOT outrank verified related tracks)
        score += 0.2;
        explanations.push('Discovery candidate');
    }

    // 2. Behavioral Signals
    if (likedTrackKeys.has(candidateKey.toLowerCase())) {
        score += 2.0;
        explanations.push('You liked this track');
    }
    if (skippedTrackKeys.has(candidateKey.toLowerCase())) {
        score -= 4.0;
        explanations.push('Previously skipped');
    }
    if (skippedArtists.includes(candidateArtist)) {
        score -= 2.5;
    }

    // 3. Repeat Song Penalty (Do not recommend songs currently in recent queue)
    if (recentTrackKeys.has(candidateKey.toLowerCase())) {
        score -= 3.0; // Mild penalty to encourage exploration rather than immediate replay
    }

    // 4. Duplicate in current recommendation row check
    const isAlreadyInRow = selectedSoFar.some(
        s => (s.canonicalKey || extractCanonicalKey(s.title, s.artist)) === candidateKey
    );
    if (isAlreadyInRow) {
        score -= 20.0; // Hard rejection for duplicates
    }

    // 5. Alternate Version Penalty (slowed, sped-up, lo-fi, etc.)
    if (candidate.isAlternate) {
        score -= 5.0;
        explanations.push(`Alternate ${candidate.modifier} version`);
    }

    // 6. Artist Saturation Penalty
    const countOfThisArtistInRow = selectedSoFar.filter(
        s => cleanArtist(s.artist) === candidateArtist
    ).length;
    if (countOfThisArtistInRow === 1) {
        score -= 2.0; // Mild spacing to allow related artists to interleave
    } else if (countOfThisArtistInRow === 2) {
        score -= 4.5; // Stronger penalty for 3rd track
    } else if (countOfThisArtistInRow >= 3) {
        score -= 15.0; // Strict diversity: max 3 tracks per artist in Up Next
    }

    // 7. Verified Music Catalog Bonus (minor audio-quality tie-breaker only)
    if (candidate.provider === 'saavn' && (seedArtists.includes(candidateArtist) || candidate.source === 'seed_artist')) {
        score += 0.2;
    }

    return {
        score: Math.max(0, Number(score.toFixed(2))),
        primaryExplanation: explanations[0] || 'Recommended music'
    };
}

const {
    GENRE_FAMILIES,
    GENRE_COMPATIBILITY_MATRIX,
    extractArtistList,
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile,
    computeAcousticSimilarity
} = require('./music_intelligence');

/**
 * Ranks a pool of candidates using greedy selection with real-time diversity constraints.
 * Incorporates:
 * 1. Strict genre and mood eligibility gates (incompatible styles rejected pre-scoring).
 * 2. 0-100 weighted point scoring system (acoustic similarity 40, genre 25, mood/energy 20, affinity 15).
 * 3. Multi-artist saturation and diversity constraints.
 * 4. Transparent development diagnostics.
 */
function rankRecommendations(candidates, userProfile = {}, limit = 20) {
    if (!candidates || candidates.length === 0) {
        const empty = [];
        empty.diagnostics = {
            totalCandidates: 0,
            eligibleCount: 0,
            rejectedCount: 0,
            rejections: [],
            returnedCount: 0
        };
        return empty;
    }

    const effectiveMaxPerArtist = userProfile.maxPerArtist || (userProfile.seedArtists?.length ? 3 : 2);

    // Build or extract seed track profile
    const seedProfile = userProfile.seedProfile || (
        (userProfile.seedTitle || (userProfile.seedArtists && userProfile.seedArtists[0]))
            ? buildTrackProfile({ title: userProfile.seedTitle || '', artist: (userProfile.seedArtists && userProfile.seedArtists[0]) || '' })
            : null
    );

    // Pre-filter non-music and deduplicate canonical songs
    const cleanCandidates = deduplicateAndPruneVersions(candidates, {
        allowAlternateVersions: false,
        maxPerArtist: effectiveMaxPerArtist,
        seedKeysToExclude: new Set((userProfile.excludeKeys || []).map(k => k.toLowerCase()))
    });

    // Apply Phase 3: Genre and Mood Eligibility Gate
    const eligiblePool = [];
    const rejections = [];
    for (const candidate of cleanCandidates) {
        const candProfile = candidate.profile || buildTrackProfile(candidate);
        candidate.profile = candProfile;

        if (seedProfile) {
            const gate = checkEligibilityGate(seedProfile, candProfile);
            if (!gate.eligible) {
                candidate.eligible = false;
                candidate.rejectionReason = gate.rejectionReason;
                rejections.push({
                    title: candidate.title,
                    artist: candidate.artist,
                    reason: gate.rejectionReason
                });
                continue; // Rejected by gate! Never enters scoring or queue
            }
        }
        candidate.eligible = true;
        eligiblePool.push(candidate);
    }

    const pool = [...eligiblePool];
    const selected = [];

    while (pool.length > 0 && selected.length < limit) {
        let bestIndex = -1;
        let bestScore = -Infinity;
        let bestExplanation = '';
        let bestBreakdown = null;

        for (let i = 0; i < pool.length; i++) {
            const candidate = pool[i];
            let score, primaryExplanation, breakdown;

            if (seedProfile) {
                const result = scoreTrackProfile(candidate, seedProfile, userProfile, selected);
                score = result.totalScore;
                primaryExplanation = result.primaryExplanation;
                breakdown = result.breakdown;
            } else {
                const result = scoreCandidate(candidate, userProfile, selected);
                score = result.score;
                primaryExplanation = result.primaryExplanation;
            }

            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
                bestExplanation = primaryExplanation;
                bestBreakdown = breakdown;
            }
        }

        if (bestIndex >= 0 && bestScore > 0) {
            const [chosen] = pool.splice(bestIndex, 1);
            selected.push({
                ...chosen,
                recommendationScore: bestScore,
                recommendationReason: chosen.explanation || bestExplanation,
                scoreBreakdown: bestBreakdown,
                genre: chosen.profile?.primaryGenre,
                mood: chosen.profile?.moodTags
            });
        } else {
            break;
        }
    }

    selected.diagnostics = {
        totalCandidates: candidates.length,
        cleanCandidatesCount: cleanCandidates.length,
        eligibleCount: eligiblePool.length,
        rejectedCount: rejections.length,
        rejections: rejections.slice(0, 15),
        returnedCount: selected.length
    };

    return selected;
}

module.exports = {
    isMusicContent,
    cleanTitle,
    cleanArtist,
    extractArtistList,
    extractCoreTitle,
    parseArtistAndTitle,
    extractCanonicalKey,
    extractRecordingKey,
    detectModifier,
    deduplicateAndPruneVersions,
    deduplicateForQueue,
    scoreCandidate,
    rankRecommendations,
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile,
    computeAcousticSimilarity,
    GENRE_FAMILIES
};
