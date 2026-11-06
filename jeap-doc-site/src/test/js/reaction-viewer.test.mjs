import {test} from 'node:test';
import assert from 'node:assert/strict';
import remarkReactionView from '../../main/resources/site/plugins/remark-reaction-view/index.js';
import {overviewFrame, overviewPoint, overviewSize} from '../../main/resources/site/src/components/ReactionViewer/overview.mjs';
import {viewSource} from '../../main/resources/site/src/components/ReactionViewer/source.mjs';
import {componentHref, interfaceDestination} from '../../main/resources/site/src/components/ReactionViewer/navigation.mjs';
import {readGraph, entryView, expandGroups, setGroupExpanded, project, widthOf, identity, labelOf, focusedReactionCount, visibleReactionRows} from '../../main/resources/site/src/components/ReactionViewer/model.mjs';
const key = (key, nodeType = 'INTERFACE') => ({nodeType, key});
const message = name => ({identity: key(name), nodeType: 'MESSAGE', interfaceDetails: {message: {messageType: name, variant: ''}}});
const reaction = (name, system = 's', median = 10, extra = {}) => ({identity: key(name, 'REACTION'), nodeType: 'REACTION', reaction: {system, component: 'c', median, status: 'ACTIVE', ...extra}});
const edge = (source, target, edgeType) => ({source: key(source, edgeType === 'ACTION' ? 'REACTION' : 'INTERFACE'), target: key(target, edgeType === 'TRIGGER' ? 'REACTION' : 'INTERFACE'), edgeType});
function payload() {
    return {formatVersion: 1, available: true, site: 'site', environment: 'dev', ownershipComplete: true, graph: {
        nodes: [message('A'), message('B'), reaction('r1'), reaction('r2', 's', 80), reaction('foreign', 'other'), reaction('alone'), reaction('old', 's', 0, {status: 'SUPERSEDED'})],
        edges: [edge('A','r1','TRIGGER'), edge('A','r2','TRIGGER'), edge('r1','B','ACTION'), edge('r2','B','ACTION'), edge('A','foreign','TRIGGER'), edge('A','old','TRIGGER')]}};
}
const entry = {kind: 'SYSTEM', system: 's'};
test('each reaction fence owns only its adjacent fallback table, including nested and tableless entries', () => {
    const fence = {type: 'code', lang: 'reaction-view', value: 'kind=SYSTEM&system=s'};
    const table = {type: 'table', children: []};
    const heading = {type: 'heading', children: []};
    const root = {type: 'root', children: [fence, table, heading, fence, heading, table,
        {type: 'blockquote', children: [fence, table]}, {type: 'code', lang: 'java'}, table]};
    remarkReactionView()(root);
    assert.equal(root.children[0].data.hProperties.className[0], 'jeapReactionView');
    assert.equal(root.children[0].children[1].data.hProperties.className[0], 'jeapReactionTableFallback');
    assert.equal(root.children[0].children[1].children[0], table);
    assert.equal(root.children[2].children.length, 1);
    assert.equal(root.children[4], table);
    assert.equal(root.children[5].children[0].children[1].children[0], table);
    assert.equal(root.children[6].lang, 'java');
    assert.equal(root.children[7], table);
});
test('live rows follow visible reactions with exact medians and deduplicated typed interface labels', () => {
    const data = payload();
    data.graph.nodes.find(node => node.identity.key === 'r1').reaction.median = 1.25;
    data.graph.nodes.find(node => node.identity.key === 'alone').reaction.median = 0;
    data.graph.nodes.find(node => node.identity.key === 'A').interfaceDetails.message.variant = 'express';
    data.graph.nodes.push({identity: key('http'), nodeType: 'HTTP_OPERATION',
        interfaceDetails: {http: {provider: 'api', method: 'GET', path: '/items'}}},
    {identity: key('future'), nodeType: 'UNKNOWN', interfaceDetails: {interfaceType: 'FUTURE', fqn: '<queue>'}});
    data.graph.edges.push(edge('r1', 'http', 'ACTION'), edge('r1', 'future', 'ACTION'), edge('r1', 'B', 'ACTION'),
        {source: key('r1', 'REACTION'), target: key('old', 'REACTION'), edgeType: 'PREDECESSOR'});
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    const before = visibleReactionRows(graph, state);
    assert.deepEqual(before, [
        {id: identity(key('alone', 'REACTION')), component: 'c', trigger: null, answers: [], median: 0},
        {id: identity(key('r1', 'REACTION')), component: 'c', trigger: 'A [express]',
            answers: ['B', 'FUTURE <queue>', 'api GET /items'], median: 1.25},
        {id: identity(key('r2', 'REACTION')), component: 'c', trigger: 'A [express]', answers: ['B'], median: 80},
    ]);
    const group = graph.groupOf.get(identity(key('r1', 'REACTION')));
    const collapsed = setGroupExpanded(graph, state, group, false);
    assert.deepEqual(visibleReactionRows(graph, collapsed), [before[0]]);
    assert.deepEqual(visibleReactionRows(graph, setGroupExpanded(graph, collapsed, group, true)), before);
    assert.deepEqual(visibleReactionRows(graph, {...state, visible: new Set()}), []);
    // A second viewer derives its rows from its own selection, independently of the collapsed group.
    const other = expandGroups(graph, entryView(graph, {kind: 'MESSAGE', message: 'B', variant: ''}));
    assert.deepEqual(visibleReactionRows(graph, other).map(row => row.id), before.slice(1).map(row => row.id));
});
test('focus counts stay within system and component ownership and exclude superseded reactions', () => {
    const data = payload();
    data.graph.nodes.push(reaction('other-component', 's', 1, {component: 'other'}));
    const graph = readGraph(data, 'site', 'dev');
    for (const [focus, count] of [
        [entry, 4],
        [{kind: 'COMPONENT', system: 's', component: 'c'}, 3],
        [{kind: 'COMPONENT', system: 'other', component: 'c'}, 1],
        [{kind: 'COMPONENT', system: 's', component: 'missing'}, 0],
    ]) {
        const state = entryView(graph, focus);
        assert.equal(focusedReactionCount(graph, state), count);
        assert.equal(focusedReactionCount(graph, expandGroups(graph, state)), count);
    }
});
test('message focus counts direct consumers and producers once, not other members of their groups', () => {
    const data = payload();
    data.graph.nodes.push(reaction('sibling'));
    for (const node of data.graph.nodes.filter(node => node.reaction)) node.reaction.groupKey = 'shared';
    data.graph.edges.push(edge('B', 'sibling', 'TRIGGER'), edge('r1', 'A', 'ACTION'), edge('A', 'r1', 'TRIGGER'));
    const graph = readGraph(data, 'site', 'dev');
    const focus = {kind: 'MESSAGE', message: 'A', variant: ''};
    const state = expandGroups(graph, entryView(graph, focus));
    assert.ok(!state.visible.has(identity(key('sibling', 'REACTION'))));
    assert.equal(focusedReactionCount(graph, state), 3);
    const collapsed = setGroupExpanded(graph, state, graph.groupOf.get(identity(key('r1', 'REACTION'))), false);
    assert.equal(focusedReactionCount(graph, collapsed), 3);
    assert.equal(focusedReactionCount(graph, entryView(graph, {...focus, message: 'B'})), 3);
    assert.equal(focusedReactionCount(graph, entryView(graph, {...focus, variant: 'unobserved'})), 0);
});
test('minimap maps negative graph coordinates and clips the visible frame without changing its scale', () => {
    const bounds = {x1: -200, y1: -100, w: 1000, h: 500};
    assert.deepEqual(overviewSize(bounds), {width: 220, height: 110});
    assert.deepEqual(overviewFrame(bounds, {x1: -300, y1: 25, x2: 300, y2: 500}),
        {left: 0, top: 25, width: 50, height: 75});
    assert.deepEqual(overviewPoint(bounds, 165, 55, 220, 110), {x: 550, y: 150});
    assert.deepEqual(overviewPoint(bounds, -20, 200, 220, 110), {x: -200, y: 400});
});
test('JSON source represents the current projection with opaque identities and no unrelated raw graph', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    const collapsed = setGroupExpanded(graph, state, graph.groupOf.get(identity(key('r1', 'REACTION'))), false);
    const elements = project(graph, collapsed).elements;
    const source = JSON.parse(viewSource(elements, {...entry, componentLinks: [{href: '/unused'}]}, 'site', 'dev'));
    assert.deepEqual(source, {formatVersion: 1, site: 'site', environment: 'dev', entry,
        elements: JSON.parse(JSON.stringify(elements))});
    assert.equal(source.elements.filter(element => element.data.kind === 'reaction').length, 1);
    assert.ok(source.elements.some(element => element.data.id.startsWith('hidden:')));
    assert.ok(!source.elements.some(element => element.data.id === identity(key('foreign', 'REACTION'))));
});
test('interface navigation resolves messages and REST providers without guessing ambiguous or foreign destinations', () => {
    const event = message('A'), command = message('C');
    const http = {interfaceDetails: {http: {provider: 'Provider', method: 'GET', path: '/items'}}};
    const nav = {interfaceLinks: [
        {kind: 'EVENT', name: 'a', href: '/dev/systems/s/events/a/'},
        {kind: 'COMMAND', name: 'C', href: '/dev/systems/s/commands/c/'},
        {kind: 'REST', name: 'provider', href: '/dev/systems/s/components/provider/rest-api/'},
    ]};
    assert.deepEqual(interfaceDestination(nav, event, '/dev/'), {href: nav.interfaceLinks[0].href, label: 'Go to event documentation'});
    assert.equal(interfaceDestination(nav, command, '/dev/').label, 'Go to command documentation');
    assert.deepEqual(interfaceDestination(nav, http, '/dev/'), {href: nav.interfaceLinks[2].href, label: 'Go to REST documentation'});
    assert.equal(interfaceDestination(nav, message('unknown'), '/dev/').href, null);
    assert.equal(interfaceDestination(nav, http, '/prod/').href, null);
    assert.equal(interfaceDestination({interfaceLinks: [{kind: 'EVENT', name: 'A', href: 'javascript:alert(1)'}]}, event, '/dev/').href, null);
    nav.interfaceLinks.push({kind: 'REST', name: 'Provider', href: '/dev/systems/other/rest-api/'});
    assert.equal(interfaceDestination(nav, http, '/dev/').href, null);
});
test('component navigation uses generated destinations in the current site and environment', () => {
    const href = '/site/docs/dev/systems/s/components/c/runtime';
    const navigationEntry = {...entry, componentLinks: [{system: 'S', component: 'C', href}]};
    const owner = {system: 's', component: 'c'};
    assert.equal(componentHref(navigationEntry, owner, '/site/docs/dev/'), href);
    assert.equal(componentHref(navigationEntry, owner, '/site/docs/prod/'), null);
    assert.equal(componentHref(navigationEntry, {system: 'other', component: 'c'}, '/site/docs/dev/'), null);
    assert.equal(componentHref(navigationEntry, {}, '/site/docs/dev/'), null);
    assert.equal(componentHref({...navigationEntry, kind: 'COMPONENT', component: 'c'}, owner, '/site/docs/dev/'), null);
    for (const href of ['javascript:alert(1)', '//outside.example/path', '/site/docs/dev/systems/../../outside',
        '/site/docs/dev/systems/c\\evil', '/site/docs/dev/systems/c\n', '/site/docs/dev/systems/c?query',
        '/site/docs/dev/systems/c#fragment']) {
        assert.equal(componentHref({...entry, componentLinks: [{...owner, href}]}, owner, '/site/docs/dev/'), null);
    }
});
test('system boundary, stable grouping, collapsed contributions and deduplication', () => {
    const data = payload(); data.graph.edges.push(edge('r1','B','ACTION'));
    const graph = readGraph(data,'site','dev');
    assert.equal(graph.groups.size, 3);
    const result = project(graph, entryView(graph, entry));
    const arrows = result.elements.filter(e => e.data.source);
    assert.equal(arrows.length, 2);
    assert.equal(arrows[0].data.width, widthOf(90));
    assert.match(arrows[0].data.tooltip, /approximation: 90/);
    assert.equal(result.superseded, 1);
    assert.equal(result.elements.filter(e=>e.data.kind==='reaction').length, 0);
    assert.equal(result.elements.filter(e=>e.data.id.startsWith('boundary:'))[0].data.label, '1');
});
test('message entry pins only its message and exposes directly connected reactions', () => {
    const graph = readGraph(payload(),'site','dev');
    const state = entryView(graph,{kind:'MESSAGE',message:'B',variant:''});
    assert.deepEqual([...state.pins], [identity(key('B'))]);
    const projected = project(graph,state);
    assert.equal(projected.elements.filter(e=>e.data.kind==='reaction').length, 2);
    assert.deepEqual(projected.elements.filter(e=>e.data.source && e.data.target===identity(key('B'))).map(e=>e.data.width), [widthOf(10),widthOf(80)]);
});
test('component entry excludes an identically named component in another system', () => {
    const graph = readGraph(payload(),'site','dev');
    const state = entryView(graph,{kind:'COMPONENT',system:'other',component:'c'});
    assert.equal(state.selected.size,1); assert.equal(state.pins.size,0);
});
test('unknown triggers remain separate, V2 group keys preserve system boundaries', () => {
    const data = payload(); data.graph.nodes.push(reaction('alone2'));
    for (const n of data.graph.nodes.filter(n=>n.reaction)) n.reaction.groupKey='same';
    const graph=readGraph(data,'site','dev'); assert.equal(graph.groups.size,4);
});
test('history never becomes a flow arrow; unknown interface labels keep identity', () => {
    const data=payload(); data.graph.edges.push({edgeType:'PREDECESSOR',source:key('r2','REACTION'),target:key('r1','REACTION')});
    const node={identity:key('9007199254740993'),nodeType:'FUTURE',interfaceDetails:{interfaceType:'CUSTOM',fqn:'<script>alert(1)</script>'}};
    data.graph.nodes.push(node);
    const graph=readGraph(data,'site','dev'); assert.equal(graph.edges.length,7);
    assert.equal(project(graph,entryView(graph,entry)).elements.filter(e=>e.data.source).length,2);
    assert.equal(labelOf(node),'CUSTOM\n<script>alert(1)</script>');
});
test('invalid replacement and foreign context are rejected, zero is valid', () => {
    const data=payload(); data.graph.nodes[2].reaction.median=0;
    assert.doesNotThrow(()=>readGraph(data,'site','dev'));
    for(const median of [null,-1,'0',Infinity]) {
        data.graph.nodes[2].reaction.median=median;
        assert.throws(()=>readGraph(data,'site','dev'));
    }
    assert.throws(()=>readGraph(payload(),'other','dev'));
    assert.throws(()=>readGraph(payload(),'site','prod'));
    assert.equal(widthOf(0),1); assert.equal(widthOf(1e10),6);
});
test('message view excludes sibling reactions and every edge not touching the selected event', () => {
    const data=payload(); data.graph.nodes.push(message('C'));
    data.graph.edges=data.graph.edges.filter(e=>!(e.edgeType==='ACTION' && e.source.key==='r2'));
    data.graph.edges.push(edge('r2','C','ACTION'));
    const graph=readGraph(data,'site','dev');
    const state=entryView(graph,{kind:'MESSAGE',message:'B',variant:''});
    const result=project(graph,state);
    assert.equal(result.elements.filter(e=>e.data.kind==='reaction').length,1);
    assert.deepEqual(result.elements.filter(e => e.data.source).map(e => [e.data.source, e.data.target]),
        [[identity(key('r1', 'REACTION')), identity(key('B'))]]);
    assert.deepEqual([...expandGroups(graph, state).visible].sort(),
        [identity(key('B')), identity(key('r1', 'REACTION'))].sort());
    assert.deepEqual(visibleReactionRows(graph, state), [{id: identity(key('r1', 'REACTION')),
        component: 'c', trigger: null, answers: ['B'], median: 10}]);
});
test('internal cycles terminate and preserve both directed paths',()=>{
    const data=payload(); data.graph.nodes.push(reaction('cycle'));
    data.graph.edges.push(edge('B','cycle','TRIGGER'),edge('cycle','A','ACTION'));
    const graph=readGraph(data,'site','dev');
    assert.equal(project(graph,entryView(graph,entry)).elements.filter(e=>e.data.source).length,4);
});
test('superseded-only message neighbours are disclosed and never invent zero-frequency flow', () => {
    const data=payload(); data.graph.nodes.push(message('old-only'));
    data.graph.edges.push(edge('old','old-only','ACTION'));
    const graph=readGraph(data,'site','dev');
    const result=project(graph,entryView(graph,{kind:'MESSAGE',message:'old-only',variant:''}));
    assert.equal(result.superseded,1);
    assert.equal(result.elements.filter(e=>e.data.source).length,0);
});
test('a malformed object label rejects the entire snapshot',()=>{
    const data=payload(); data.graph.nodes[0].interfaceDetails.message.messageType={bad:'label'};
    assert.throws(()=>readGraph(data,'site','dev'));
});

test('expanding groups reveals their active members without crossing the system boundary', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const initial = entryView(graph, entry);
    const expanded = expandGroups(graph, initial);
    assert.equal(project(graph, expanded).elements.filter(e => e.data.kind === 'reaction').length, 3);
    assert.equal(project(graph, initial).elements.filter(e => e.data.kind === 'reaction').length, 0);
    assert.ok(!expanded.visible.has(identity(key('foreign', 'REACTION'))));
    assert.ok(!expanded.visible.has(identity(key('old', 'REACTION'))));
});

test('three expanded triggers keep individual medians; only hidden reactions share a bundled trigger', () => {
    const data = payload();
    data.graph.nodes.push(reaction('r3', 's', 0));
    data.graph.edges.push(edge('A', 'r3', 'TRIGGER'), edge('A', 'r1', 'TRIGGER'));
    const graph = readGraph(data, 'site', 'dev');
    const collapsed = entryView(graph, entry);
    const expanded = expandGroups(graph, collapsed);
    const triggers = state => project(graph, state).elements.filter(e => e.data.source === identity(key('A'))).map(e => e.data);
    assert.deepEqual(triggers(expanded).map(e => [e.target, e.width, e.tooltip]), [
        [identity(key('r1', 'REACTION')), widthOf(10), 'Median per day: 10'],
        [identity(key('r2', 'REACTION')), widthOf(80), 'Median per day: 80'],
        [identity(key('r3', 'REACTION')), widthOf(0), 'Median per day: 0'],
    ]);
    assert.equal(triggers(collapsed).length, 1);
    assert.equal(triggers(collapsed)[0].width, widthOf(90));
    const partial = {...expanded, visible: new Set([...expanded.visible].filter(id => id !== identity(key('r2', 'REACTION')) && id !== identity(key('r3', 'REACTION'))))};
    assert.deepEqual(triggers(partial).map(e => [e.target, e.width]), [
        [identity(key('r1', 'REACTION')), widthOf(10)],
        [graph.groupOf.get(identity(key('r2', 'REACTION'))), widthOf(80)],
    ]);
});

test('collapsing a message entry preserves its selection and pin but hides all individual reactions', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const initial = entryView(graph, {kind: 'MESSAGE', message: 'A', variant: ''});
    const expanded = expandGroups(graph, initial);
    let collapsed = expanded;
    for (const group of expanded.selected) collapsed = setGroupExpanded(graph, collapsed, group, false);
    assert.deepEqual(collapsed.selected, initial.selected);
    assert.deepEqual(collapsed.pins, initial.pins);
    assert.deepEqual(collapsed.visible, new Set([identity(key('A'))]));
    assert.deepEqual(expandGroups(graph, collapsed), expanded);
});

test('one group action preserves other groups and pinned reactions, and never reveals superseded members', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    const first = identity(key('r1', 'REACTION')), second = identity(key('r2', 'REACTION'));
    const group = graph.groupOf.get(first);
    state.pins.add(first);
    const collapsed = setGroupExpanded(graph, state, group, false);
    assert.deepEqual(collapsed.visible, new Set([...state.visible].filter(id => id !== second)));
    assert.ok(state.visible.has(second));
    assert.deepEqual(setGroupExpanded(graph, collapsed, group, true), state);
    assert.ok(!collapsed.visible.has(identity(key('old', 'REACTION'))));
    assert.throws(() => setGroupExpanded(graph, state, graph.groupOf.get(identity(key('foreign', 'REACTION'))), true));
});

test('diagram labels match legacy suffix and variant conventions without changing identities or full names', () => {
    for (const [name, expected] of [['OrderAcceptedEvent', 'OrderAccepted'], ['AcceptOrderCommand', 'AcceptOrder'],
        ['Event', 'Event'], ['ShippingBulkEvent29', 'ShippingBulkEvent29']]) {
        for (const component of ['orders-service', 'orders-scs']) {
            const data = payload();
            data.graph.nodes[0].interfaceDetails.message = {messageType: name, variant: 'express'};
            data.graph.nodes[2].reaction.component = component;
            const graph = readGraph(data, 'site', 'dev');
            const {elements} = project(graph, expandGroups(graph, entryView(graph, entry)));
            const find = id => elements.find(element => element.data.id === id).data;
            assert.equal(find(identity(key('A'))).displayLabel, expected + '\n[express]');
            assert.equal(find(identity(key('A'))).label, name + '\nexpress');
            const reaction = find(identity(key('r1', 'REACTION')));
            assert.equal(reaction.displayLabel, '10');
            assert.equal(reaction.tooltip, 'Median per day: 10');
            assert.equal(reaction.label, component + '\nr1');
            assert.equal(find(reaction.parent).displayLabel, 'orders');
        }
    }
});

test('foreign colours use documented message ownership and the entry system, without guessing unknown owners', () => {
    const data = payload();
    data.messageOwnership = [{message: 'a', system: 'S'}, {message: 'b', system: 'other'}];
    const graph = readGraph(data, 'site', 'dev');
    const systemView = project(graph, expandGroups(graph, entryView(graph, entry)));
    const node = (view, id) => view.elements.find(element => element.data.id === id).data;
    assert.equal(node(systemView, identity(key('A'))).foreign, false);
    assert.equal(node(systemView, identity(key('B'))).foreign, true);
    assert.equal(node(systemView, identity(key('r1', 'REACTION'))).foreign, false);
    const messageView = project(graph, expandGroups(graph, entryView(graph, {kind: 'MESSAGE', message: 'B', variant: ''})));
    assert.equal(node(messageView, identity(key('B'))).foreign, false);
    assert.equal(node(messageView, identity(key('r1', 'REACTION'))).foreign, true);
    assert.equal(node(messageView, graph.groupOf.get(identity(key('r1', 'REACTION')))).foreign, true);
    const unknownGraph = readGraph(payload(), 'site', 'dev');
    const unknownView = project(unknownGraph, entryView(unknownGraph, entry));
    assert.equal(node(unknownView, identity(key('B'))).foreign, false);
});

test('event view keeps only direct producers and consumers through collapse and expansion', () => {
    const data = payload();
    data.graph.nodes = [message('A'), message('B'), message('C'), reaction('r1', 's', 0), reaction('r2', 's', 1.25)];
    data.graph.edges = [edge('A', 'r1', 'TRIGGER'), edge('r1', 'B', 'ACTION'),
        edge('B', 'r2', 'TRIGGER'), edge('r2', 'C', 'ACTION')];
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, {kind: 'MESSAGE', message: 'B', variant: ''}));
    const projected = project(graph, state).elements.map(element => element.data);
    assert.deepEqual(projected.filter(node => node.kind === 'message').map(node => node.id), [identity(key('B'))]);
    assert.deepEqual(projected.filter(node => node.kind === 'reaction').map(node => node.displayLabel), ['0', '1.25']);
    assert.deepEqual(projected.filter(edge => edge.source).map(edge => [edge.source, edge.target]), [
        [identity(key('r1', 'REACTION')), identity(key('B'))], [identity(key('B')), identity(key('r2', 'REACTION'))],
    ]);
    let collapsed = state;
    for (const group of state.selected) collapsed = setGroupExpanded(graph, collapsed, group, false);
    assert.deepEqual(project(graph, expandGroups(graph, collapsed)), project(graph, state));
});
