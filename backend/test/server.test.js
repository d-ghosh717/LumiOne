const { test, before, after, afterEach } = require('node:test');
const { once } = require('node:events');
const assert = require('node:assert/strict');
const { app } = require('../server');

let server;
let baseUrl;
before(async () => {
    server = app.listen(0, '127.0.0.1');
    await once(server, 'listening');
    baseUrl = `http://127.0.0.1:${server.address().port}`;
});
const originalProviders = app.locals.recommendationProviders;

after(() => server.close());
afterEach(() => {
    if (originalProviders === undefined) delete app.locals.recommendationProviders;
    else app.locals.recommendationProviders = originalProviders;
});

async function requestRelated(title, artist, videoId) {
    const url = new URL(`${baseUrl}/api/recommendations/related`);
    url.searchParams.set('title', title);
    url.searchParams.set('artist', artist);
    url.searchParams.set('limit', '10');
    if (videoId) url.searchParams.set('videoId', videoId);
    const response = await fetch(url);
    return { status: response.status, body: await response.json() };
}

test('Related endpoint calls seed-dependent providers and returns different ranked queues', async () => {
    app.locals.recommendationProviders = {
        youtubeSearch: async query => {
            const q = query.toLowerCase();
            if (q.includes('alan walker songs')) return [{ id: 'aw-track-001', youtubeVideoId: 'aw-track-001', title: 'Alan Walker - Sing Me To Sleep', artist: 'Alan Walker', duration: 210, provider: 'lumione' }];
            if (q.includes('avicii songs')) return [{ id: 'av-track-001', youtubeVideoId: 'av-track-001', title: 'Avicii - The Nights', artist: 'Avicii', duration: 220, provider: 'lumione' }];
            if (q.includes('lady gaga songs')) return [{ id: 'gaga-track-001', youtubeVideoId: 'gaga-track-001', title: 'Lady Gaga - Americano', artist: 'Lady Gaga', duration: 200, provider: 'lumione' }];
            return [];
        },
        fetchYouTubeWatchNext: async id => id === 'fadedvid001' ? [
            { id: 'av-watch-001', youtubeVideoId: 'av-watch-001', title: 'Avicii - Wake Me Up', artist: 'Avicii', duration: 240, provider: 'lumione' }
        ] : [],
        fetchJioSaavnArtistSongs: async () => []
    };
    const faded = await requestRelated('Faded', 'Alan Walker', 'fadedvid001');
    const judas = await requestRelated('Judas', 'Lady Gaga', 'judasvid001');
    assert.equal(faded.status, 200);
    assert.equal(judas.status, 200);
    assert.ok(faded.body.results.length > 0);
    assert.ok(judas.body.results.length > 0);
    assert.notDeepEqual(faded.body.results.map(x => x.youtubeVideoId), judas.body.results.map(x => x.youtubeVideoId));
    assert.ok(faded.body.results.some(x => x.artist === 'Avicii'));
    assert.ok(judas.body.results.some(x => x.artist === 'Lady Gaga'));
    assert.ok(faded.body.results.every(x => x.scoreBreakdown && x.recommendationScore > 0));
    assert.ok(faded.body.diagnostics.sourceRuns.some(x => x.source === 'watch_next' && x.count > 0));
});

test('Failed providers return an empty limited result and never inject global charts', async () => {
    app.locals.recommendationProviders = {
        youtubeSearch: async () => { throw new Error('fixture provider unavailable'); },
        fetchYouTubeWatchNext: async () => { throw new Error('fixture provider unavailable'); },
        fetchJioSaavnArtistSongs: async () => { throw new Error('fixture provider unavailable'); }
    };
    const result = await requestRelated('Faded', 'Alan Walker', 'fadedvid001');
    assert.equal(result.status, 200);
    assert.deepEqual(result.body.results, []);
    assert.equal(result.body.returnedCount, 0);
    assert.ok(result.body.diagnostics.sourceRuns.some(x => x.status === 'failed'));
    assert.ok(result.body.results.every(x => x.source !== 'generic_chart'));
});

test('Profile recommendations without seed or preference candidates return empty instead of charts', async () => {
    const response = await fetch(`${baseUrl}/api/recommendations`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ limit: 10 })
    });
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.deepEqual(body.results, []);
});
