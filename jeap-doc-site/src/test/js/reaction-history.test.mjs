import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readGraph, identity, project, missingGroupActions} from '../../main/resources/site/src/components/ReactionViewer/model.mjs';
import {hiddenMarkers} from '../../main/resources/site/src/components/ReactionViewer/visibility.mjs';
import {startHistory, advanceHistory, backHistory, HISTORY_LIMIT} from '../../main/resources/site/src/components/ReactionViewer/history.mjs';
import {shareLink, restoreLink, LINK_PREFIX, MAX_LINK_LENGTH, referenceOf} from '../../main/resources/site/src/components/ReactionViewer/sharing.mjs';
import {decodeLinkText, encodeLinkText} from '../../main/resources/site/src/components/ReactionViewer/linkCodec.mjs';
import {compactState, expandState, MAX_REPLAY_STEPS} from '../../main/resources/site/src/components/ReactionViewer/linkState.mjs';
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
const encode = async payload => location + LINK_PREFIX + await encodeLinkText(JSON.stringify(compactState(payload)));
const payloadOf = async url => expandState(JSON.parse(await decodeLinkText(new URL(await url).hash.slice(LINK_PREFIX.length))));

test('combined actions, group state, superseded reveals, focus and reset each undo completely', async () => {
    const g = graph(), initial = startHistory(g, entry), group = g.groupOf.get(rid('r2'));
    let session = initial;
    const actions = [{type: 'expand-group', id: group}, {type: 'show', id: rid('old')}, {type: 'pin', id: rid('old')},
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
    const opened = apply(g, jumped, 'expand-group', g.groupOf.get(rid('r1')));
    const focused = apply(g, opened, 'focus', rid('r1'));
    const reset = advanceHistory(g, focused, {type: 'reset'});
    assert.deepEqual(reset.state, jumped.state);
    assert.deepEqual(backHistory(reset), focused);
    assert.deepEqual(backHistory(backHistory(reset)), opened);
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
    const g = graph(), opened = apply(g, startHistory(g, entry), 'expand-group', g.groupOf.get(rid('r1')));
    const session = apply(g, apply(g, opened, 'pin', iid('A')), 'pin', rid('r1'));
    const url = link(g, session), original = structuredClone(session);
    assert.deepEqual((await restore(g, url)).state, session.state);
    for (const mutate of [
        p => { p.graph.nodes = p.graph.nodes.filter(n => n.identity.key !== 'r1'); p.graph.edges = p.graph.edges.filter(e => e.source.key !== 'r1' && e.target.key !== 'r1'); },
        p => { const duplicate = structuredClone(p.graph.nodes.find(n => n.identity.key === 'r1')); duplicate.identity.key = 'duplicate'; p.graph.nodes.push(duplicate);
            p.graph.edges.push(...p.graph.edges.filter(e => e.source.key === 'r1' || e.target.key === 'r1').map(e => JSON.parse(JSON.stringify(e).replaceAll('r1', 'duplicate')))); },
    ]) {
        const changed = fixture(); mutate(changed);
        await assert.rejects(() => restore(read(changed), url), /cannot be restored/);
    }
    const payload = await payloadOf(url);
    payload.entry = {...payload.entry, system: 'absent'};
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
    const unsupported = location + LINK_PREFIX + await encodeLinkText(JSON.stringify({v: 99}));
    await assert.rejects(() => restore(g, unsupported), /cannot be restored/);
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
    assert.equal(await restore(read(missing), url, {kind: 'MESSAGE', message: 'different-variant', variant: ''}), null);
    await assert.rejects(() => restore(read(missing), url, messageEntry), /cannot be restored/);
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
    const g = read(payload);
    let session = apply(g, startHistory(g, entry), 'expand-group', g.groupOf.get(rid('r2')));
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

test('interface references without semantic identity are refused', async () => {
    const g = graph(), payload = await payloadOf(link(g, apply(g, startHistory(g, entry), 'pin', iid('A'))));
    delete payload.steps[0].target.identity;
    await assert.rejects(() => restore(g, encode(payload)), /cannot be restored/);
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
    assert.equal((await payloadOf(url)).version, 2);
});

test('invalid shared presentation is rejected atomically instead of changing zoom or view state', async () => {
    const g = graph(), session = apply(g, startHistory(g, entry), 'pin', iid('A'));
    const payload = await payloadOf(link(g, session));
    for (const presentation of [null, {}, {zoom: 0, center: {x: 0, y: 0}},
        {zoom: 4, center: {x: 0, y: 0}}, {zoom: 1, center: {x: '0', y: 0}},
        {zoom: 1, center: {x: 1e12, y: 0}}, {zoom: 1, center: {x: 0, y: 0, extra: true}},
        {zoom: 1, center: {x: 0, y: 0}, extra: true}]) {
        const packed = compactState(payload);
        packed.p = presentation && presentation.center ? [presentation.zoom, presentation.center.x, presentation.center.y] : null;
        if (presentation?.extra || presentation?.center?.extra) packed.p.push(true);
        const invalid = location + LINK_PREFIX + await encodeLinkText(JSON.stringify(packed));
        await assert.rejects(() => restore(g, invalid), /cannot be restored/);
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
        {type: 'expand-group', id: g.groupOf.get(rid('r2'))}, {type: 'collapse', id: g.groupOf.get(rid('r2'))},
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

test('a long compressed replay stays independent of the undo buffer', async () => {
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

});


test('stale targets and context errors are reported only by the addressed viewer', async () => {
    const g = graph(), viewer = {kind: 'MESSAGE', message: 'A', variant: ''};
    const session = apply(g, startHistory(g, viewer), 'pin', rid('r1'));
    const payload = await payloadOf(link(g, session, viewer));
    payload.steps[0].target.status = 'MISSING';
    const url = encode(payload);
    assert.equal(await restore(g, url, entry), null);
    await assert.rejects(() => restore(g, url, viewer), /cannot be restored/);
    assert.equal(await restore(g, link(g, session, viewer), entry, {...context, environment: 'wrong'}), null);
    await assert.rejects(() => restoreLink(g, LINK_PREFIX + 'invalid', context, viewer), error => error.unaddressed === true);
});

test('unidentifiable targets report a sharing error and Back makes the view shareable again', async () => {
    const data = fixture();
    const original = data.graph.nodes.find(node => node.identity.key === 'r1');
    data.graph.nodes.push({...structuredClone(original), identity: {nodeType: 'REACTION', key: 'copy'}});
    data.graph.edges.push(...data.graph.edges.filter(edge => edge.source.key === 'r1' || edge.target.key === 'r1')
        .map(edge => JSON.parse(JSON.stringify(edge).replaceAll('r1', 'copy'))));
    const g = read(data), initial = startHistory(g, entry), session = apply(g, initial, 'pin', rid('r1'));
    await assert.rejects(() => link(g, session), /cannot be shared:.*not uniquely identifiable/);
    assert.deepEqual((await restore(g, link(g, backHistory(session)))).state, initial.state);
});

test('pan and zoom reuse the semantic payload without reading targets or projecting entries again', async () => {
    const g = graph(), viewer = {kind: 'MESSAGE', message: 'A', variant: ''};
    const session = apply(g, startHistory(g, viewer), 'pin', rid('r1'));
    const first = await payloadOf(link(g, session, viewer));
    const get = g.nodes.get;
    g.nodes.get = () => { throw new Error('A presentation update must not read graph nodes'); };
    let second;
    try {
        second = await payloadOf(shareLink(g, session, {...context}, {...viewer}, location,
            {zoom: 1.5, pan: {x: 30, y: 60}, width: 900, height: 600}));
    } finally { g.nodes.get = get; }
    assert.deepEqual(second, {...first, presentation: {zoom: 1.5, center: {x: 280, y: 160}}});
});

test('new entries, focus, paths and boundary expansion keep reaction groups collapsed', () => {
    const g = graph(), group = g.groupOf.get(rid('r1'));
    const collapsed = state => assert.ok([...state.visible].every(id => !g.nodes.get(id).reaction));
    for (const selected of [entry, {kind: 'COMPONENT', system: 'orders', component: 'dispatcher'},
        {kind: 'MESSAGE', message: 'A', variant: ''}, {kind: 'INTERFACE', id: iid('http')}, {kind: 'GROUP', id: group}]) {
        const session = startHistory(g, selected);
        assert.ok(session.state.reactions.size);
        collapsed(session.state);
    }
    const initial = startHistory(g, entry);
    collapsed(apply(g, initial, 'focus', iid('A')).state);
    collapsed(advanceHistory(g, initial, {type: 'paths', ids: [iid('A'), iid('generic')]}).state);
    const focused = apply(g, initial, 'focus', iid('A'));
    assert.strictEqual(apply(g, focused, 'expand', iid('A'), {direction: 'outgoing', depth: 1}), focused);
    collapsed(apply(g, focused, 'expand', iid('A'), {direction: 'outgoing', depth: 4}).state);
    // Explicitly focusing an individual reaction still shows that reaction.
    const opened = apply(g, initial, 'expand-group', group);
    assert.ok(apply(g, opened, 'focus', rid('r1')).state.visible.has(rid('r1')));
});

test('the outgoing opener shows direct actions without opening groups and supports Back, Reset and sharing', async () => {
    const g = graph(), selected = {kind: 'MESSAGE', message: 'A', variant: ''};
    const initial = startHistory(g, selected), group = g.groupOf.get(rid('r1'));
    const marker = project(g, initial.state).elements.find(e => e.data.id === 'actions:' + group).data;
    assert.equal(marker.label, '1');
    assert.equal(marker.anchor, group);
    assert.deepEqual(marker.explore, {type: 'show-actions', id: group});
    const session = advanceHistory(g, initial, marker.explore);
    assert.deepEqual(session.state.visible, new Set([iid('A'), iid('http')]));
    assert.deepEqual(session.state.reactions, new Set([rid('r1')]));
    assert.deepEqual(session.state.pins, initial.state.pins);
    assert.equal(missingGroupActions(g, session.state, group).size, 0);
    assert.ok(!project(g, session.state).elements.some(e => e.data.id === marker.id));
    assert.deepEqual(backHistory(session), initial);
    assert.deepEqual(advanceHistory(g, session, {type: 'reset'}).state, initial.state);
    assert.deepEqual((await restore(g, link(g, session, selected), selected)).state, session.state);
    // The separate inner marker unfolds members without adding more outgoing connections.
    const inner = project(g, session.state).elements.find(e => e.data.id === 'hidden:' + group).data;
    const unfolded = advanceHistory(g, session, {type: 'show', ids: inner.restore});
    assert.ok(unfolded.state.visible.has(rid('r1')));
    assert.deepEqual(unfolded.state.edges, session.state.edges);
    assert.deepEqual((await restore(g, link(g, unfolded, selected), selected)).state, unfolded.state);
});

test('the outgoing opener excludes unselected siblings, hidden members and unrevealed superseded reactions', () => {
    const payload = fixture();
    const sibling = structuredClone(payload.graph.nodes.find(n => n.identity.key === 'r1'));
    sibling.identity.key = 'sibling';
    payload.graph.nodes.push(sibling);
    payload.graph.edges.push({edgeType: 'TRIGGER', source: {nodeType: 'INTERFACE', key: 'generic'}, target: sibling.identity},
        {edgeType: 'ACTION', source: sibling.identity, target: {nodeType: 'INTERFACE', key: 'generic'}});
    const superseded = payload.graph.nodes.find(n => n.identity.key === 'old');
    superseded.reaction.groupKey = 'start';
    const g = read(payload), group = g.groupOf.get(rid('r1'));
    const selected = startHistory(g, {kind: 'MESSAGE', message: 'A', variant: ''});
    const expanded = apply(g, selected, 'show-actions', group);
    assert.deepEqual(expanded.state.reactions, new Set([rid('r1')]));
    assert.ok(!expanded.state.visible.has(iid('generic')));
    const full = apply(g, startHistory(g, entry), 'expand-group', group);
    const hidden = apply(g, full, 'hide', rid('sibling'));
    const withoutActions = apply(g, hidden, 'hide', iid('http'));
    const restored = apply(g, withoutActions, 'show-actions', group);
    assert.ok(restored.state.visible.has(iid('http')));
    assert.ok(restored.state.visible.has(rid('r1')), 'already open members remain open');
    assert.ok(restored.state.hidden.has(rid('sibling')));
    assert.ok(!restored.state.visible.has(rid('old')));
});

test('version 1 links retain expanded defaults and their original collapse and expansion replay', async () => {
    const g = graph(), legacy = startHistory(g, entry, 1);
    assert.ok(legacy.state.visible.has(rid('r1')));
    const collapsed = apply(g, legacy, 'collapse', g.groupOf.get(rid('r1')));
    const focused = apply(g, collapsed, 'focus', iid('A'));
    const expanded = apply(g, focused, 'expand', iid('A'), {direction: 'outgoing', depth: 4});
    for (const session of [legacy, collapsed, expanded]) {
        const url = link(g, session);
        assert.equal((await payloadOf(url)).version, 1);
        const restored = await restore(g, url);
        assert.deepEqual(restored.state, session.state);
        assert.equal(restored.version, 1);
    }
});

test('outgoing markers count distinct targets and include superseded actions only after explicit reveal', () => {
    const payload = fixture();
    const duplicate = structuredClone(payload.graph.nodes.find(n => n.identity.key === 'r1'));
    duplicate.identity.key = 'duplicate';
    payload.graph.nodes.push(duplicate);
    payload.graph.edges.push(...payload.graph.edges.filter(e => e.source.key === 'r1' || e.target.key === 'r1')
        .map(e => JSON.parse(JSON.stringify(e).replaceAll('r1', 'duplicate'))));
    payload.graph.nodes.find(n => n.identity.key === 'old').reaction.groupKey = 'start';
    payload.graph.edges.find(e => e.target.key === 'old').source.key = 'A';
    const g = read(payload), initial = startHistory(g, {kind: 'MESSAGE', message: 'A', variant: ''});
    const group = g.groupOf.get(rid('r1'));
    const markerCount = session => project(g, session.state).elements.find(e => e.data.id === 'actions:' + group)?.data.label;
    assert.equal(markerCount(initial), '1');
    const active = apply(g, initial, 'show-actions', group);
    assert.ok(active.state.visible.has(iid('http')));
    assert.ok(!active.state.visible.has(iid('generic')));
    assert.equal(markerCount(active), undefined);
    const revealed = apply(g, active, 'show', rid('old'));
    assert.equal(markerCount(revealed), '1');
    const expanded = apply(g, revealed, 'show-actions', group);
    assert.ok(expanded.state.visible.has(iid('generic')));
    assert.ok(expanded.state.visible.has(rid('old')));
    assert.ok(!expanded.state.visible.has(rid('r1')));
    assert.ok(!expanded.state.visible.has(rid('duplicate')));
});

test('hiding a revealed action leaves one outgoing opener and keeps old restore steps replayable', async () => {
    const g = graph(), selected = {kind: 'MESSAGE', message: 'A', variant: ''};
    const group = g.groupOf.get(rid('r1')), initial = startHistory(g, selected);
    const markers = session => [...project(g, session.state).elements, ...hiddenMarkers(g, session.state)]
        .map(e => e.data).filter(data => data.anchor === group && data.direction === 'outgoing');
    let session = apply(g, initial, 'show-actions', group);
    for (const type of ['show-actions', 'show-actions', 'show']) {
        session = apply(g, session, 'hide', iid('http'));
        assert.equal(markers(session).length, 1);
        assert.deepEqual(markers(session)[0].explore, {type: 'show-actions', id: group});
        assert.equal(markers(session)[0].label, '1');
        assert.ok(!session.state.visible.has(rid('r1')));
        assert.deepEqual((await restore(g, link(g, session, selected), selected)).state, session.state);
        const before = session;
        session = advanceHistory(g, session, type === 'show' ? {type, ids: [iid('http')]}
            : {type, id: group});
        assert.ok(session.state.visible.has(iid('http')));
        assert.equal(markers(session).length, 0);
        assert.deepEqual(backHistory(session), before);
    }
    assert.deepEqual((await restore(g, link(g, session, selected), selected)).state, session.state);
    assert.deepEqual(advanceHistory(g, session, {type: 'reset'}).state, initial.state);
    // Expanded reactions still need their own restore markers, as do hidden triggers.
    const expanded = apply(g, session, 'expand-group', group);
    const hiddenAction = apply(g, expanded, 'hide', iid('http'));
    assert.ok(hiddenMarkers(g, hiddenAction.state).some(e => e.data.anchor === rid('r1')
        && e.data.direction === 'outgoing' && e.data.restore.includes(iid('http'))));
    const unpinned = apply(g, session, 'pin', iid('A'));
    const hiddenTrigger = apply(g, unpinned, 'hide', iid('A'));
    assert.ok(hiddenMarkers(g, hiddenTrigger.state).some(e => e.data.anchor === group
        && e.data.direction === 'incoming' && e.data.restore.includes(iid('A'))));
});

test('a single action opener covers hidden and unexplored targets together', () => {
    const payload = fixture();
    payload.graph.edges.push({edgeType: 'ACTION', source: {nodeType: 'REACTION', key: 'r1'},
        target: {nodeType: 'INTERFACE', key: 'generic'}});
    const g = read(payload), group = g.groupOf.get(rid('r1'));
    const path = advanceHistory(g, startHistory(g, entry), {type: 'paths', ids: [iid('A'), iid('http')]});
    const hidden = apply(g, path, 'hide', iid('http'));
    const markers = [...project(g, hidden.state).elements, ...hiddenMarkers(g, hidden.state)]
        .map(e => e.data).filter(data => data.anchor === group && data.direction === 'outgoing');
    assert.equal(markers.length, 1);
    assert.equal(markers[0].label, '2');
    const shown = advanceHistory(g, hidden, markers[0].explore);
    assert.ok(shown.state.visible.has(iid('http')));
    assert.ok(shown.state.visible.has(iid('generic')));
    assert.ok(!shown.state.visible.has(rid('r1')));
});
