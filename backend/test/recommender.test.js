const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const {
    isMusicContent,
    cleanTitle,
    cleanArtist,
    parseArtistAndTitle,
    extractCanonicalKey,
    detectModifier,
    deduplicateAndPruneVersions,
    deduplicateForQueue,
    scoreCandidate,
    rankRecommendations,
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile,
    GENRE_FAMILIES
} = require('../recommender');

describe('LumiOne Recommender Module Tests', () => {

    test('Non-music filtering blocks gaming, ASMR, and irrelevant videos', () => {
        // Gaming
        assert.equal(isMusicContent('GTA 5 LIVE: INSANE HEIST WITH FRIENDS', 'GamerBoy', 3600), false);
        assert.equal(isMusicContent('Minecraft Hardcore 100 Days Gameplay', 'Crafter', 1800), false);
        assert.equal(isMusicContent('Roblox Brookhaven RP', 'RobloxFun', 900), false);
        assert.equal(isMusicContent('Fortnite Season 5 Walkthrough', 'ProGamer', 800), false);

        // ASMR & Slime
        assert.equal(isMusicContent('Slime ASMR Most Satisfying Video', 'Relax Slime', 600), false);
        assert.equal(isMusicContent('Mukbang Eating 10000 Calories', 'Foodie', 1200), false);

        // Vlogs & Podcasts
        assert.equal(isMusicContent('Daily Vlog Day in NYC', 'Vlogger', 600), false);
        assert.equal(isMusicContent('Joe Rogan Podcast Clip Interview', 'ClipZone', 400), false);
        assert.equal(isMusicContent("The Making Of 'On The Floor' (Vevo Footnotes)", 'Jennifer Lopez', 240), false);

        // Duration limits
        assert.equal(isMusicContent('Short meme clip', 'Creator', 15), false);
        assert.equal(isMusicContent('10 Hour Relaxing Ambient Loop', 'RelaxZone', 36000), false);

        // Valid music
        assert.equal(isMusicContent('Blinding Lights', 'The Weeknd', 210), true);
        assert.equal(isMusicContent('Faded', 'Alan Walker', 213), true);
        assert.equal(isMusicContent('Kesariya', 'Arijit Singh', 268), true);
    });

    test('Title and artist normalization', () => {
        assert.equal(cleanTitle('Faded (Official Music Video)'), 'faded');
        assert.equal(cleanTitle('Blinding Lights [Official Audio]'), 'blinding lights');
        assert.equal(cleanTitle('Die With A Smile (feat. Bruno Mars)'), 'die with a smile');
        assert.equal(cleanTitle('Wake Me Up - Remastered 2024'), 'wake me up');

        assert.equal(cleanArtist('Alan Walker VEVO'), 'alan walker');
        assert.equal(cleanArtist('The Weeknd - Topic'), 'the weeknd');
        assert.equal(cleanArtist('Ed Sheeran feat. Beyonce'), 'ed sheeran');
    });

    test('Canonical key extraction resolves title variants of same underlying song', () => {
        const k1 = extractCanonicalKey('Faded', 'Alan Walker');
        const k2 = extractCanonicalKey('Alan Walker - Faded (Official Music Video)', 'Alan Walker');
        const k3 = extractCanonicalKey('Faded [Official Audio]', 'Alan Walker VEVO');

        assert.equal(k1, 'alan walker:::faded');
        assert.equal(k2, 'alan walker:::faded');
        assert.equal(k3, 'alan walker:::faded');
    });

    test('Alternate version modifier detection', () => {
        assert.equal(detectModifier('Believer (Slowed + Reverb)'), 'slowed');
        assert.equal(detectModifier('Blinding Lights (Sped Up)'), 'sped_up');
        assert.equal(detectModifier('Shape of You (Lo-Fi Remix)'), 'lofi');
        assert.equal(detectModifier('Wake Me Up (Avicii Remix)'), 'remix');
        assert.equal(detectModifier('Perfect (Violin Cover)'), 'cover');
        assert.equal(detectModifier('Faded (Instrumental Version)'), 'instrumental');
        assert.equal(detectModifier('Believer (Live in Vegas)'), 'live');

        // Pure songs should have no modifier
        assert.equal(detectModifier('Blinding Lights'), null);
        assert.equal(detectModifier('Kesariya'), null);
    });

    test('Deduplication eliminates duplicate slowed/sped-up/remix versions of same song', () => {
        const candidates = [
            { id: '1', title: 'Faded', artist: 'Alan Walker', duration: 213, provider: 'saavn' },
            { id: '2', title: 'Faded (Slowed + Reverb)', artist: 'Alan Walker', duration: 260, provider: 'lumione' },
            { id: '3', title: 'Alan Walker - Faded (Sped Up)', artist: 'RemixChannel', duration: 170, provider: 'lumione' },
            { id: '4', title: 'Alan Walker - Faded (Lo-Fi Chill Beats)', artist: 'LofiGuy', duration: 190, provider: 'lumione' },
            { id: '5', title: 'Blinding Lights', artist: 'The Weeknd', duration: 200, provider: 'saavn' },
            { id: '6', title: 'Blinding Lights (Acoustic)', artist: 'The Weeknd', duration: 190, provider: 'lumione' }
        ];

        const deduplicated = deduplicateAndPruneVersions(candidates, { allowAlternateVersions: false });

        // Exactly 2 distinct underlying songs should remain (Faded & Blinding Lights)
        assert.equal(deduplicated.length, 2);
        assert.equal(deduplicated[0].id, '1'); // Original Faded
        assert.equal(deduplicated[1].id, '5'); // Original Blinding Lights
    });

    test('Hybrid recommender scoring and diversity constraints', () => {
        const candidates = [
            { id: '1', title: 'Starboy', artist: 'The Weeknd', duration: 230, provider: 'saavn' },
            { id: '2', title: 'Save Your Tears', artist: 'The Weeknd', duration: 215, provider: 'saavn' },
            { id: '3', title: 'Can\'t Feel My Face', artist: 'The Weeknd', duration: 213, provider: 'saavn' },
            { id: '4', title: 'In The Night', artist: 'The Weeknd', duration: 235, provider: 'saavn' },
            { id: '5', title: 'Levitating', artist: 'Dua Lipa', duration: 203, provider: 'saavn' },
            { id: '6', title: 'Don\'t Start Now', artist: 'Dua Lipa', duration: 183, provider: 'saavn' },
            { id: '7', title: 'As It Was', artist: 'Harry Styles', duration: 167, provider: 'saavn' }
        ];

        const userProfile = {
            likedArtists: ['The Weeknd'],
            recentTrackKeys: ['the weeknd:::starboy']
        };

        const recommendations = rankRecommendations(candidates, userProfile, 10);

        // Maximum 2 tracks from The Weeknd to prevent saturation
        const weekndTracks = recommendations.filter(r => cleanArtist(r.artist) === 'the weeknd');
        assert.ok(weekndTracks.length <= 2, `Expected at most 2 tracks from The Weeknd, got ${weekndTracks.length}`);

        // Must include diverse artists like Dua Lipa and Harry Styles
        const otherArtists = recommendations.map(r => cleanArtist(r.artist));
        assert.ok(otherArtists.includes('dua lipa'));
        assert.ok(otherArtists.includes('harry styles'));

        // Explanations must be populated
        assert.ok(recommendations[0].recommendationReason.length > 0);
    });

    test('Multiple uploads of LONOWN - worry (Slowed) across different channel names resolve to same canonical key', () => {
        const u1 = extractCanonicalKey('LONOWN - worry (Slowed)', 'S o u n d s .');
        const u2 = extractCanonicalKey('LONOWN - worry (Slowed + Reverb)', 'Night Drive');
        const u3 = extractCanonicalKey('worry (slowed) - LONOWN', 'Anime Chill');
        const u4 = extractCanonicalKey('LONOWN - worry', 'LONOWN - Topic');

        assert.equal(u1, 'lonown:::worry');
        assert.equal(u2, 'lonown:::worry');
        assert.equal(u3, 'lonown:::worry');
        assert.equal(u4, 'lonown:::worry');
    });

    test('Alan Walker - Faded vs ZHU - Faded vs Tyga - Faded are NOT merged and retain distinct identities', () => {
        const k1 = extractCanonicalKey('Alan Walker - Faded', 'Alan Walker');
        const k2 = extractCanonicalKey('ZHU - Faded', 'ZHU');
        const k3 = extractCanonicalKey('Tyga - Faded ft. Lil Wayne', 'Tyga');

        assert.equal(k1, 'alan walker:::faded');
        assert.equal(k2, 'zhu:::faded');
        assert.equal(k3, 'tyga:::faded');

        assert.notEqual(k1, k2);
        assert.notEqual(k1, k3);
        assert.notEqual(k2, k3);

        const candidates = [
            { id: 'aw1', title: 'Alan Walker - Faded', artist: 'Alan Walker', duration: 213 },
            { id: 'zhu1', title: 'ZHU - Faded', artist: 'ZHU', duration: 220 },
            { id: 'tyga1', title: 'Tyga - Faded ft. Lil Wayne', artist: 'Tyga', duration: 200 }
        ];

        // Neither should be pruned as duplicate of the other
        const deduplicated = deduplicateAndPruneVersions(candidates, { allowAlternateVersions: false });
        assert.equal(deduplicated.length, 3);
    });

    test('deduplicateForQueue prunes duplicate uploads while keeping distinct songs and user selection', () => {
        const searchResults = [
            { videoId: 'w1', title: 'LONOWN - worry (Slowed)', artist: 'S o u n d s .', duration: 135 },
            { videoId: 'w2', title: 'LONOWN - worry (Slowed + Reverb)', artist: 'Night Drive', duration: 135 },
            { videoId: 'w3', title: 'worry (slowed) - LONOWN', artist: 'Anime Chill', duration: 135 },
            { videoId: 'aw1', title: 'Alan Walker - Faded', artist: 'Alan Walker', duration: 213 },
            { videoId: 'zhu1', title: 'ZHU - Faded', artist: 'ZHU', duration: 220 }
        ];

        const selectedTrack = searchResults[0]; // LONOWN - worry (Slowed)
        const selectedKey = extractCanonicalKey(selectedTrack.title, selectedTrack.artist);

        const remaining = searchResults.slice(1);
        const queueTracks = deduplicateForQueue(remaining, selectedKey, 3);

        // All other duplicate uploads of worry (w2, w3) must be filtered out!
        assert.equal(queueTracks.some(t => t.videoId === 'w2'), false);
        assert.equal(queueTracks.some(t => t.videoId === 'w3'), false);

        // Distinct songs (Alan Walker - Faded and ZHU - Faded) must be kept!
        assert.equal(queueTracks.some(t => t.videoId === 'aw1'), true);
        assert.equal(queueTracks.some(t => t.videoId === 'zhu1'), true);
    });

    test('Up Next recommendation ranking prioritizes related artists over unrelated same-title tracks', () => {
        // Seed song is Alan Walker - Faded
        const seedTrack = { id: 'seed1', title: 'Alan Walker - Faded', artist: 'Alan Walker' };
        const seedKey = extractCanonicalKey(seedTrack.title, seedTrack.artist);

        const candidates = [
            // Exact same title by unrelated artists (NOT musically related by artist affinity)
            { id: 'tyga1', title: 'Tyga - Faded ft. Lil Wayne', artist: 'Tyga', duration: 200, provider: 'lumione' },
            { id: 'tnskai1', title: 'TNS Kai - Faded', artist: 'TNS Kai', duration: 180, provider: 'lumione' },
            { id: 'fay1', title: 'Fay - Faded', artist: 'Fay', duration: 195, provider: 'lumione' },
            // Musically related tracks by the seed artist and similar electronic artists
            { id: 'aw2', title: 'Alan Walker - Alone', artist: 'Alan Walker', duration: 210, provider: 'lumione' },
            { id: 'aw3', title: 'Alan Walker - The Spectre', artist: 'Alan Walker', duration: 195, provider: 'lumione' },
            { id: 'cs1', title: 'The Chainsmokers - Closer', artist: 'The Chainsmokers', duration: 245, provider: 'saavn' },
            { id: 'marsh1', title: 'Marshmello - Happier', artist: 'Marshmello', duration: 215, provider: 'saavn' }
        ];

        const userProfile = {
            seedArtists: ['Alan Walker'],
            likedArtists: ['The Chainsmokers', 'Marshmello'],
            recentTrackKeys: [seedKey],
            excludeKeys: [seedKey]
        };

        const upNext = rankRecommendations(candidates, userProfile, 5);

        // 1. Seed song itself must be excluded
        assert.equal(upNext.some(t => t.id === 'seed1'), false);

        // 2. Musically related songs by Alan Walker, The Chainsmokers, and Marshmello must rank high
        const top3Artists = upNext.slice(0, 3).map(t => cleanArtist(t.artist));
        assert.ok(top3Artists.includes('alan walker'));
        assert.ok(top3Artists.includes('the chainsmokers') || top3Artists.includes('marshmello'));

        // 3. Unrelated tracks merely sharing the title "Faded" by Tyga, TNS Kai, Fay must NOT dominate the Up Next queue!
        const unrelatedSameTitleInTop3 = upNext.slice(0, 3).filter(t => ['tyga', 'tns kai', 'fay'].includes(cleanArtist(t.artist)));
        assert.equal(unrelatedSameTitleInTop3.length, 0, 'Unrelated artists with same title should not be in top 3 Up Next');
    });

    test('P0 BUG REPRODUCTION: Hanuman Chalisa, Parvati, and unrelated Indian chart tracks are rejected when seed is Alan Walker - Faded', () => {
        const seedKey = extractCanonicalKey('Faded', 'Alan Walker');

        // Mixed candidate pool containing both genuine related tracks and the JioSaavn trending chart items
        const mixedCandidates = [
            // Genuine seed & related tracks
            { id: 'aw_alone', title: 'Alone', artist: 'Alan Walker', duration: 164, provider: 'saavn', source: 'seed_artist' },
            { id: 'aw_onmyway', title: 'On My Way', artist: 'Alan Walker, Sabrina Carpenter, Farruko', duration: 193, provider: 'saavn', source: 'seed_artist' },
            { id: 'aw_ignite', title: 'Ignite', artist: 'Alan Walker, Julie Bergan, K-391', duration: 210, provider: 'saavn', source: 'seed_artist' },
            { id: 'zayn_dusk', title: 'Dusk Till Dawn (feat. Sia)', artist: 'ZAYN', duration: 239, provider: 'lumione', source: 'watch_next' },
            { id: 'pass_lethergo', title: 'Let Her Go', artist: 'Passenger', duration: 252, provider: 'lumione', source: 'watch_next' },
            { id: 'cs_dontletme', title: 'Don\'t Let Me Down', artist: 'The Chainsmokers', duration: 208, provider: 'lumione', source: 'watch_next' },

            // Unrelated Indian songs that previously entered the queue from JioSaavn trending charts
            { id: 'hanuman_chalisa', title: 'Hanuman Chalisa', artist: 'Hariharan, Gulshan Kumar', duration: 580, provider: 'saavn', source: 'unrelated_chart' },
            { id: 'parvati', title: 'Parvati', artist: 'Hansraj Raghuwanshi', duration: 245, provider: 'saavn', source: 'unrelated_chart' },
            { id: 'tujh_mein_basi', title: 'Tujh Mein Basi Meri Jaan', artist: 'B Praak', duration: 280, provider: 'saavn', source: 'unrelated_chart' },
            { id: 'casa_tupka', title: 'Casa Tupka Anthem', artist: 'MC Square', duration: 190, provider: 'saavn', source: 'unrelated_chart' }
        ];

        const userProfile = {
            seedArtists: ['Alan Walker'],
            recentTrackKeys: [seedKey],
            excludeKeys: [seedKey]
        };

        const upNext = rankRecommendations(mixedCandidates, userProfile, 6);

        // 1. All selected items must be from Alan Walker or verified related electronic/pop music
        const selectedTitles = upNext.map(t => t.title);
        assert.ok(selectedTitles.includes('Alone'));
        assert.ok(selectedTitles.includes('On My Way'));
        assert.ok(selectedTitles.includes('Dusk Till Dawn (feat. Sia)') || selectedTitles.includes('Let Her Go'));

        // 2. NONE of the unrelated Indian songs must appear in the top 6 recommendations!
        assert.equal(selectedTitles.includes('Hanuman Chalisa'), false, 'Hanuman Chalisa must NOT be recommended for Alan Walker');
        assert.equal(selectedTitles.includes('Parvati'), false, 'Parvati must NOT be recommended for Alan Walker');
        assert.equal(selectedTitles.includes('Tujh Mein Basi Meri Jaan'), false, 'Tujh Mein Basi Meri Jaan must NOT be recommended for Alan Walker');
        assert.equal(selectedTitles.includes('Casa Tupka Anthem'), false, 'Casa Tupka Anthem must NOT be recommended for Alan Walker');
    });

    test('Empty candidate pool or network failure produces empty recommendation list, NOT arbitrary generic tracks', () => {
        const seedKey = extractCanonicalKey('Faded', 'Alan Walker');
        const userProfile = {
            seedArtists: ['Alan Walker'],
            recentTrackKeys: [seedKey],
            excludeKeys: [seedKey]
        };

        // When all providers return empty or fail
        const emptyResult = rankRecommendations([], userProfile, 10);
        assert.equal(emptyResult.length, 0, 'Recommendation list must be empty instead of injected with fallback songs');
    });

    test('Missing artist falls back to title parsing without triggering arbitrary recommendations', () => {
        const parsed = parseArtistAndTitle('Alan Walker - Faded (Official Music Video)', '');
        assert.equal(parsed.artist, 'Alan Walker');
        assert.equal(parsed.title, 'Faded (Official Music Video)');

        const key = extractCanonicalKey('Alan Walker - Faded (Official Music Video)', '');
        assert.equal(key, 'alan walker:::faded');
    });

    test('Captured Faded and Judas provider pools rank differently from their runtime seed identity', () => {
        const fixture = require('./fixtures/staging_candidates_2026-10-22.json');
        const faded = rankRecommendations(fixture.faded, { seedTitle: 'Faded', seedArtists: ['Alan Walker'], excludeKeys: ['alan walker:::faded'] }, 10);
        const judas = rankRecommendations(fixture.judas, { seedTitle: 'Judas', seedArtists: ['Lady Gaga'], excludeKeys: ['lady gaga:::judas'] }, 10);
        assert.ok(faded.length > 0);
        assert.ok(judas.length > 0);
        assert.notDeepEqual(faded.map(item => item.id), judas.map(item => item.id));
        assert.ok(faded.some(item => item.artist === 'Avicii' || item.artist === 'The Chainsmokers'));
        assert.ok(judas.every(item => item.artist.includes('Lady Gaga')));
    });

    test('Duplicate recordings merge provider evidence before scoring', () => {
        const candidates = [
            { id: 'watch-id', youtubeVideoId: 'watch-id', title: 'The Nights', artist: 'Avicii', provider: 'lumione', source: 'watch_next', retrievalSources: ['watch_next'], duration: 220 },
            { id: 'search-id', youtubeVideoId: 'search-id', title: 'Avicii - The Nights (Official Audio)', artist: 'Avicii', provider: 'lumione', source: 'related_artist_search', retrievalSources: ['related_artist_search'], duration: 220 }
        ];
        const unique = deduplicateAndPruneVersions(candidates, { maxPerArtist: 5 });
        assert.equal(unique.length, 1);
        assert.deepEqual(new Set(unique[0].retrievalSources), new Set(['watch_next', 'related_artist_search']));
    });

    test('Generic chart candidates cannot create a radio queue without seed evidence', () => {
        const result = rankRecommendations([
            { id: 'chart-1', title: 'Random Pop Track', artist: 'Unknown Singer', provider: 'saavn', source: 'generic_chart', duration: 200 }
        ], { seedTitle: 'Faded', seedArtists: ['Alan Walker'] }, 10);
        assert.equal(result.length, 0);
    });

    test('Seed recording and named rework variants are excluded from its own queue', () => {
        const fadedKey = extractCanonicalKey('Faded', 'Alan Walker');
        const queue = rankRecommendations([
            { id: 'seed-reupload', title: 'Faded', artist: 'Alan Walker', source: 'watch_next' },
            { id: 'seed-rework', title: 'Faded (Restrung)', artist: 'Alan Walker', source: 'watch_next' },
            { id: 'related', title: 'The Nights', artist: 'Avicii', source: 'related_artist_search' }
        ], { seedTitle: 'Faded', seedArtists: ['Alan Walker'], excludeKeys: [fadedKey] }, 10);
        assert.deepEqual(queue.map(track => track.id), ['related']);
    });

    test('Profiles contain categorical evidence only and no fabricated audio features', () => {
        const profile = buildTrackProfile({ title: 'Faded', artist: 'Alan Walker' });
        assert.equal(profile.primaryGenre, 'electronic_edm');
        assert.equal(profile.provenance, 'artist_taxonomy:alan walker');
        for (const key of ['energy', 'tempo', 'valence', 'danceability', 'acousticness', 'acousticVector', 'popularity']) {
            assert.equal(Object.hasOwn(profile, key), false, `${key} must not be fabricated`);
        }
        const unknown = buildTrackProfile({ title: 'Unknown title', artist: 'Unknown channel' });
        assert.equal(unknown.primaryGenre, null);
        assert.equal(unknown.confidence, 0);
        assert.deepEqual(unknown.moodTags, []);
    });

    test('Strict genre eligibility gate rejects devotional and regional desi tracks for EDM seed', () => {
        const edmProfile = buildTrackProfile({ title: 'Faded', artist: 'Alan Walker' });
        const devotionalProfile = buildTrackProfile({ title: 'Hanuman Chalisa', artist: 'Hariharan' });
        const regionalProfile = buildTrackProfile({ title: 'Casa Tupka Anthem', artist: 'MC Square' });
        const relatedProfile = buildTrackProfile({ title: 'Alone', artist: 'Alan Walker' });

        const gateDev = checkEligibilityGate(edmProfile, devotionalProfile);
        assert.equal(gateDev.eligible, false, 'Devotional must be rejected by gate for EDM seed');
        assert.ok(gateDev.rejectionReason.includes('Incompatible genre family'));

        const gateReg = checkEligibilityGate(edmProfile, regionalProfile);
        assert.equal(gateReg.eligible, false, 'Regional Desi must be rejected by gate for EDM seed');

        const gateEdm = checkEligibilityGate(edmProfile, relatedProfile);
        assert.equal(gateEdm.eligible, true, 'EDM track must be eligible for EDM seed');
    });

    test('Sad/emotional seed preserves emotional mood & rejects upbeat dance or devotional tracks', () => {
        const seedProfile = buildTrackProfile({ title: 'Let Her Go', artist: 'Passenger' });
        const seedKey = extractCanonicalKey('Let Her Go', 'Passenger');

        const candidates = [
            { id: '1', title: 'Someone Like You', artist: 'Adele', duration: 285, source: 'watch_next' },
            { id: '2', title: 'Channa Mereya', artist: 'Arijit Singh', duration: 289, source: 'watch_next' },
            { id: '3', title: 'Dusk Till Dawn (feat. Sia)', artist: 'ZAYN', duration: 239, source: 'watch_next' },
            { id: '4', title: 'Hanuman Chalisa', artist: 'Hariharan', duration: 580, source: 'unrelated_chart' }
        ];

        const userProfile = {
            seedProfile,
            seedTitle: 'Let Her Go',
            seedArtists: ['Passenger'],
            recentTrackKeys: [seedKey],
            excludeKeys: [seedKey]
        };

        const upNext = rankRecommendations(candidates, userProfile, 3);
        const titles = upNext.map(t => t.title);

        assert.equal(titles.includes('Hanuman Chalisa'), false, 'Hanuman Chalisa must NOT be in Up Next for Passenger');
        assert.ok(titles.includes('Someone Like You') || titles.includes('Channa Mereya') || titles.includes('Dusk Till Dawn (feat. Sia)'));
    });

    test('Point score is transparent, bounded, and increases with stronger retrieval evidence', () => {
        const seedProfile = buildTrackProfile({ title: 'Faded', artist: 'Alan Walker' });
        const base = { id: 'aw1', title: 'Alone', artist: 'Alan Walker', duration: 164, source: 'same_artist_catalog' };
        const strong = { ...base, source: 'watch_next', retrievalSources: ['watch_next', 'same_artist_catalog'] };
        const weakScore = scoreTrackProfile(base, seedProfile, { seedArtists: ['Alan Walker'] }, []);
        const strongScore = scoreTrackProfile(strong, seedProfile, { seedArtists: ['Alan Walker'] }, []);
        assert.ok(strongScore.totalScore > weakScore.totalScore);
        assert.deepEqual(Object.keys(strongScore.breakdown), ['relatedEvidence', 'genreCompatibility', 'moodStyleCompatibility', 'artistRelationship', 'contextualSimilarity', 'penalties']);
        assert.ok(strongScore.totalScore >= 0 && strongScore.totalScore <= 100);
        assert.equal(strongScore.breakdown.relatedEvidence, 30);
    });

    test('Devotional seed: compatible devotional candidates; strictly rejects unrelated EDM', () => {
        const seedProfile = buildTrackProfile({ title: 'Hanuman Chalisa', artist: 'Hariharan' });
        const seedKey = extractCanonicalKey('Hanuman Chalisa', 'Hariharan');

        const candidates = [
            { id: '1', title: 'Shree Hanuman Chalisa', artist: 'Gulshan Kumar', duration: 580, source: 'seed_artist' },
            { id: '2', title: 'Shiv Tandav Stotram', artist: 'Anuradha Paudwal', duration: 450, source: 'related' },
            { id: '3', title: 'Aisi Lagi Lagan', artist: 'Anup Jalota', duration: 380, source: 'related' },
            { id: '4', title: 'Levels', artist: 'Avicii', duration: 200, source: 'unrelated' },
            { id: '5', title: 'Faded', artist: 'Alan Walker', duration: 213, source: 'unrelated' },
            { id: '6', title: 'Alone', artist: 'Marshmello', duration: 210, source: 'unrelated' }
        ];

        const userProfile = {
            seedProfile,
            seedTitle: 'Hanuman Chalisa',
            seedArtists: ['Hariharan'],
            recentTrackKeys: [seedKey],
            excludeKeys: [seedKey]
        };

        const upNext = rankRecommendations(candidates, userProfile, 4);
        const titles = upNext.map(t => t.title);

        assert.ok(titles.includes('Shree Hanuman Chalisa'));
        assert.ok(titles.includes('Shiv Tandav Stotram'));
        assert.equal(titles.includes('Levels'), false, 'Avicii must be rejected for Devotional seed');
        assert.equal(titles.includes('Faded'), false, 'Alan Walker must be rejected for Devotional seed');
        assert.equal(titles.includes('Alone'), false, 'Marshmello must be rejected for Devotional seed');
    });

    test('Devotional seed rejects unclassified tracks from a broad catalog singer', () => {
        const seedProfile = buildTrackProfile({ title: 'Hanuman Chalisa', artist: 'Hariharan' });
        const rows = rankRecommendations([
            { id: 'dev-1', title: 'Shree Hanuman Chalisa', artist: 'Hariharan', source: 'same_artist_catalog' },
            { id: 'film-1', title: 'Chinnaga Chinnaga', artist: 'Hariharan', source: 'same_artist_catalog' }
        ], { seedProfile, seedTitle: 'Hanuman Chalisa', seedArtists: ['Hariharan'] }, 10);
        assert.ok(rows.some(row => row.title === 'Shree Hanuman Chalisa'));
        assert.ok(!rows.some(row => row.title === 'Chinnaga Chinnaga'));
        assert.ok(rows.diagnostics.rejections.some(row => row.title === 'Chinnaga Chinnaga'));
    });

    test('Hip-hop and ambient seeds maintain compatible candidates and reject mismatches', () => {
        // Hip-hop
        const hiphopSeed = buildTrackProfile({ title: 'HUMBLE.', artist: 'Kendrick Lamar' });
        const hiphopCand = buildTrackProfile({ title: 'Lose Yourself', artist: 'Eminem' });
        const devotionalCand = buildTrackProfile({ title: 'Hanuman Chalisa', artist: 'Hariharan' });

        const gateHipHop = checkEligibilityGate(hiphopSeed, hiphopCand);
        assert.equal(gateHipHop.eligible, true);

        const gateDevHiphop = checkEligibilityGate(hiphopSeed, devotionalCand);
        assert.equal(gateDevHiphop.eligible, false);

        // Ambient
        const ambientSeed = buildTrackProfile({ title: 'Music for Airports', artist: 'Brian Eno' });
        const lofiCand = buildTrackProfile({ title: 'Coffee Beats', artist: 'Lofi Girl' });
        const festivalEdmCand = buildTrackProfile({ title: 'Animals (Festival Mix)', artist: 'Martin Garrix' });

        const gateAmbient = checkEligibilityGate(ambientSeed, lofiCand);
        assert.equal(gateAmbient.eligible, true);

        const gateEdmAmbient = checkEligibilityGate(ambientSeed, festivalEdmCand);
        assert.equal(gateEdmAmbient.eligible, true, 'The current taxonomy considers compatible electronic adjacent to ambient; no invented energy value is used to reject it');
    });
});
