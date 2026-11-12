import {test} from 'node:test';
import assert from 'node:assert/strict';
import {constants, deflateRawSync, inflateRawSync} from 'node:zlib';
import {encodeLinkText, decodeLinkText, MAX_STATE_BYTES, MAX_LINK_LENGTH} from '../../main/resources/site/src/components/ReactionViewer/linkCodec.mjs';
import {compactState, expandState, MAX_REPLAY_STEPS} from '../../main/resources/site/src/components/ReactionViewer/linkState.mjs';

const entry = {kind: 'SYSTEM', system: 'orders'};
const target = {kind: 'interface', key: 'opaque-1', identity: {representation: 'MESSAGE', type: 'event',
    fqn: 'orders.OrderPlacedEvent', message: ['OrderPlacedEvent', 'express'], http: null}};
const state = steps => ({version: 1, context: {site: 'handbook', environment: 'dev', baseUrl: '/docs/site/handbook/',
    path: '/docs/site/handbook/dev/reactions/'}, viewer: entry, entry, steps});

test('DEFLATE raw round-trips the exact JSON text and interoperates with an independent compressor', async () => {
    const source = ' {\n "z": 1.2300, "a": "Grüsse / 注文", "numbers": [1e-7, -0, 9007199254740993] \n}\n';
    const token = await encodeLinkText(source);
    assert.match(token, /^d1\.[A-Za-z0-9_-]+$/);
    assert.equal(await decodeLinkText(token), source);
    assert.equal(inflateRawSync(Buffer.from(token.slice(3), 'base64url')).toString('utf8'), source);
    const independentlyCompressed = 'd1.' + deflateRawSync(Buffer.from(source), {level: 9}).toString('base64url');
    assert.equal(await decodeLinkText(independentlyCompressed), source);
    for (const options of [{level: 0}, {strategy: constants.Z_FIXED}]) {
        const alternative = 'd1.' + deflateRawSync(Buffer.from(source), options).toString('base64url');
        assert.equal(await decodeLinkText(alternative), source);
    }
    await assert.rejects(() => decodeLinkText(Buffer.from(source).toString('base64url')));
});

test('the compact schema retains every action shape, context and viewport without changing semantic references', async () => {
    const original = state([
        {type: 'reset'}, {type: 'entry', entry: {kind: 'COMPONENT', system: 'orders', component: 'dispatcher'}},
        {type: 'paths', targets: [target, {...target, key: 'opaque-2'}]},
        {type: 'show', target}, {type: 'show', targets: [target]},
        {type: 'expand', target, direction: 'incoming', depth: 3},
        {type: 'expand', target, direction: 'outgoing', depth: 4},
        ...['focus', 'pin', 'hide', 'outgoing', 'connected', 'collapse', 'expand-group'].map(type => ({type, target})),
    ]);
    original.presentation = {zoom: 1.23456789, center: {x: -77.125, y: 400.75}};
    const packed = compactState(original);
    assert.equal(packed.v, 1);
    assert.equal(packed.w, packed.e);
    assert.equal(packed.r.length, 4);
    assert.deepEqual(expandState(packed), original);
    const restored = JSON.parse(await decodeLinkText(await encodeLinkText(JSON.stringify(packed))));
    assert.deepEqual(expandState(restored), original);
    assert.throws(() => expandState(original), /Invalid compact/);
});

test('repeated targets occupy one dictionary entry and thousands of steps compress to a short token', async () => {
    const original = state(Array.from({length: MAX_REPLAY_STEPS}, () => ({type: 'pin', target})));
    const packed = compactState(original), full = JSON.stringify(original), compact = JSON.stringify(packed);
    assert.equal(packed.r.length, 2);
    assert.deepEqual(packed.s[0], [6, 1]);
    assert.deepEqual(packed.s.at(-1), [6, 1]);
    assert.ok(compact.length < full.length / 10);
    const token = await encodeLinkText(compact);
    assert.ok(token.length < 600);
    assert.deepEqual(expandState(JSON.parse(await decodeLinkText(token))), original);
});

test('decompression is bounded before parsing even when the compressed input is tiny', async () => {
    const token = 'd1.' + deflateRawSync(Buffer.from('x'.repeat(MAX_STATE_BYTES + 1))).toString('base64url');
    assert.ok(token.length < 1000);
    await assert.rejects(() => decodeLinkText(token), /maximum state size/);
    await assert.rejects(() => encodeLinkText('ä'.repeat(MAX_STATE_BYTES / 2 + 1)), /maximum state size/);
    const exact = 'x'.repeat(MAX_STATE_BYTES);
    assert.equal(await decodeLinkText(await encodeLinkText(exact)), exact);
});

test('invalid, unsupported and truncated compressed data are rejected', async () => {
    const token = await encodeLinkText('{"message":"OrderPlacedEvent"}');
    for (const invalid of ['', 'd1.', 'd2.abc', 'd1.%', 'd1.a', token.slice(0, -6), 'a'.repeat(MAX_LINK_LENGTH + 1)]) {
        await assert.rejects(() => decodeLinkText(invalid));
    }
    // Node's native decoder accepts trailing bytes; Chrome's rejection is covered by ReactionViewerBrowserIT.
    await assert.rejects(() => decodeLinkText(Buffer.from([255]).toString('base64url')));
});

test('invalid opcodes, references and tuple shapes cannot silently change a compact action', () => {
    const packed = compactState(state([{type: 'pin', target}]));
    for (const mutate of [
        p => { p.v = 4; }, p => { p.v = 99; }, p => { p.c.pop(); }, p => { p.c[0] = null; }, p => { p.r = null; },
        p => { p.w = -1; }, p => { p.e = 1.5; }, p => { p.w = 900; }, p => { p.r[0] = []; },
        p => { p.s = null; }, p => { p.s[0] = [99, 1]; }, p => { p.s[0] = [6, -1]; },
        p => { p.s[0] = [0, 1]; }, p => { p.s[0] = [6, 1, 2]; }, p => { p.s[0] = [4, 1, 2, 3]; },
        p => { p.s[0] = [2, 1]; }, p => { p.s[0] = [3, [999]]; }, p => { p.p = [1, 2]; },
        p => { p.unknown = true; }, p => { p.s = Array.from({length: MAX_REPLAY_STEPS + 1}, () => [0]); },
    ]) {
        const invalid = structuredClone(packed); mutate(invalid);
        assert.throws(() => expandState(invalid), /Invalid compact/);
    }
    assert.throws(() => compactState(state([{type: 'expand', target, direction: 'sideways', depth: 1}])), /Invalid compact/);
    assert.throws(() => compactState(state([{type: 'unknown', target}])), /Invalid compact/);
});
