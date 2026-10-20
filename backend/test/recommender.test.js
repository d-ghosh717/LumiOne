const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const {
    isMusicContent,
    cleanTitle,
    cleanArtist,
    extractCanonicalKey,
    detectModifier,
    deduplicateAndPruneVersions,
    deduplicateForQueue,
    scoreCandidate,
    rankRecommendations
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
});
