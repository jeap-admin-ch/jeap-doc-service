import {test} from 'node:test';
import {orderGroupChildren} from '../../main/resources/site/src/components/ReactionViewer/layout.mjs';
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
test('message entry starts pinned and exposes directly connected reactions', () => {
    const graph = readGraph(payload(),'site','dev');
    const state = entryView(graph,{kind:'MESSAGE',message:'B',variant:''});
    assert.deepEqual([...state.pins], [mid('B')]);
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


test('group layout removes avoidable crossings without changing graph membership or input order', () => {
    const children = new Map([['group', ['lower', 'upper', 'middle']]]);
    const parents = new Map(children.get('group').map(id => [id, 'group']));
    const positions = new Map([['group', {x: 100, y: 0}], ['top', {x: 200, y: -100}],
        ['center', {x: 200, y: 0}], ['bottom', {x: 200, y: 100}], ['trigger', {x: 0, y: 0}]]);
    const edges = [['lower', 'bottom'], ['upper', 'top'], ['middle', 'center'],
        ['trigger', 'lower'], ['trigger', 'upper'], ['trigger', 'middle']]
        .map(([source, target]) => ({data: {source, target}}));
    const ordered = orderGroupChildren(children, parents, edges, positions);
    assert.deepEqual(ordered.get('group'), ['upper', 'middle', 'lower']);
    assert.deepEqual(children.get('group'), ['lower', 'upper', 'middle']);
    assert.deepEqual(orderGroupChildren(ordered, parents, edges, positions), ordered);
    // Reversing flow does not change where the neighbouring endpoints are drawn.
    assert.deepEqual(orderGroupChildren(children, parents, edges.map(({data}) =>
        ({data: {source: data.target, target: data.source}})), positions), ordered);
});

test('group layout balances incoming and outgoing crossings and keeps ties stable', () => {
    const children = new Map([['group', ['a', 'b', 'unconnected']]]);
    const parents = new Map(children.get('group').map(id => [id, 'group']));
    const positions = new Map([['group', {x: 100, y: 0}], ['leftTop', {x: 0, y: -100}],
        ['leftBottom', {x: 0, y: 100}], ['rightTop', {x: 200, y: -100}], ['rightBottom', {x: 200, y: 100}]]);
    const edges = [['leftTop', 'a'], ['leftBottom', 'b'], ['a', 'rightBottom'], ['b', 'rightTop']]
        .map(([source, target]) => ({data: {source, target}}));
    assert.deepEqual(orderGroupChildren(children, parents, edges, positions), children);
    assert.deepEqual(orderGroupChildren(children, parents, [], positions), children);
});

test('JSON source explains opaque group ids with ownership and structured grouping metadata', () => {
    const data = payload();
    const trigger = data.graph.nodes.find(node => node.identity.key === 'A');
    trigger.interfaceDetails.message = {messageType: 'OrderAcceptedEvent', variant: 'express'};
    const graph = readGraph(data, 'site', 'dev');
    const state = entryView(graph, entry);
    const source = JSON.parse(viewSource(project(graph, state).elements, entry, 'site', 'dev'));
    const group = source.elements.find(element => element.data.id === graph.groupOf.get(identity(key('r1', 'REACTION')))).data;
    assert.equal(group.system, 's');
    assert.equal(group.component, 'c');
    assert.deepEqual(group.grouping, {trigger: {nodeType: 'INTERFACE', key: 'A', label: 'OrderAcceptedEvent\nexpress'}});
    const standalone = source.elements.find(element => element.data.id === graph.groupOf.get(identity(key('alone', 'REACTION')))).data;
    assert.deepEqual(standalone.grouping, {reaction: key('alone', 'REACTION')});
    const node = source.elements.find(element => element.data.id === identity(key('A'))).data;
    assert.deepEqual(node.identity, key('A'));
    assert.equal(node.label, 'OrderAcceptedEvent\nexpress');
});

test('explicit group keys do not imply one shared trigger and unknown owners remain unknown', () => {
    const data = payload();
    for (const node of data.graph.nodes.filter(node => node.reaction)) node.reaction.groupKey = 'checkout/"express"';
    data.graph.edges = data.graph.edges.filter(edge => !(edge.edgeType === 'TRIGGER' && edge.target.key === 'r2'));
    data.graph.edges.push(edge('B', 'r2', 'TRIGGER'));
    const graph = readGraph(data, 'site', 'dev');
    const group = project(graph, entryView(graph, entry)).elements.find(element =>
        element.data.id === graph.groupOf.get(identity(key('r1', 'REACTION')))).data;
    assert.deepEqual(group.grouping, {groupKey: 'checkout/"express"'});
    data.graph.nodes.find(node => node.identity.key === 'r1').reaction.system = null;
    const unknown = readGraph(data, 'site', 'dev');
    const unknownGroup = project(unknown, entryView(unknown, {kind: 'MESSAGE', message: 'A', variant: ''})).elements
        .find(element => element.data.id === unknown.groupOf.get(identity(key('r1', 'REACTION')))).data;
    assert.equal(unknownGroup.system, null);
    assert.deepEqual(unknownGroup.grouping, {reaction: key('r1', 'REACTION')});
});

// Visibility fixtures use independent groups so containers cannot invent alternate flow paths.
import {changeVisibility, hiddenMarkers} from '../../main/resources/site/src/components/ReactionViewer/visibility.mjs';
const mid = name => identity(key(name));
const rid = name => identity(key(name, 'REACTION'));
function chainFixture({alternate = false, cycle = false} = {}) {
    const data = payload();
    data.graph.nodes = ['A', 'B', 'C', 'D', 'E'].map(message)
        .concat(['r1', 'r2', 'r3', 'r4'].map(name => reaction(name, 's', 10, {groupKey: name})));
    data.graph.edges = [edge('A', 'r1', 'TRIGGER'), edge('r1', 'B', 'ACTION'),
        edge('B', 'r2', 'TRIGGER'), edge('r2', 'C', 'ACTION'),
        edge('D', 'r4', 'TRIGGER'), edge('r4', 'E', 'ACTION')];
    if (alternate) data.graph.edges.push(edge('A', 'r3', 'TRIGGER'), edge('r3', 'C', 'ACTION'));
    if (cycle) data.graph.edges.push(edge('C', 'r3', 'TRIGGER'), edge('r3', 'A', 'ACTION'));
    const graph = readGraph(data, 'site', 'dev');
    return {graph, state: expandGroups(graph, entryView(graph, entry))};
}
const act = (graph, state, type, id) => changeVisibility(graph, state, {type, id});
test('ordinary hide preserves only pin-connected affected contents, with arrows ignored', () => {
    for (const bothPins of [false, true]) {
        const {graph, state} = chainFixture();
        state.pins.add(mid('A'));
        if (bothPins) state.pins.add(mid('C'));
        const original = structuredClone(state), originalGraph = structuredClone(graph);
        const result = act(graph, state, 'hide', mid('B'));
        assert.equal(result.visible.has(mid('B')), false);
        assert.equal(result.visible.has(rid('r2')), bothPins);
        assert.equal(result.visible.has(mid('C')), bothPins);
        for (const id of [mid('A'), rid('r1'), mid('D'), rid('r4'), mid('E')]) assert.ok(result.visible.has(id));
        assert.deepEqual(state, original);
        assert.deepEqual(graph, originalGraph);
    }
});
test('alternate routes and cycles preserve connections; previously hidden routes do not', () => {
    for (const config of [{alternate: true}, {cycle: true}]) {
        const {graph, state} = chainFixture(config);
        const hiddenRoute = act(graph, act(graph, state, 'pin', mid('A')), 'hide', rid('r3'));
        for (const candidate of [state, hiddenRoute]) {
            const pinned = {...candidate, pins: new Set([mid('A')])};
            const result = act(graph, pinned, 'hide', mid('B'));
            assert.equal(result.visible.has(mid('C')), candidate === state);
            assert.equal(result.visible.has(rid('r2')), candidate === state);
        }
    }
});
test('no pin, a separate pin and removal of the last local pin never cause global pruning', () => {
    for (const pins of [[], ['D'], ['B'], ['B', 'D']]) {
        const {graph, state} = chainFixture();
        pins.forEach(name => state.pins.add(mid(name)));
        const result = act(graph, state, 'hide', mid('B'));
        assert.deepEqual(result.visible, new Set([mid('D'), rid('r4'), mid('E'), rid('r3')]));
        assert.deepEqual(result.pins, new Set(pins.filter(name => name !== 'B').map(mid)));
    }
});
test('group hide removes its pins atomically and protects outside pins', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const initial = expandGroups(graph, entryView(graph, entry));
    const group = graph.groupOf.get(rid('r1'));
    for (const outside of [false, true]) {
        const state = {...initial, pins: new Set([group, rid('r1'), ...(outside ? [mid('A'), mid('B')] : [])])};
        const result = act(graph, state, 'hide', group);
        assert.deepEqual(result.pins, new Set(outside ? [mid('A'), mid('B')] : []));
        for (const id of [mid('A'), mid('B')]) assert.equal(result.visible.has(id), outside);
        const projected = project(graph, result).elements.map(element => element.data.id);
        for (const id of [group, rid('r1'), rid('r2')]) assert.ok(!projected.includes(id));
        assert.ok(state.pins.has(rid('r1')));
    }
});
test('partial collapse preserves individual pins, bundled medians and group pins', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    const group = graph.groupOf.get(rid('r1'));
    state.pins.add(group); state.pins.add(rid('r1'));
    const collapsed = act(graph, state, 'collapse', group);
    const nodes = project(graph, collapsed).elements.map(element => element.data);
    assert.equal(nodes.find(node => node.id === group).pinned, true);
    assert.ok(collapsed.visible.has(rid('r1')));
    assert.ok(!collapsed.visible.has(rid('r2')));
    assert.equal(nodes.find(node => node.source === mid('A') && node.target === group).width, widthOf(80));
    assert.equal(nodes.find(node => node.id === 'hidden:' + group).label, '1');
    const hidden = act(graph, collapsed, 'hide', rid('r1'));
    assert.ok(!project(graph, hidden).elements.some(element => element.data.target === rid('r1')));
    assert.equal(project(graph, hidden).elements.find(element => element.data.target === group).data.width, widthOf(80));
});
test('directed hide traverses cycles but preserves start, pins, containers and upstream regions', () => {
    for (const cycle of [false, true]) {
        const {graph, state} = chainFixture({cycle});
        state.pins.add(rid('r2'));
        const result = act(graph, state, 'outgoing', mid('B'));
        assert.ok(result.visible.has(mid('B')));
        assert.ok(result.visible.has(rid('r2')));
        assert.ok(!result.hidden.has(graph.groupOf.get(rid('r2'))));
        assert.ok(!result.visible.has(mid('C')));
        assert.equal(result.visible.has(mid('A')), !cycle);
        assert.ok(result.visible.has(mid('D')));
        assert.ok(result.visible.has(mid('E')));
    }
});
test('group outgoing hide starts from members without hiding the group or its members', () => {
    const {graph, state} = chainFixture();
    const group = graph.groupOf.get(rid('r1'));
    const result = act(graph, state, 'outgoing', group);
    assert.ok(result.visible.has(rid('r1')));
    assert.ok(result.visible.has(mid('A')));
    assert.ok(!result.visible.has(mid('B')));
    assert.ok(!result.visible.has(rid('r2')));
});
test('restore markers distinguish incoming and outgoing content and restore without mutating history', () => {
    const {graph, state} = chainFixture();
    state.pins.add(mid('A')); state.pins.add(mid('C'));
    const hidden = act(graph, state, 'hide', mid('B'));
    const markers = hiddenMarkers(graph, hidden).map(element => element.data);
    assert.deepEqual(markers.map(marker => [marker.anchor, marker.direction, marker.restore]),
        [[rid('r1'), 'outgoing', [mid('B')]], [rid('r2'), 'incoming', [mid('B')]]]);
    const restored = changeVisibility(graph, hidden, {type: 'show', ids: markers[0].restore});
    assert.deepEqual(restored, state);
    assert.ok(hidden.hidden.has(mid('B')));
});
test('superseded reveal includes its flow, shared interfaces and rows; predecessor remains inert', () => {
    const data = payload();
    data.graph.nodes.push(message('history-target'));
    data.graph.edges.push(edge('old', 'history-target', 'ACTION'),
        {source: key('r1', 'REACTION'), target: key('old', 'REACTION'), edgeType: 'PREDECESSOR'});
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    const revealed = act(graph, state, 'show', rid('old'));
    assert.ok(revealed.visible.has(mid('history-target')));
    assert.equal(project(graph, revealed).superseded, 0);
    assert.ok(visibleReactionRows(graph, revealed).some(row => row.id === rid('old')));
    const pinned = act(graph, revealed, 'pin', rid('old'));
    assert.ok(act(graph, pinned, 'outgoing', mid('A')).visible.has(rid('old')));
    const hidden = act(graph, act(graph, revealed, 'pin', mid('A')), 'hide', rid('old'));
    assert.ok(hidden.visible.has(mid('A')));
    assert.ok(!project(graph, hidden).elements.some(element => element.data.source === rid('old')));
    assert.ok(!act(graph, state, 'outgoing', rid('r1')).hidden.has(rid('old')));
});
test('unpinning message entry preserves its selection and counter; superseded reveal stays scoped', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, {kind: 'MESSAGE', message: 'A', variant: ''}));
    const unpinned = act(graph, state, 'pin', mid('A'));
    assert.deepEqual([...unpinned.pins], []);
    assert.equal(focusedReactionCount(graph, unpinned), focusedReactionCount(graph, state));
    assert.deepEqual(project(graph, unpinned).elements.filter(e => e.data.source), project(graph, state).elements.filter(e => e.data.source));
    const revealed = act(graph, unpinned, 'show', rid('old'));
    assert.ok(project(graph, revealed).elements.some(e => e.data.target === rid('old')));
    assert.ok(!revealed.visible.has(mid('B')));
});

test('hiding and restoring a message group cannot offer a sibling outside the entry', () => {
    const data = payload();
    data.graph.edges = data.graph.edges.filter(e => !(e.edgeType === 'ACTION' && e.source.key === 'r2'));
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, {kind: 'MESSAGE', message: 'B', variant: ''}));
    const group = graph.groupOf.get(rid('r1'));
    const hidden = act(graph, state, 'hide', group);
    assert.ok(!hidden.hidden.has(rid('r2')));
    const restored = act(graph, hidden, 'show', group);
    assert.deepEqual(restored, state);
});
test('repeated explicit hiding can empty the view and every hidden element can be restored', () => {
    const {graph, state} = chainFixture();
    let result = state;
    for (const group of state.selected) result = act(graph, result, 'hide', group);
    for (const id of [...result.visible]) result = act(graph, result, 'hide', id);
    assert.deepEqual(project(graph, result).elements, []);
    for (const group of state.selected) result = act(graph, result, 'show', group);
    for (const id of state.visible) result = act(graph, result, 'show', id);
    const elements = view => project(graph, view).elements.sort((a, b) => a.data.id.localeCompare(b.data.id));
    assert.deepEqual(elements(result), elements(state));
});
test('a group with separate paths cascades every affected region and preserves pins', () => {
    const data = payload();
    data.graph.nodes = ['A', 'B', 'C', 'D'].map(message)
        .concat(['r1', 'r2'].map(name => reaction(name, 's', 10, {groupKey: 'shared'})));
    data.graph.edges = [edge('A', 'r1', 'TRIGGER'), edge('r1', 'B', 'ACTION'),
        edge('C', 'r2', 'TRIGGER'), edge('r2', 'D', 'ACTION')];
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    state.pins.add(mid('A'));
    const hidden = act(graph, state, 'hide', graph.groupOf.get(rid('r1')));
    assert.deepEqual(hidden.visible, new Set([mid('A')]));
});
test('a partially collapsed bundle never hides its independently visible siblings', () => {
    const data = payload();
    data.graph.nodes = ['A', 'B', 'C', 'D'].map(message)
        .concat(['r1', 'r2'].map(name => reaction(name, 's', 10, {groupKey: 'shared'})));
    data.graph.edges = [edge('A', 'r1', 'TRIGGER'), edge('r1', 'B', 'ACTION'),
        edge('C', 'r2', 'TRIGGER'), edge('r2', 'D', 'ACTION')];
    const graph = readGraph(data, 'site', 'dev');
    const state = expandGroups(graph, entryView(graph, entry));
    state.visible.delete(rid('r1'));
    state.pins.add(rid('r2'));
    const result = act(graph, state, 'outgoing', mid('A'));
    assert.ok(result.hidden.has(rid('r1')));
    assert.ok(!result.visible.has(mid('B')));
    assert.ok(result.visible.has(rid('r2')));
    assert.ok(result.visible.has(mid('C')));
    assert.ok(result.visible.has(mid('D')));
    assert.ok(project(graph, result).elements.some(e => e.data.id === graph.groupOf.get(rid('r2'))));
});
test('a collapsed marker restores exactly its count without revealing superseded members', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = entryView(graph, entry);
    const group = graph.groupOf.get(rid('r1'));
    const marker = project(graph, state).elements.find(e => e.data.id === 'hidden:' + group).data;
    assert.equal(marker.label, '2');
    const result = changeVisibility(graph, state, {type: 'show', ids: marker.restore});
    assert.deepEqual(result.visible, new Set([...state.visible, rid('r1'), rid('r2')]));
    assert.ok(!result.revealed.has(rid('old')));
    const explicit = act(graph, state, 'show', group);
    assert.ok(explicit.visible.has(rid('old')));
});
test('a group pin remains a local pin even after its last visible member is explicitly hidden', () => {
    const {graph, state} = chainFixture();
    const group = graph.groupOf.get(rid('r1'));
    state.pins.add(group);
    const result = act(graph, state, 'hide', rid('r1'));
    assert.ok(result.pins.has(group));
    assert.ok(project(graph, result).elements.some(e => e.data.id === group && e.data.pinned));
    assert.deepEqual(project(graph, result).elements.find(e => e.data.id === 'hidden:' + group).data.restore, [rid('r1')]);
    for (const id of [mid('A'), mid('B'), mid('C'), rid('r2')]) assert.ok(!result.visible.has(id));
    assert.ok(result.visible.has(mid('D')));
});
test('outgoing hide through a collapsed group does not visit initially superseded members', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const state = entryView(graph, entry);
    const result = act(graph, state, 'outgoing', mid('A'));
    assert.ok(result.hidden.has(rid('r1')));
    assert.ok(result.hidden.has(rid('r2')));
    assert.ok(!result.hidden.has(rid('old')));
    assert.equal(project(graph, result).superseded, 1);
});

import "./reaction-exploration.test.mjs";

test('group menu expansion preserves explicit hiding and superseded status while its marker restores hidden members', () => {
    const graph = readGraph(payload(), 'site', 'dev');
    const initial = expandGroups(graph, entryView(graph, entry));
    const group = graph.groupOf.get(rid('r1'));
    initial.pins.add(mid('A'));
    const hidden = act(graph, initial, 'hide', rid('r2'));
    const collapsed = act(graph, hidden, 'collapse', group);
    const expanded = act(graph, collapsed, 'expand-group', group);
    assert.ok(expanded.visible.has(rid('r1')));
    assert.ok(!expanded.visible.has(rid('r2')));
    assert.ok(!expanded.visible.has(rid('old')));
    assert.deepEqual(expanded.hidden, hidden.hidden);
    assert.deepEqual(expanded.revealed, hidden.revealed);
    const marker = project(graph, expanded).elements.find(e => e.data.id === 'hidden:' + group).data;
    assert.equal(marker.label, '1');
    assert.deepEqual(marker.restore, [rid('r2')]);
    const restored = changeVisibility(graph, expanded, {type: 'show', ids: marker.restore});
    assert.ok(restored.visible.has(rid('r2')));
    assert.ok(!restored.hidden.has(rid('r2')));
    assert.ok(!restored.visible.has(rid('old')));
    assert.deepEqual(restored.revealed, initial.revealed);
});


test('live rows exclude hidden trigger and action connections and restore them with their interfaces', () => {
    const data = payload();
    data.graph.nodes = [message('A'), reaction('r1'), message('B')];
    data.graph.edges = [edge('A', 'r1', 'TRIGGER'), edge('r1', 'B', 'ACTION')];
    const graph = readGraph(data, 'site', 'dev');
    const initial = expandGroups(graph, entryView(graph, entry));
    initial.pins.add(rid('r1'));
    const row = {id: rid('r1'), component: 'c', trigger: 'A', answers: ['B'], median: 10};
    assert.deepEqual(visibleReactionRows(graph, initial), [row]);
    for (const [action, endpoint, expected] of [
        [{type: 'outgoing', id: rid('r1')}, mid('B'), {...row, answers: []}],
        [{type: 'hide', id: mid('A')}, mid('A'), {...row, trigger: null}],
    ]) {
        const hidden = changeVisibility(graph, initial, action);
        assert.deepEqual(hidden.edges, initial.edges);
        assert.ok(!project(graph, hidden).elements.some(({data}) => data.source === endpoint || data.target === endpoint));
        assert.deepEqual(visibleReactionRows(graph, hidden), [expected]);
        const restored = changeVisibility(graph, hidden, {type: 'show', id: endpoint});
        assert.deepEqual(visibleReactionRows(graph, restored), [row]);
        assert.deepEqual(visibleReactionRows(graph, initial), [row]);
    }
});
