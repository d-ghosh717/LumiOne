#!/usr/bin/env node
// Live evaluation client. Reports provider-returned IDs and server explanations; it does not supply recommendations.
const baseUrl = (process.argv.find(arg => arg.startsWith('--url=')) || '--url=https://lumione-nextupdate.onrender.com').slice(6).replace(/\/$/, '');
const seeds = [
    { title: 'Faded', artist: 'Alan Walker', videoId: '60ItHLz5WEA' },
    { title: 'Judas', artist: 'Lady Gaga', videoId: 'wagn8Wrmzuc' },
    { title: 'On the Floor', artist: 'Jennifer Lopez', videoId: 't4H_Zoh7G5A' },
    { title: 'Hanuman Chalisa', artist: 'Hariharan', videoId: '' },
    { title: 'Let Her Go', artist: 'Passenger', videoId: 'RBumgq5yVrA' }
];

async function main() {
    for (const seed of seeds) {
        const url = new URL(`${baseUrl}/api/recommendations/related`);
        const seedKey = `${seed.artist.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim()}:::${seed.title.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim()}`;
        Object.entries({ ...seed, recentKeys: JSON.stringify([seedKey]), limit: '10' }).forEach(([key, value]) => { if (value) url.searchParams.set(key, value); });
        console.log(`\nSEED ${seed.title} — ${seed.artist}`);
        try {
            const response = await fetch(url, { signal: AbortSignal.timeout(45000) });
            const body = await response.json();
            console.log(`HTTP ${response.status}; seedProfile=${JSON.stringify(body.seedProfile || {})}`);
            console.log(`Candidates per source: ${JSON.stringify(body.diagnostics?.sourceRuns || {})}`);
            console.log(`Eligibility rejections: ${JSON.stringify(body.diagnostics?.rejections || [])}`);
            for (const candidate of body.results || []) {
                console.log(`${candidate.youtubeVideoId || candidate.id}\t${candidate.title}\t${candidate.artist}\tscore=${candidate.recommendationScore}\tsources=${(candidate.retrievalSources || [candidate.source]).join(',')}\tbreakdown=${JSON.stringify(candidate.scoreBreakdown)}\tgenre=${candidate.profile?.primaryGenre || 'unknown'}\tmood=${(candidate.profile?.moodTags || []).join(',') || 'unknown'}\tconfidence=${candidate.profile?.confidence ?? 0}\tprovenance=${candidate.profile?.provenance || 'missing'}`);
            }
            if (!(body.results || []).length) console.log('Final ranked queue: empty (no eligible candidates retrieved).');
        } catch (error) {
            console.log(`REQUEST ERROR: ${error.name}: ${error.message}`);
        }
    }
}
main();
