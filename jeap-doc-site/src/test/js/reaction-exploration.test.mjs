import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readGraph, entryView, expandGroups, project, identity, visibleReactionRows} from '../../main/resources/site/src/components/ReactionViewer/model.mjs';
import {changeView, expandView, expansionLimit, focusView, pathCandidates, pathSelection, pathView} from '../../main/resources/site/src/components/ReactionViewer/exploration.mjs';

const iid = key => identity({nodeType: 'INTERFACE', key});
const rid = key => identity({nodeType: 'REACTION', key});
function fixture(flows, extras = []) {
    const nodes = new Map(), edges = [];
    const reference = (key, reaction) => ({nodeType: reaction ? 'REACTION' : 'INTERFACE', key});
    for (const [from, reaction, to, options = {}] of flows) {
        for (const key of [from, to].filter(Boolean)) nodes.set(iid(key), {identity: reference(key, false), nodeType: 'MESSAGE',
            interfaceDetails: {message: {messageType: key, variant: ''}}});
        nodes.set(rid(reaction), {identity: reference(reaction, true), nodeType: 'REACTION',
            reaction: {system: 'orders', component: 'dispatcher', status: 'ACTIVE', median: 10, ...options}});
        if (from) edges.push({source: reference(from, false), target: reference(reaction, true), edgeType: 'TRIGGER'});
        if (to) edges.push({source: reference(reaction, true), target: reference(to, false), edgeType: 'ACTION'});
    }
    const payload = {formatVersion: 1, site: 'site', environment: 'dev', available: true, ownershipComplete: true,
        graph: {nodes: [...nodes.values(), ...extras], edges}};
    return {payload, graph: readGraph(payload, 'site', 'dev')};
}
const chain = () => fixture([['A', 'r1', 'B'], ['B', 'r2', 'C'], ['C', 'r3', 'D']]).graph;
const system = graph => expandGroups(graph, entryView(graph, {kind: 'SYSTEM', system: 'orders'}));
const interfaces = (graph, name) => entryView(graph, {kind: 'INTERFACE', id: iid(name)});
const connections = (graph, state) => project(graph, state).elements.filter(e => e.data.source).map(e => [e.data.source, e.data.target]);

test('only message entries pin their selected variant', () => {
    const {payload} = fixture([['A', 'r1', 'B'], ['B', 'r2', 'C']]);
    payload.graph.nodes.find(node => node.identity.key === 'A').interfaceDetails.message.variant = 'express';
    const graph = readGraph(payload, 'site', 'dev');
    for (const entry of [
        {kind: 'SYSTEM', system: 'orders'},
        {kind: 'COMPONENT', system: 'orders', component: 'dispatcher'},
        {kind: 'MESSAGE', message: 'A', variant: 'express'},
        {kind: 'INTERFACE', id: iid('B')},
        {kind: 'REACTION', id: rid('r2')},
        {kind: 'GROUP', id: graph.groupOf.get(rid('r1'))},
    ]) {
        const state = expandGroups(graph, entryView(graph, entry));
        assert.deepEqual(state.pins, new Set(entry.kind === 'MESSAGE' ? [iid('A')] : []));
        assert.deepEqual(project(graph, state).elements.filter(element => element.data.pinned).map(element => element.data.id),
            entry.kind === 'MESSAGE' ? [iid('A')] : []);
    }
});

test('outgoing expansion counts individual edges, remains additive and preserves pins and baseline', () => {
    const graph = chain(), initial = changeView(graph, interfaces(graph, 'A'), {type: 'pin', id: iid('A')});
    const before = structuredClone(initial), originalGraph = structuredClone(graph);
    assert.equal(expansionLimit(graph, initial, iid('A'), 'outgoing'), 6);
    assert.strictEqual(expandView(graph, initial, iid('A'), 'outgoing', 1), initial);
    for (const [depth, expected] of [[2, [iid('A'), rid('r1'), iid('B')]],
        [3, [iid('A'), rid('r1'), iid('B'), rid('r2')]], [4, [iid('A'), rid('r1'), iid('B'), rid('r2'), iid('C')]]]) {
        const expanded = expandView(graph, initial, iid('A'), 'outgoing', depth);
        assert.deepEqual(expanded.visible, new Set(expected));
        assert.deepEqual(expanded.pins, initial.pins);
        assert.strictEqual(expanded.baseline, initial.baseline);
        assert.equal(connections(graph, expanded).length, depth);
    }
    const farther = expandView(graph, initial, iid('A'), 'outgoing', 6);
    assert.strictEqual(expandView(graph, farther, iid('A'), 'outgoing', 2), farther);
    assert.deepEqual(initial, before); assert.deepEqual(graph, originalGraph);
});

test('incoming expansion and reaction starts use the same edge distances', () => {
    const graph = chain(), initial = interfaces(graph, 'D');
    const expanded = expandView(graph, initial, iid('D'), 'incoming', 2);
    assert.deepEqual(expanded.visible, new Set([iid('D'), rid('r3'), iid('C')]));
    const focused = focusView(graph, system(graph), rid('r2'));
    assert.equal(expansionLimit(graph, focused, rid('r2'), 'incoming'), 3);
    assert.equal(expansionLimit(graph, focused, rid('r2'), 'outgoing'), 3);
    assert.deepEqual(expandView(graph, focused, rid('r2'), 'incoming', 2).visible,
        new Set([rid('r2'), iid('B'), iid('C'), rid('r1')]));
});

test('invalid and empty depths are rejected without changing the view', () => {
    const graph = chain(), state = interfaces(graph, 'A'), before = structuredClone(state);
    for (const depth of ['', '1', null, NaN, Infinity, 0, -1, 1.5, 7, 51]) {
        assert.throws(() => expandView(graph, state, iid('A'), 'outgoing', depth));
    }
    assert.equal(expansionLimit(graph, state, iid('A'), 'incoming'), 0);
    assert.throws(() => expandView(graph, state, iid('A'), 'incoming', 1), /No reachable/);
    assert.deepEqual(state, before);
});

test('cycles use shortest distances and expansion stops at fifty edges without limiting path selection', () => {
    const graph = fixture(Array.from({length: 40}, (_, n) => [`m${n}`, `r${n}`, `m${n + 1}`])).graph;
    const state = interfaces(graph, 'm0');
    assert.equal(expansionLimit(graph, state, iid('m0'), 'outgoing'), 50);
    assert.throws(() => expandView(graph, state, iid('m0'), 'outgoing', 51));
    assert.ok(expandView(graph, state, iid('m0'), 'outgoing', 50).visible.has(iid('m25')));
    const paths = pathView(graph, state, [iid('m0'), iid('m40')]);
    assert.equal(paths.edges.size, 80);
    const cycle = fixture([['A', 'r1', 'B'], ['B', 'r2', 'A']]).graph;
    assert.equal(expansionLimit(cycle, interfaces(cycle, 'A'), iid('A'), 'outgoing'), 4);
});

test('expansion can reveal foreign reactions without selecting their sibling reactions or other edges', () => {
    const graph = fixture([['A', 'own', 'B'], ['B', 'foreign', 'C', {system: 'shipping', groupKey: 'shared'}],
        ['X', 'sibling', 'Y', {system: 'shipping', groupKey: 'shared'}]]).graph;
    const initial = system(graph);
    assert.ok(!initial.reactions.has(rid('foreign')));
    const expanded = expandView(graph, initial, iid('B'), 'outgoing', 1);
    assert.ok(expanded.visible.has(rid('foreign')));
    assert.ok(!expanded.visible.has(iid('C')));
    assert.ok(!expanded.reactions.has(rid('sibling')));
    assert.deepEqual(connections(graph, expanded), [...connections(graph, initial), [iid('B'), rid('foreign')]]);
    const shown = changeView(graph, expanded, {type: 'show', id: graph.groupOf.get(rid('foreign'))});
    assert.ok(!shown.visible.has(rid('sibling')));
    assert.ok(!shown.visible.has(iid('C')));
});

test('group path expansion considers all members but does not unfold a partially collapsed group', () => {
    const graph = fixture([['A', 'r1', 'B', {groupKey: 'same'}], ['X', 'r2', 'Y', {groupKey: 'same'}], ['Y', 'r3', 'Z']]).graph;
    const state = interfaces(graph, 'A'), group = graph.groupOf.get(rid('r1'));
    state.pins.add(rid('r1'));
    const expanded = expandView(graph, state, group, 'outgoing', 1);
    assert.ok(expanded.visible.has(rid('r1')));
    assert.ok(!expanded.visible.has(rid('r2')));
    assert.ok(expanded.reactions.has(rid('r2')));
    assert.ok(expanded.visible.has(iid('B')) && expanded.visible.has(iid('Y')));
    assert.ok(!expanded.visible.has(iid('X')) && !expanded.visible.has(rid('r3')));
    assert.deepEqual(expanded.pins, state.pins);
    assert.ok(connections(graph, expanded).some(([source, target]) => source === group && target === iid('Y')));
});

test('hiding makes expansion available again and restoring cannot exceed the selected depth', () => {
    const graph = chain(), initial = interfaces(graph, 'A');
    const expanded = expandView(graph, initial, iid('A'), 'outgoing', 3);
    const hidden = changeView(graph, expanded, {type: 'hide', id: rid('r2')});
    const restored = expandView(graph, hidden, iid('A'), 'outgoing', 3);
    assert.deepEqual(restored.visible, expanded.visible);
    assert.ok(!restored.visible.has(iid('C')));
    assert.deepEqual(visibleReactionRows(graph, restored).find(row => row.id === rid('r2')).answers, []);
});

test('interface and reaction focus use initial views and preserve the original baseline across subsequent focus', () => {
    const graph = chain(), state = system(graph);
    const first = focusView(graph, state, iid('B'));
    assert.deepEqual(first.visible, new Set([iid('B'), rid('r1'), rid('r2')]));
    assert.deepEqual(first.pins, new Set());
    assert.equal(first.edges.size, 2);
    const next = focusView(graph, first, rid('r3'));
    assert.deepEqual(next.visible, new Set([rid('r3'), iid('C'), iid('D')]));
    assert.deepEqual(next.pins, new Set());
    assert.strictEqual(first.baseline, state.baseline);
    assert.strictEqual(next.baseline, state.baseline);
});

test('group focus starts collapsed and unpinned, while a triggerless reaction invents no trigger', () => {
    const graph = fixture([['A', 'r1', 'B'], ['A', 'r2', 'C'], [null, 'orphan', 'X']]).graph;
    const state = system(graph), group = graph.groupOf.get(rid('r1'));
    const focused = focusView(graph, state, group);
    assert.deepEqual(focused.visible, new Set([iid('A'), iid('B'), iid('C')]));
    assert.deepEqual(focused.pins, new Set());
    assert.equal(connections(graph, focused).length, 3);
    const orphan = focusView(graph, focused, rid('orphan'));
    assert.deepEqual(orphan.visible, new Set([rid('orphan'), iid('X')]));
    assert.deepEqual(connections(graph, orphan), [[rid('orphan'), iid('X')]]);
    assert.strictEqual(orphan.baseline, state.baseline);
});

test('path candidates allow incoming or outgoing connections but not siblings reached by reversing an arrow', () => {
    const graph = fixture([['A', 'r1', 'B'], ['B', 'r2', 'C'], ['X', 'r3', 'B'], ['D', 'r4', 'E']]).graph;
    const state = system(graph);
    assert.deepEqual(new Set(pathCandidates(graph, state, [iid('A')]).map(node => node.id)), new Set([iid('B'), iid('C')]));
    assert.ok(pathCandidates(graph, state, [iid('A'), iid('B')]).some(node => node.id === iid('X')));
    assert.equal(pathCandidates(graph, state, []).length, 6);
    assert.throws(() => pathCandidates(graph, state, [iid('A'), iid('A')]), /only once/);
    assert.throws(() => pathCandidates(graph, state, [rid('r1')]), /not reactions/);
});

test('multi-interface paths include both directions and cyclic detours, but exclude dead ends', () => {
    const graph = fixture([['A', 'r1', 'B'], ['B', 'r2', 'C'], ['C', 'r3', 'B'],
        ['B', 'r4', 'D'], ['B', 'dead', 'E'], ['D', 'back', 'A']]).graph;
    const state = system(graph);
    const paths = pathView(graph, state, [iid('A'), iid('D')]);
    assert.deepEqual(paths.visible, new Set([iid('A'), iid('B'), iid('C'), iid('D'), rid('r1'), rid('r2'), rid('r3'), rid('r4'), rid('back')]));
    assert.equal(paths.edges.size, 10);
    assert.deepEqual(paths.pins, new Set());
    const reversed = pathView(graph, state, [iid('D'), iid('A')]);
    assert.deepEqual(reversed.edges, paths.edges);
    assert.deepEqual(reversed.visible, paths.visible);
    assert.strictEqual(paths.baseline, state.baseline);
    assert.deepEqual(pathView(graph, state, [iid('A'), iid('C'), iid('D')]).pins, new Set());
});

test('editing endpoints requires at least one connected pair, without connecting every selected interface', () => {
    const graph = fixture([['A', 'r1', 'B'], ['B', 'r2', 'C'], ['D', 'r3', 'E']]).graph;
    const state = system(graph), before = structuredClone(state);
    assert.equal(pathSelection(graph, state, [iid('A')]).connected, false);
    assert.equal(pathSelection(graph, state, [iid('A'), iid('D')]).connected, false);
    assert.throws(() => pathView(graph, state, [iid('A'), iid('D')]), /No directed connection/);
    const paths = pathView(graph, state, [iid('A'), iid('C'), iid('D')]);
    assert.ok(paths.visible.has(iid('D')));
    assert.ok(!paths.visible.has(rid('r3')));
    assert.deepEqual(state, before);
});

test('HTTP and generic interfaces participate in focus, expansion and paths; predecessors never do', () => {
    const {payload} = fixture([['A', 'r1', 'H'], ['H', 'r2', 'U'], ['X', 'old', 'Y']]);
    Object.assign(payload.graph.nodes.find(n => n.identity.key === 'H'), {nodeType: 'HTTP_OPERATION',
        interfaceDetails: {http: {provider: 'catalog', method: 'POST', path: '/items'}}});
    Object.assign(payload.graph.nodes.find(n => n.identity.key === 'U'), {nodeType: 'FUTURE',
        interfaceDetails: {interfaceType: 'QUEUE', fqn: 'catalog.items'}});
    payload.graph.edges.push({edgeType: 'PREDECESSOR', source: {nodeType: 'REACTION', key: 'r2'}, target: {nodeType: 'REACTION', key: 'old'}});
    const graph = readGraph(payload, 'site', 'dev'), state = system(graph);
    assert.deepEqual(focusView(graph, state, iid('H')).visible, new Set([iid('H'), rid('r1'), rid('r2')]));
    assert.equal(expansionLimit(graph, state, iid('A'), 'outgoing'), 4);
    assert.equal(pathView(graph, state, [iid('A'), iid('U')]).edges.size, 4);
    assert.equal(pathSelection(graph, state, [iid('H'), iid('Y')]).connected, false);
    assert.match(pathCandidates(graph, state, [iid('A')]).find(c => c.id === iid('H')).label, /POST.*items/);
});

test('superseded paths stay hidden by default and explicit reveal remains effective through focus and expansion', () => {
    const graph = fixture([['A', 'r1', 'B'], ['B', 'old', 'C', {status: 'SUPERSEDED'}]]).graph;
    const state = system(graph);
    assert.equal(expansionLimit(graph, state, iid('A'), 'outgoing'), 2);
    assert.equal(pathSelection(graph, state, [iid('A'), iid('C')]).connected, false);
    const revealed = changeView(graph, state, {type: 'show', id: rid('old')});
    const focused = focusView(graph, revealed, iid('B'));
    assert.ok(focused.visible.has(rid('old')));
    assert.ok(pathView(graph, focused, [iid('A'), iid('C')]).visible.has(rid('old')));
    assert.ok(expandView(graph, focused, iid('B'), 'outgoing', 2).visible.has(iid('C')));
});

test('selected path edges do not pull in sibling reactions after collapse, hide and restore', () => {
    const graph = fixture([['A', 'r1', 'B', {groupKey: 'same'}], ['A', 'r2', 'X', {groupKey: 'same'}], ['B', 'r3', 'C']]).graph;
    const paths = pathView(graph, system(graph), [iid('A'), iid('C')]);
    const group = graph.groupOf.get(rid('r1'));
    const collapsed = changeView(graph, paths, {type: 'collapse', id: group});
    const restored = changeView(graph, collapsed, {type: 'show', id: group});
    assert.deepEqual(restored.visible, paths.visible);
    assert.deepEqual(restored.edges, paths.edges);
    assert.ok(!restored.reactions.has(rid('r2')));
});

test('boundary circles expand their counted direct neighbours, including across system boundaries', () => {
    const graph = fixture([['A', 'own', 'B'], ['B', 'foreign', 'C', {system: 'shipping'}],
        ['X', 'producer', 'B', {system: 'catalog'}]]).graph;
    const state = system(graph);
    const markers = project(graph, state).elements.map(e => e.data).filter(data => data.anchor === iid('B') && data.explore);
    assert.deepEqual(markers.map(data => [data.direction, data.label]), [['incoming', '1'], ['outgoing', '1']]);
    const expanded = changeView(graph, state, {type: 'expand', ...markers[1].explore});
    assert.ok(expanded.visible.has(rid('foreign')));
    assert.ok(!expanded.visible.has(iid('C')));
    assert.ok(!expanded.visible.has(rid('producer')));
    assert.ok(!project(graph, expanded).elements.some(e => e.data.id === markers[1].id));
    assert.ok(project(graph, expanded).elements.some(e => e.data.id === markers[0].id));
    const next = project(graph, expanded).elements.find(e => e.data.anchor === rid('foreign') && e.data.direction === 'outgoing').data;
    assert.equal(next.label, '1');
    assert.ok(changeView(graph, expanded, {type: 'expand', ...next.explore}).visible.has(iid('C')));
});

test('expansion can add a missing cycle edge even when both of its nodes are already visible', () => {
    const graph = fixture([['A', 'r1', 'B'], ['B', 'r2', 'A']]).graph;
    const initial = interfaces(graph, 'A');
    const depthTwo = expandView(graph, initial, iid('A'), 'outgoing', 2);
    const depthThree = expandView(graph, depthTwo, iid('A'), 'outgoing', 3);
    assert.deepEqual(depthThree.visible, depthTwo.visible);
    assert.equal(depthThree.edges.size, depthTwo.edges.size + 1);
    assert.notStrictEqual(depthThree, depthTwo);
    assert.strictEqual(expandView(graph, depthThree, iid('A'), 'outgoing', 3), depthThree);
});

for (const direction of ['outgoing', 'incoming']) {
    test(`${direction} maximum depth includes a cycle-closing edge to an already reached node`, () => {
        const flows = [['A', 'r1', 'B'], ['B', 'r2', 'C'], ['C', 'r3', 'B']];
        const graph = fixture(direction === 'outgoing' ? flows : flows.map(([from, reaction, to]) => [to, reaction, from])).graph;
        const initial = interfaces(graph, 'A');
        assert.equal(expansionLimit(graph, initial, iid('A'), direction), 6);
        const beforeClosure = expandView(graph, initial, iid('A'), direction, 5);
        const complete = expandView(graph, beforeClosure, iid('A'), direction, 6);
        assert.equal(beforeClosure.edges.size, 5);
        assert.deepEqual(complete.visible, beforeClosure.visible);
        assert.deepEqual(complete.edges, new Set(graph.flow.keys()));
        assert.equal(connections(graph, complete).length, 6);
        assert.strictEqual(expandView(graph, complete, iid('A'), direction, 6), complete);
        assert.throws(() => expandView(graph, initial, iid('A'), direction, 7));
    });
}

test('cycle-closing edge depths respect the fifty-edge cap', () => {
    for (const length of [25, 26]) {
        const graph = fixture(Array.from({length}, (_, n) =>
            [`m${n}`, `r${n}`, n === length - 1 ? 'm1' : `m${n + 1}`])).graph;
        const initial = interfaces(graph, 'm0');
        assert.equal(expansionLimit(graph, initial, iid('m0'), 'outgoing'), 50);
        const expanded = expandView(graph, initial, iid('m0'), 'outgoing', 50);
        assert.equal(expanded.edges.size, 50);
        const closure = [...graph.flow.values()].find(edge => edge.source === rid(`r${length - 1}`) && edge.target === iid('m1'));
        assert.equal(expanded.edges.has(closure.id), length === 25);
        assert.throws(() => expandView(graph, initial, iid('m0'), 'outgoing', 51));
    }
});
