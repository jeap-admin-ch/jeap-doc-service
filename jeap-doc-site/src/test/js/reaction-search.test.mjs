import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readGraph, identity, project} from '../../main/resources/site/src/components/ReactionViewer/model.mjs';
import {entryCandidates, matchingEntries} from '../../main/resources/site/src/components/ReactionViewer/search.mjs';
import {startHistory, advanceHistory, backHistory} from '../../main/resources/site/src/components/ReactionViewer/history.mjs';
import {shareLink, restoreLink} from '../../main/resources/site/src/components/ReactionViewer/sharing.mjs';

const key = (key, nodeType = 'INTERFACE') => ({key, nodeType});
const id = name => identity(key(name));
const context = {site: 'handbook', environment: 'dev', baseUrl: '/site/handbook/', path: '/site/handbook/dev/reactions/'};
const explorer = {kind: 'EXPLORER'};
function fixture(systems = ['orders', 'billing']) {
    const message = (name, type, variant) => ({identity: key(name), nodeType: 'MESSAGE', interfaceDetails: {message: {messageType: type, variant}}});
    const reaction = (name, system) => ({identity: key(name, 'REACTION'), nodeType: 'REACTION',
        reaction: {system, component: 'dispatcher', median: 1, status: 'ACTIVE'}});
    return readGraph({formatVersion: 1, ...context, available: true, ownershipComplete: systems.every(Boolean), graph: {nodes: [
        message('a', 'OrderEvent', 'fast'), message('b', 'OrderEvent', 'slow'), message('c', 'OtherEvent', ''),
        {identity: key('http'), nodeType: 'HTTP_OPERATION', interfaceDetails: {http: {provider: 'api', method: 'GET', path: '/orders'}}},
        {identity: key('generic'), nodeType: 'OTHER', interfaceDetails: {interfaceType: 'FILE', fqn: '<script>orders.csv</script>'}},
        reaction('r1', systems[0]), reaction('r2', systems[1])], edges: [
            {source: key('a'), target: key('r1', 'REACTION'), edgeType: 'TRIGGER'},
            {source: key('r1', 'REACTION'), target: key('http'), edgeType: 'ACTION'},
            {source: key('b'), target: key('r2', 'REACTION'), edgeType: 'TRIGGER'},
            {source: key('r2', 'REACTION'), target: key('generic'), edgeType: 'ACTION'},
        ]}}, context.site, context.environment);
}

test('global and selection scopes are explicit and keep hidden interfaces and components apart', () => {
    const graph = fixture(), all = entryCandidates(graph, {scope: 'graph'});
    assert.equal(all.length, 7); // five interfaces and two distinct components
    const initial = startHistory(graph, explorer);
    assert.equal(project(graph, initial.state).elements.length, 0);
    const selected = advanceHistory(graph, initial, {type: 'entry', entry: {kind: 'INTERFACE', id: id('a')}});
    assert.deepEqual(selected.state.pins, new Set([id('a')]));
    const visibleIds = new Set(project(graph, selected.state).elements.filter(e => !e.data.source).map(e => e.data.id));
    const local = entryCandidates(graph, {scope: 'selection', visibleIds});
    assert.equal(matchingEntries(local, 'slow').length, 0);
    assert.equal(matchingEntries(all, 'slow').length, 1);
    assert.equal(matchingEntries(local, 'dispatcher').length, 1);
    assert.equal(matchingEntries(all, 'dispatcher').length, 2);
    assert.throws(() => entryCandidates(graph), /scope/);
    assert.throws(() => entryCandidates(graph, {scope: 'selection'}), /scope/);
});

test('search uses semantic context for messages, HTTP and generic interfaces and keeps identity separate', () => {
    const all = entryCandidates(fixture(), {scope: 'graph'});
    assert.equal(matchingEntries(all, '   ').length, 0);
    assert.equal(matchingEntries(all, 'unmatched').length, 0);
    assert.equal(matchingEntries(all, 'orderevent').length, 2);
    assert.match(matchingEntries(all, 'FAST')[0].label, /fast/);
    for (const query of ['GET', 'api', '/orders']) assert.equal(matchingEntries(all, query)[0].id, id('http'));
    for (const query of ['file', 'orders.csv']) assert.equal(matchingEntries(all, query)[0].id, id('generic'));
    assert.match(matchingEntries(all, 'dispatcher')[0].label, /billing/);
});

test('local component results follow projected groups, including collapsed groups but excluding hidden ones', () => {
    const graph = fixture();
    const visibleEntries = state => entryCandidates(graph, {scope: 'selection',
        visibleIds: new Set(project(graph, state).elements.filter(element => !element.data.source).map(element => element.data.id))});
    let session = startHistory(graph, {kind: 'COMPONENT', system: 'orders', component: 'dispatcher'});
    const group = graph.groupOf.get(identity(key('r1', 'REACTION')));
    assert.equal(session.state.visible.has(identity(key('r1', 'REACTION'))), false);
    assert.equal(matchingEntries(visibleEntries(session.state), 'dispatcher').length, 1);
    assert.equal(matchingEntries(visibleEntries(session.state), 'slow').length, 0);
    session = advanceHistory(graph, session, {type: 'hide', id: group});
    assert.equal(matchingEntries(visibleEntries(session.state), 'dispatcher').length, 0);
    session = backHistory(session);
    assert.equal(matchingEntries(visibleEntries(session.state), 'dispatcher').length, 1);
});

test('rank exact, prefix and substring matches alphabetically, retaining every result', () => {
    const entries = ['x order', 'order B', 'order', 'order A'].map(label => ({id: label, label}));
    assert.deepEqual(matchingEntries(entries, ' ORDER ').map(e => e.label), ['order', 'order A', 'order B', 'x order']);
    const many = Array.from({length: 1000}, (_, i) => ({id: String(i), label: `Order${i}`}));
    assert.equal(matchingEntries(many, 'order').length, 1000);
});

test('Explorer selections have an undoable reset baseline and links restore without a preselected viewer', async () => {
    const graph = fixture(), initial = startHistory(graph, explorer);
    let session = advanceHistory(graph, initial, {type: 'entry', entry: {kind: 'INTERFACE', id: id('a')}});
    const first = session;
    session = advanceHistory(graph, session, {type: 'entry', entry: {kind: 'INTERFACE', id: id('http')}});
    assert.deepEqual(session.state.pins, new Set([id('http')]));
    assert.deepEqual(backHistory(session), first);
    assert.deepEqual(backHistory(first), initial);
    const url = await shareLink(graph, session, context, explorer, 'https://docs.example' + context.path);
    const restored = await restoreLink(graph, new URL(url).hash, context, explorer);
    assert.deepEqual(restored.state, session.state);
    assert.deepEqual(restored.baseline, session.baseline);
    assert.equal(await restoreLink(graph, new URL(url).hash, context, {kind: 'MESSAGE', message: 'OrderEvent', variant: 'fast'}), null);
    await assert.rejects(() => restoreLink(graph, new URL(url).hash, {...context, site: 'other'}, explorer));
});

test('incomplete ownership never merges unnamed systems into a component result; interfaces remain searchable', async () => {
    for (const systems of [[undefined, null], ['', '   '], [undefined, 'billing']]) {
        const graph = fixture(systems), all = entryCandidates(graph, {scope: 'graph'});
        assert.equal(graph.ownershipComplete, false);
        assert.equal(graph.groups.size, 2);
        assert.equal(all.filter(entry => entry.entry.kind === 'INTERFACE').length, 5);
        const components = matchingEntries(all, 'dispatcher');
        assert.equal(components.length, systems.includes('billing') ? 1 : 0);
        for (const candidate of all) {
            const session = advanceHistory(graph, startHistory(graph, explorer), {type: 'entry', entry: candidate.entry});
            const url = await shareLink(graph, session, context, explorer, 'https://docs.example' + context.path);
            const restored = await restoreLink(graph, new URL(url).hash, context, explorer);
            assert.deepEqual(restored.state, session.state);
        }
    }
});

test('interfaces with unknown types or missing details are searchable by their displayed label in both scopes', () => {
    const graph = readGraph({formatVersion: 1, ...context, available: true, graph: {nodes: [
        {identity: key('opaque-v2'), nodeType: 'FUTURE_INTERFACE'},
        {identity: key('empty-details'), nodeType: 'OTHER', interfaceDetails: {}},
        {identity: key('blank-details'), nodeType: 'OTHER', interfaceDetails: {interfaceType: '', fqn: ''}},
    ], edges: []}}, context.site, context.environment);
    const global = entryCandidates(graph, {scope: 'graph'});
    for (const candidate of global) {
        const session = startHistory(graph, candidate.entry);
        const local = entryCandidates(graph, {scope: 'selection',
            visibleIds: new Set(project(graph, session.state).elements.map(element => element.data.id))});
        for (const candidates of [global, local]) {
            assert.equal(matchingEntries(candidates, candidate.label)[0].id, candidate.id);
            assert.equal(matchingEntries(candidates, graph.nodes.get(candidate.id).identity.key)[0].id, candidate.id);
        }
    }
    assert.equal(matchingEntries(global, 'future_interface')[0].id, id('opaque-v2'));
    assert.equal(matchingEntries([{id: 'empty', label: 'Fallback label', terms: []}], 'fallback')[0].id, 'empty');
});
