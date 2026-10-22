const {
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile,
    rankRecommendations
} = require('../recommender');

console.log('========================================================================');
console.log('REAL-WORLD MUSIC INTELLIGENCE & RECOMMENDATION VERIFICATION');
console.log('========================================================================\n');

// ── Test Seed 1: Alan Walker — Faded (Melodic EDM) ────────────────────────
console.log('--- TEST SEED 1: Alan Walker — Faded (Melodic EDM) ---');
const seed1Profile = buildTrackProfile({ title: 'Faded', artist: 'Alan Walker' });
console.log('Seed 1 Profile:', {
    genre: seed1Profile.primaryGenre,
    subgenres: seed1Profile.subgenres,
    mood: seed1Profile.moodTags,
    energy: seed1Profile.energy,
    valence: seed1Profile.valence,
    danceability: seed1Profile.danceability,
    acousticness: seed1Profile.acousticness,
    provenance: seed1Profile.provenance
});

const candidates1 = [
    // EDM & Melodic Electronic
    { id: 'aw_alone', title: 'Alone', artist: 'Alan Walker', duration: 164, source: 'seed_artist' },
    { id: 'aw_onmyway', title: 'On My Way', artist: 'Alan Walker', duration: 193, source: 'seed_artist' },
    { id: 'avicii_levels', title: 'Levels', artist: 'Avicii', duration: 200, source: 'related_search' },
    { id: 'cs_closer', title: 'Closer', artist: 'The Chainsmokers', duration: 245, source: 'watch_next' },
    { id: 'marsh_happier', title: 'Happier', artist: 'Marshmello', duration: 215, source: 'watch_next' },
    { id: 'kygo_stargazing', title: 'Stargazing', artist: 'Kygo', duration: 236, source: 'watch_next' },
    { id: 'zayn_dusk', title: 'Dusk Till Dawn', artist: 'ZAYN ft. Sia', duration: 239, source: 'watch_next' },

    // Incompatible tracks that previously polluted the queue
    { id: 'hanuman', title: 'Hanuman Chalisa', artist: 'Hariharan, Gulshan Kumar', duration: 580, source: 'unrelated_chart' },
    { id: 'parvati', title: 'Parvati', artist: 'Hansraj Raghuwanshi', duration: 245, source: 'unrelated_chart' },
    { id: 'casa_tupka', title: 'Casa Tupka Anthem', artist: 'MC Square', duration: 190, source: 'unrelated_chart' },
    { id: 'tujh_mein', title: 'Tujh Mein Basi Meri Jaan', artist: 'B Praak', duration: 280, source: 'unrelated_chart' }
];

const results1 = rankRecommendations(candidates1, {
    seedProfile: seed1Profile,
    seedArtists: ['Alan Walker'],
    recentTrackKeys: ['alan walker:::faded']
}, 5);

console.log('\nTop 5 Recommendations for Alan Walker — Faded:');
results1.forEach((t, i) => {
    console.log(`  ${i + 1}. "${t.title}" by ${t.artist} | Score: ${t.recommendationScore} | Reason: ${t.recommendationReason} | Genre: ${t.genre}`);
});

// Verify no Indian chart/devotional tracks leaked
const leakedDevotional = results1.some(t => ['Hanuman Chalisa', 'Parvati', 'Casa Tupka Anthem', 'Tujh Mein Basi Meri Jaan'].includes(t.title));
console.log('Devotional/Regional Leaked?', leakedDevotional ? 'FAIL: LEAKED!' : 'PASS: Zero Incompatible Leaks');


// ── Test Seed 2: Hariharan — Hanuman Chalisa (Devotional) ─────────────────
console.log('\n--- TEST SEED 2: Hariharan — Hanuman Chalisa (Devotional) ---');
const seed2Profile = buildTrackProfile({ title: 'Hanuman Chalisa', artist: 'Hariharan' });
console.log('Seed 2 Profile:', {
    genre: seed2Profile.primaryGenre,
    subgenres: seed2Profile.subgenres,
    mood: seed2Profile.moodTags,
    energy: seed2Profile.energy,
    provenance: seed2Profile.provenance
});

const candidates2 = [
    { id: 'h1', title: 'Shree Hanuman Chalisa', artist: 'Gulshan Kumar', duration: 580, source: 'seed_artist' },
    { id: 'h2', title: 'Shiv Tandav Stotram', artist: 'Anuradha Paudwal', duration: 420, source: 'watch_next' },
    { id: 'h3', title: 'Aisi Lagi Lagan', artist: 'Anup Jalota', duration: 380, source: 'watch_next' },
    // EDM tracks that must NOT leak into Devotional session
    { id: 'aw_faded', title: 'Faded', artist: 'Alan Walker', duration: 213, source: 'unrelated' },
    { id: 'mg_animals', title: 'Animals', artist: 'Martin Garrix', duration: 180, source: 'unrelated' }
];

const results2 = rankRecommendations(candidates2, {
    seedProfile: seed2Profile,
    seedArtists: ['Hariharan'],
    recentTrackKeys: ['hariharan:::hanuman chalisa']
}, 3);

console.log('\nTop Recommendations for Hanuman Chalisa:');
results2.forEach((t, i) => {
    console.log(`  ${i + 1}. "${t.title}" by ${t.artist} | Score: ${t.recommendationScore} | Reason: ${t.recommendationReason} | Genre: ${t.genre}`);
});

const leakedEdm = results2.some(t => ['Faded', 'Animals'].includes(t.title));
console.log('EDM Leaked into Devotional?', leakedEdm ? 'FAIL: LEAKED!' : 'PASS: Zero EDM Leaks');


// ── Test Seed 3: Passenger — Let Her Go (Sad / Emotional Acoustic Pop) ───
console.log('\n--- TEST SEED 3: Passenger — Let Her Go (Sad/Emotional Pop) ---');
const seed3Profile = buildTrackProfile({ title: 'Let Her Go', artist: 'Passenger' });
console.log('Seed 3 Profile:', {
    genre: seed3Profile.primaryGenre,
    subgenres: seed3Profile.subgenres,
    mood: seed3Profile.moodTags,
    energy: seed3Profile.energy,
    acousticness: seed3Profile.acousticness,
    provenance: seed3Profile.provenance
});

const candidates3 = [
    { id: 'p1', title: 'Someone Like You', artist: 'Adele', duration: 285, source: 'watch_next' },
    { id: 'p2', title: 'Photograph', artist: 'Ed Sheeran', duration: 258, source: 'watch_next' },
    { id: 'p3', title: 'All of Me', artist: 'John Legend', duration: 269, source: 'watch_next' },
    { id: 'p4', title: 'Animals (Club Mix)', artist: 'Martin Garrix', duration: 180, source: 'unrelated' },
    { id: 'p5', title: 'Hanuman Chalisa', artist: 'Hariharan', duration: 580, source: 'unrelated' }
];

const results3 = rankRecommendations(candidates3, {
    seedProfile: seed3Profile,
    seedArtists: ['Passenger'],
    recentTrackKeys: ['passenger:::let her go']
}, 3);

console.log('\nTop Recommendations for Passenger — Let Her Go:');
results3.forEach((t, i) => {
    console.log(`  ${i + 1}. "${t.title}" by ${t.artist} | Score: ${t.recommendationScore} | Reason: ${t.recommendationReason} | Genre: ${t.genre}`);
});

console.log('\n========================================================================\n');
