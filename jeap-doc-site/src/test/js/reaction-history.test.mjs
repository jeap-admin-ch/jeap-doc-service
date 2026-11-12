import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readGraph, identity} from '../../main/resources/site/src/components/ReactionViewer/model.mjs';
import {startHistory, advanceHistory, backHistory, HISTORY_LIMIT} from '../../main/resources/site/src/components/ReactionViewer/history.mjs';
import {shareLink, restoreLink, LINK_PREFIX, MAX_LINK_LENGTH, referenceOf} from '../../main/resources/site/src/components/ReactionViewer/sharing.mjs';
import {decodeLinkText} from '../../main/resources/site/src/components/ReactionViewer/linkCodec.mjs';
import {expandState, MAX_REPLAY_STEPS} from '../../main/resources/site/src/components/ReactionViewer/linkState.mjs';
import {sharedPresentation, validPresentation, presentationPan} from '../../main/resources/site/src/components/ReactionViewer/presentation.mjs';

const iid = key => identity({nodeType: 'INTERFACE', key});
const rid = key => identity({nodeType: 'REACTION', key});
const entry = {kind: 'SYSTEM', system: 'orders'};
const context = {site: 'handbook', environment: 'dev', baseUrl: '/docs/site/handbook/', path: '/docs/site/handbook/dev/reactions/'};
const location = 'https://docs.example.test' + context.path;
function fixture() {
    const node = (key, reaction = null) => ({identity: {nodeType: reaction ? 'REACTION' : 'INTERFACE', key},
        nodeType: reaction ? 'REACTION' : key === 'http' ? 'HTTP_OPERATION' : key === 'generic' ? 'OTHER' : 'MESSAGE',
        ...(reaction ? {reaction} : {interfaceDetails: key === 'http' ? {http: {provider: 'api', method: 'GET', path: '/orders'}}
            : key === 'generic' ? {interfaceType: 'OTHER', fqn: 'generic'} : {message: {messageType: key, variant: ''}}})});
    const reaction = (key, groupKey, status = 'ACTIVE') => node(key,
        {system: 'orders', component: 'dispatcher', groupKey, median: 10, status, reactionModelVersion: 2});
    const edge = (from, to, type) => ({edgeType: type,
        source: {nodeType: type === 'ACTION' ? 'REACTION' : 'INTERFACE', key: from},
        target: {nodeType: type === 'TRIGGER' ? 'REACTION' : 'INTERFACE', key: to}});
    return {formatVersion: 1, site: context.site, environment: context.environment, available: true, ownershipComplete: true,
        graph: {nodes: [node('A'), node('http'), node('generic'), reaction('r1', 'start'), reaction('r2', 'next'), reaction('old', 'next', 'SUPERSEDED')],
            edges: [edge('A', 'r1', 'TRIGGER'), edge('r1', 'http', 'ACTION'), edge('http', 'r2', 'TRIGGER'),
                edge('r2', 'generic', 'ACTION'), edge('http', 'old', 'TRIGGER'), edge('old', 'generic', 'ACTION')]}};
}
const read = payload => readGraph(payload, context.site, context.environment);
const graph = () => read(fixture());
const link = (g, session, viewer = entry) => shareLink(g, session, context, viewer, location);
const restore = async (g, url, viewer = entry, scope = context) => restoreLink(g, new URL(await url).hash, scope, viewer);
const apply = (g, session, type, id, extra = {}) => advanceHistory(g, session, {type, id, ...extra});
const encode = payload => location + LINK_PREFIX + Buffer.from(JSON.stringify(payload)).toString('base64url');
const payloadOf = async url => expandState(JSON.parse(await decodeLinkText(new URL(await url).hash.slice(LINK_PREFIX.length))));

test('combined actions, group state, superseded reveals, focus and reset each undo completely', async () => {
    const g = graph(), initial = startHistory(g, entry), group = g.groupOf.get(rid('r2'));
    let session = initial;
    const actions = [{type: 'collapse', id: group}, {type: 'show', id: rid('old')}, {type: 'pin', id: rid('old')},
        {type: 'hide', id: group}, {type: 'focus', id: iid('A')}, {type: 'expand', id: iid('A'), direction: 'outgoing', depth: 4},
        {type: 'paths', ids: [iid('A'), iid('generic')]}, {type: 'reset'}];
    const preceding = [];
    for (const action of actions) { preceding.push(session); session = advanceHistory(g, session, action); }
    for (const before of preceding.reverse()) {
        session = backHistory(session);
        assert.deepEqual(session, before);
    }
    assert.deepEqual(session, initial);
});

test('search jumps replace the reset baseline, while focus preserves it and Back restores it', async () => {
    const g = graph(), initial = apply(g, startHistory(g, entry), 'hide', iid('generic'));
    const jumped = advanceHistory(g, initial, {type: 'entry', entry: {kind: 'INTERFACE', id: iid('http')}});
    const focused = apply(g, jumped, 'focus', rid('r1'));
    const reset = advanceHistory(g, focused, {type: 'reset'});
    assert.deepEqual(reset.state, jumped.state);
    assert.deepEqual(backHistory(reset), focused);
    assert.deepEqual(backHistory(backHistory(reset)), jumped);
    assert.deepEqual(backHistory(jumped), initial);
    const oldReset = advanceHistory(g, backHistory(jumped), {type: 'reset'});
    assert.deepEqual(oldReset.state, startHistory(g, entry).state);
    assert.deepEqual((await restore(g, link(g, reset))).state, reset.state);
    assert.deepEqual((await restore(g, link(g, reset))).baseline, reset.baseline);
});

test('the newest hundred undo steps are independent of the full replay script and other viewers', async () => {
    const g = graph(), other = startHistory(g, {kind: 'COMPONENT', system: 'orders', component: 'dispatcher'});
    let session = startHistory(g, entry);
    for (let i = 0; i < 105; i++) session = advanceHistory(g, session,
        i % 2 ? {type: 'reset'} : {type: 'focus', id: iid('A')});
    assert.equal(session.history.length, HISTORY_LIMIT);
    assert.equal(session.steps.length, 105);
    const restored = await restore(g, link(g, session));
    assert.equal(restored.history.length, 0);
    assert.equal(restored.steps.length, 105);
    assert.deepEqual(restored.state, session.state);
    for (let i = 0; i < HISTORY_LIMIT; i++) session = backHistory(session);
    assert.equal(session.steps.length, 5);
    assert.strictEqual(backHistory(session), session);
    assert.deepEqual(other, startHistory(g, other.entry));
});

test('semantic no-ops do not consume history and Back branches the replay script', async () => {
    const g = graph(), initial = startHistory(g, entry);
    assert.strictEqual(advanceHistory(g, initial, {type: 'reset'}), initial);
    const pinned = apply(g, initial, 'pin', iid('A'));
    const changed = apply(g, backHistory(apply(g, pinned, 'hide', iid('generic'))), 'focus', iid('A'));
    assert.equal(changed.steps.length, 2);
    assert.deepEqual((await restore(g, link(g, changed))).state, changed.state);
});

test('Back restores each semantic checkpoint with its preceding viewport and node positions', async () => {
    const g = graph(), initial = startHistory(g, entry);
    const firstViewport = {zoom: 1.4, pan: {x: -90, y: 210}, width: 1000, height: 600,
        positions: [[iid('A'), {x: 500, y: 45}]]};
    const first = advanceHistory(g, initial, {type: 'pin', id: iid('A')}, firstViewport);
    const secondViewport = {...firstViewport, zoom: .7, pan: {x: -300, y: 17}};
    const second = advanceHistory(g, first, {type: 'reset'}, secondViewport);
    assert.deepEqual(backHistory(second).state, first.state);
    assert.deepEqual(backHistory(second).presentation, secondViewport);
    assert.deepEqual(backHistory(backHistory(second)).state, initial.state);
    assert.deepEqual(backHistory(backHistory(second)).presentation, firstViewport);
    assert.equal(first.history.length, 1);
    assert.equal(second.history.length, 2);
    assert.equal(first.presentation, undefined);
    assert.deepEqual(firstViewport.positions, [[iid('A'), {x: 500, y: 45}]]);
});

test('replay uses stable interface keys, group keys and unique reaction signatures across changed IDs and medians', async () => {
    const g = graph();
    let session = apply(g, startHistory(g, entry), 'show', rid('old'));
    session = apply(g, session, 'pin', rid('old'));
    session = apply(g, session, 'collapse', g.groupOf.get(rid('old')));
    session = apply(g, session, 'hide', iid('generic'));
    const url = link(g, session), changed = fixture();
    changed.graph.nodes.find(n => n.identity.key === 'old').identity.key = 'new-opaque-id';
    changed.graph.nodes.find(n => n.identity.key === 'r1').reaction.median = 900;
    for (const edge of changed.graph.edges) for (const endpoint of ['source', 'target']) {
        if (edge[endpoint].key === 'old') edge[endpoint].key = 'new-opaque-id';
    }
    const restored = await restore(read(changed), url);
    assert.ok(restored.state.pins.has(rid('new-opaque-id')));
    assert.ok(restored.state.visible.has(rid('new-opaque-id')));
    assert.ok(restored.state.revealed.has(rid('new-opaque-id')));
    assert.ok(restored.state.hidden.has(iid('generic')));
    assert.equal(restored.history.length, 0);
    assert.deepEqual((await payloadOf(url)).steps[1].target, {kind: 'reaction',
        group: {kind: 'group', system: 'orders', component: 'dispatcher', key: 'next', trigger: null},
        trigger: [{representation: 'HTTP_OPERATION', type: null, fqn: null, message: null, http: ['api', 'GET', '/orders']}],
        actions: [{representation: 'OTHER', type: 'OTHER', fqn: 'generic', message: null, http: null}],
        model: 2, status: 'SUPERSEDED'});
});

test('missing and ambiguous targets, missing entries and inapplicable steps reject the entire replay', async () => {
    const g = graph(), session = apply(g, apply(g, startHistory(g, entry), 'pin', iid('A')), 'pin', rid('r1'));
    const url = link(g, session), original = structuredClone(session);
    for (const mutate of [
        p => { p.graph.nodes = p.graph.nodes.filter(n => n.identity.key !== 'r1'); p.graph.edges = p.graph.edges.filter(e => e.source.key !== 'r1' && e.target.key !== 'r1'); },
        p => { const duplicate = structuredClone(p.graph.nodes.find(n => n.identity.key === 'r1')); duplicate.identity.key = 'duplicate'; p.graph.nodes.push(duplicate);
            p.graph.edges.push(...p.graph.edges.filter(e => e.source.key === 'r1' || e.target.key === 'r1').map(e => JSON.parse(JSON.stringify(e).replaceAll('r1', 'duplicate')))); },
    ]) {
        const changed = fixture(); mutate(changed);
        await assert.rejects(() => restore(read(changed), url), /cannot be restored/);
    }
    const payload = await payloadOf(url);
    payload.entry.system = 'absent';
    await assert.rejects(() => restore(g, encode(payload)), /cannot be restored/);
    const hiddenTwice = await payloadOf(link(g, apply(g, startHistory(g, entry), 'hide', iid('generic'))));
    hiddenTwice.steps.push(hiddenTwice.steps[0]);
    await assert.rejects(() => restore(g, encode(hiddenTwice)), /cannot be restored/);
    assert.deepEqual(session, original);
});

test('malformed and unsupported links fail safely and site, environment and base paths cannot be crossed', async () => {
    const g = graph(), url = link(g, startHistory(g, entry));
    for (const hash of [LINK_PREFIX, LINK_PREFIX + '%', LINK_PREFIX + 'a'.repeat(MAX_LINK_LENGTH), LINK_PREFIX + 'e30']) {
        await assert.rejects(() => restoreLink(g, hash, context, entry), /cannot be restored/);
    }
    for (const change of [{site: 'another'}, {environment: 'prod'}, {baseUrl: '/'}, {path: '/another/'}]) {
        await assert.rejects(() => restore(g, url, entry, {...context, ...change}), /cannot be restored/);
    }
    const payload = await payloadOf(url); payload.version = 99;
    await assert.rejects(() => restore(g, encode(payload)), /cannot be restored/);
    assert.equal(await restoreLink(g, '#ordinary-heading', context, entry), null);
    assert.equal(await restore(g, url, {kind: 'MESSAGE', message: 'A', variant: ''}), null);
});

test('message entries and variants use interface keys and preserve their initial pin', async () => {
    const g = graph(), messageEntry = {kind: 'MESSAGE', message: 'A', variant: ''};
    const session = startHistory(g, messageEntry), url = link(g, session, messageEntry);
    assert.deepEqual((await payloadOf(url)).entry, {kind: 'MESSAGE', targets: [{kind: 'interface', key: 'A',
        identity: {representation: 'MESSAGE', type: null, fqn: null, message: ['A', ''], http: null}}]});
    assert.deepEqual((await restore(g, url, messageEntry)).state, session.state);
    const missing = fixture();
    missing.graph.nodes = missing.graph.nodes.filter(node => node.identity.key !== 'A');
    missing.graph.edges = missing.graph.edges.filter(edge => edge.source.key !== 'A');
    await assert.rejects(() => restore(read(missing), url, {kind: 'MESSAGE', message: 'different-variant', variant: ''}), /cannot be restored/);
});

function renameInterfaces(payload, names) {
    for (const node of payload.graph.nodes) if (!node.reaction) node.identity.key = names[node.identity.key] ?? node.identity.key;
    for (const edge of payload.graph.edges) for (const endpoint of ['source', 'target']) {
        if (edge[endpoint].nodeType === 'INTERFACE') edge[endpoint].key = names[edge[endpoint].key] ?? edge[endpoint].key;
    }
    return payload;
}

test('reused or swapped interface keys cannot redirect a link to a different message or HTTP operation', async () => {
    const g = graph(), initial = apply(g, startHistory(g, entry), 'pin', iid('A'));
    const url = link(g, initial);
    const changed = read(renameInterfaces(fixture(), {A: 'http', http: 'A'}));
    const restored = await restore(changed, url);
    assert.deepEqual(restored.state.pins, new Set([iid('http')]));
    assert.equal(changed.nodes.get([...restored.state.pins][0]).interfaceDetails.message.messageType, 'A');
    const messageEntry = {kind: 'MESSAGE', message: 'A', variant: ''};
    const messageLink = link(g, startHistory(g, messageEntry), messageEntry);
    assert.deepEqual((await restore(changed, messageLink, messageEntry)).state.pins, new Set([iid('http')]));
});

test('reaction and legacy group references survive renumbering of trigger and action interfaces', async () => {
    const payload = fixture();
    for (const node of payload.graph.nodes) if (node.reaction) delete node.reaction.groupKey;
    const g = read(payload), group = g.groupOf.get(rid('r1'));
    let session = apply(g, startHistory(g, entry), 'collapse', group);
    session = apply(g, session, 'pin', rid('r2'));
    const changed = read(renameInterfaces(structuredClone(payload), {A: 'http', http: 'generic', generic: 'A'}));
    const restored = await restore(changed, link(g, session));
    assert.deepEqual(restored.state.pins, new Set([rid('r2')]));
    assert.ok(!restored.state.visible.has(rid('r1')));
});

test('a reused key with no remaining semantic target fails, and identical candidates remain ambiguous', async () => {
    const g = graph(), url = link(g, apply(g, startHistory(g, entry), 'pin', iid('A')));
    const reused = fixture();
    reused.graph.nodes.find(node => node.identity.key === 'A').interfaceDetails.message.messageType = 'ReplacementEvent';
    await assert.rejects(() => restore(read(reused), url), /cannot be restored/);
    const duplicate = fixture();
    duplicate.graph.nodes.push({...structuredClone(duplicate.graph.nodes.find(node => node.identity.key === 'A')),
        identity: {nodeType: 'INTERFACE', key: 'another-id'}});
    await assert.rejects(() => restore(read(duplicate), url), /cannot be restored/);
});

test('old key-only interface references are refused because reused IDs cannot be checked safely', async () => {
    const g = graph(), payload = await payloadOf(link(g, apply(g, startHistory(g, entry), 'pin', iid('A'))));
    delete payload.steps[0].target.identity;
    for (const version of [1, 2, 3]) await assert.rejects(() => restore(g, encode({...payload, version})), /cannot be restored/);
});

test('links retain zoom and the centred model point across different canvas dimensions', async () => {
    const g = graph(), session = startHistory(g, entry);
    const viewport = {zoom: 1.25, pan: {x: -220, y: 73}, width: 1000, height: 600};
    const url = await shareLink(g, session, context, entry, location, viewport);
    const restored = await restore(g, url);
    assert.deepEqual(restored.presentation, {zoom: 1.25, center: {x: 576, y: 181.6}});
    assert.deepEqual(restored.state, session.state);
    assert.deepEqual(presentationPan(restored.presentation, 1400, 900), {x: -20, y: 223});
    assert.deepEqual(presentationPan(viewport, 1400, 900), viewport.pan);
    assert.equal(restored.history.length, 0);
    assert.equal((await payloadOf(url)).version, 3);
    const legacy = await payloadOf(link(g, session)); legacy.version = 1;
    assert.deepEqual(await restore(g, encode(legacy)), session);
});

test('invalid shared presentation is rejected atomically instead of changing zoom or view state', async () => {
    const g = graph(), session = apply(g, startHistory(g, entry), 'pin', iid('A'));
    const payload = await payloadOf(link(g, session));
    for (const presentation of [null, {}, {zoom: 0, center: {x: 0, y: 0}},
        {zoom: 4, center: {x: 0, y: 0}}, {zoom: 1, center: {x: '0', y: 0}},
        {zoom: 1, center: {x: 1e12, y: 0}}, {zoom: 1, center: {x: 0, y: 0, extra: true}},
        {zoom: 1, center: {x: 0, y: 0}, extra: true}]) {
        await assert.rejects(() => restore(g, encode({...payload, presentation})), /cannot be restored/);
    }
    assert.equal(validPresentation({zoom: Infinity, center: {x: 0, y: 0}}), false);
    assert.equal(sharedPresentation(null), undefined);
    assert.equal(validPresentation(sharedPresentation({zoom: 0, pan: {x: 0, y: 0}, width: 100, height: 100})), false);
});

test('expansion, path selection, marker restores and group actions replay through the same boundary', async () => {
    const g = graph();
    let session = startHistory(g, entry);
    for (const action of [
        {type: 'focus', id: iid('A')}, {type: 'expand', id: iid('A'), direction: 'outgoing', depth: 4},
        {type: 'hide', id: iid('generic')}, {type: 'show', ids: [iid('generic')]},
        {type: 'collapse', id: g.groupOf.get(rid('r2'))}, {type: 'expand-group', id: g.groupOf.get(rid('r2'))},
        {type: 'outgoing', id: iid('http')}, {type: 'reset'},
        {type: 'paths', ids: [iid('A'), iid('generic')]}, {type: 'connected', id: iid('generic')},
    ]) {
        const next = advanceHistory(g, session, action);
        assert.notStrictEqual(next, session);
        session = next;
        assert.deepEqual((await restore(g, link(g, session))).state, session.state);
    }
});

test('oversize sharing reports a limit instead of truncating the state, and Unicode references round-trip', async () => {
    const g = graph(), initial = startHistory(g, entry);
    await assert.rejects(() => shareLink(g, {...initial, steps: Array.from({length: MAX_REPLAY_STEPS + 1}, () => ({type: 'pin', id: iid('A')}))},
        context, entry, location), /too many steps/);
    await assert.rejects(() => shareLink(g, initial, context, entry, location + '?padding=' + 'a'.repeat(MAX_LINK_LENGTH)), /too large/);
    const data = fixture();
    data.graph.nodes.find(n => n.identity.key === 'A').identity.key = 'Ä/注文';
    for (const edge of data.graph.edges) if (edge.source.key === 'A') edge.source.key = 'Ä/注文';
    const unicodeGraph = read(data), session = apply(unicodeGraph, startHistory(unicodeGraph, entry), 'pin', iid('Ä/注文'));
    assert.deepEqual((await restore(unicodeGraph, link(unicodeGraph, session))).state, session.state);
    const duplicate = fixture();
    duplicate.graph.nodes.push({...structuredClone(duplicate.graph.nodes.find(n => n.identity.key === 'r1')), identity: {nodeType: 'REACTION', key: 'copy'}});
    duplicate.graph.edges.push(...duplicate.graph.edges.filter(e => e.source.key === 'r1' || e.target.key === 'r1')
        .map(e => JSON.parse(JSON.stringify(e).replaceAll('r1', 'copy'))));
    assert.throws(() => referenceOf(read(duplicate), rid('r1')), /cannot be restored/);
});

test('a long compressed replay stays independent of the undo buffer and old uncompressed semantic links still work', async () => {
    const g = graph();
    let session = startHistory(g, entry);
    for (let i = 0; i < 1000; i++) session = apply(g, session, 'pin', iid('A'));
    const url = await link(g, session);
    assert.ok(url.length < 1000);
    assert.match(new URL(url).hash, /^#reaction-view=d1\./);
    const restored = await restore(g, url);
    assert.deepEqual(restored.state, session.state);
    assert.equal(restored.steps.length, 1000);
    assert.equal(restored.history.length, 0);
    const uncompressed = await payloadOf(link(g, apply(g, startHistory(g, entry), 'pin', iid('A'))));
    assert.deepEqual((await restore(g, encode(uncompressed))).state.pins, new Set([iid('A')]));
});
