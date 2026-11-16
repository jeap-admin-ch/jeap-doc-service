import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createExplorerAvailability} from '../../main/resources/site/src/components/ReactionExplorer/availability.mjs';

const status = {formatVersion: 1, site: 'docs', environment: 'dev', available: true};
const route = '/site/docs/dev/reactions/';
const html = () => new Response(null, {headers: {'content-type': 'text/html;charset=UTF-8'}});
const storage = () => {
    const values = new Map();
    return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value)};
};

test('navigation requires a published snapshot and route, sharing concurrent requests and respecting HTTP caching', async () => {
    const requests = [];
    const check = createExplorerAvailability({storage, fetcher: async (url, options) => {
        requests.push([url, options]);
        return url === route ? html() : Response.json(status);
    }});
    const marker = '/ready/reaction-explorer.json';
    const results = await Promise.all(Array.from({length: 3}, () => check(marker, route, 'docs', 'dev')));
    assert.deepEqual(results, [true, true, true]);
    assert.deepEqual(requests, [[marker, {redirect: 'error'}], [route, {method: 'HEAD', redirect: 'error'}]]);
});

test('old shells, missing imports and unrelated markers never advertise an Explorer', async () => {
    const responses = [new Response(null, {status: 404}), Response.json({...status, available: false}),
        Response.json({...status, site: 'other'}), Response.json({...status, environment: 'prod'}),
        Response.json({...status, formatVersion: 2}), new Response('<html>Login</html>')];
    for (const response of responses) {
        let calls = 0;
        const check = createExplorerAvailability({storage, fetcher: async () => { calls++; return response; }});
        assert.equal(await check('/missing', route, 'docs', 'dev'), false);
        assert.equal(calls, 1);
    }
    const offline = createExplorerAvailability({storage, fetcher: async () => { throw new Error('offline'); }});
    assert.equal(await offline('/offline', route, 'docs', 'dev'), false);
});

test('a system part with another route cannot advertise a missing page even with a ready marker', async () => {
    for (const response of [new Response(null, {status: 404}), Response.json({})]) {
        const check = createExplorerAvailability({storage, fetcher: async (_url, options) =>
            options.method === 'HEAD' ? response : Response.json(status)});
        assert.equal(await check('/wrong-route', route, 'docs', 'dev'), false);
    }
});

test('positive results survive full-page navigation for ten minutes, then refresh', async () => {
    const tab = storage();
    let time = 1000, requests = 0;
    const createPage = () => createExplorerAvailability({storage: () => tab, now: () => time,
        fetcher: async (_url, options) => { requests++; return options.method === 'HEAD' ? html() : Response.json(status); }});
    const firstPage = createPage();
    assert.equal(await firstPage('/marker', route, 'docs', 'dev'), true);
    time += 6_000;
    assert.equal(await firstPage('/marker', route, 'docs', 'dev'), true);
    time += 593_999;
    assert.equal(await createPage()('/marker', route, 'docs', 'dev'), true);
    assert.equal(requests, 2);
    time++;
    assert.equal(await createPage()('/marker', route, 'docs', 'dev'), true);
    assert.equal(requests, 4);
});

test('negative results survive navigation for one minute and then discover a later publication', async () => {
    const tab = storage();
    let time = 1000, available = false, requests = 0;
    const createPage = () => createExplorerAvailability({storage: () => tab, now: () => time,
        fetcher: async (_url, options) => {
            requests++; return options.method === 'HEAD' ? html() : Response.json({...status, available});
        }});
    assert.equal(await createPage()('/marker', route, 'docs', 'dev'), false);
    available = true; time += 59_999;
    assert.equal(await createPage()('/marker', route, 'docs', 'dev'), false);
    assert.equal(requests, 1);
    time++;
    assert.equal(await createPage()('/marker', route, 'docs', 'dev'), true);
    assert.equal(requests, 3);
});

test('stored results are scoped to the site, environment and route', async () => {
    const tab = storage();
    let requests = 0;
    const check = createExplorerAvailability({storage: () => tab, fetcher: async (_url, options) => {
        requests++; return options.method === 'HEAD' ? html() : Response.json(status);
    }});
    assert.equal(await check('/marker', route, 'docs', 'dev'), true);
    assert.equal(await check('/marker', route, 'other', 'dev'), false);
    assert.equal(await check('/marker', route, 'docs', 'prod'), false);
    assert.equal(requests, 4);
    assert.equal(await check('/marker', '/site/docs/dev/_reactions/', 'docs', 'dev'), true);
    assert.equal(requests, 6);
});

test('blocked or corrupt browser storage keeps the in-memory cache usable', async () => {
    for (const unavailableStorage of [() => { throw new Error('blocked'); },
        () => ({getItem: () => '{invalid json', setItem: () => { throw new Error('full'); }})]) {
        let requests = 0;
        const check = createExplorerAvailability({storage: unavailableStorage, fetcher: async (_url, options) => {
            requests++; return options.method === 'HEAD' ? html() : Response.json(status);
        }});
        assert.equal(await check('/marker', route, 'docs', 'dev'), true);
        assert.equal(await check('/marker', route, 'docs', 'dev'), true);
        assert.equal(requests, 2);
    }
});
