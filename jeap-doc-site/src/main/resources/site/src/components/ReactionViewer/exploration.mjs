import {copyView, createView, entryView, labelOf, reactionOf} from './model.mjs';
import {changeVisibility} from './visibility.mjs';

const eligible = (graph, state, id) => graph.nodes.get(id).reaction?.status !== 'SUPERSEDED' || state.revealed.has(id);

function startingNodes(graph, state, id) {
    if (graph.groups.has(id)) return graph.groups.get(id).members.filter(member => eligible(graph, state, member));
    if (!graph.nodes.has(id)) throw new Error('Unknown starting node');
    return [id];
}

/** Breadth-first distances count flow edges, never group containment or predecessor history. */
function traverse(graph, state, starts, direction, limit = Infinity) {
    if (!['incoming', 'outgoing'].includes(direction)) throw new Error('Unknown direction');
    const index = graph[direction], distance = new Map(starts.map(id => [id, 0])), edges = new Set();
    const queue = [...distance.keys()];
    for (let cursor = 0; cursor < queue.length; cursor++) {
        const id = queue[cursor], depth = distance.get(id);
        if (depth >= limit) continue;
        for (const edgeId of index.get(id) ?? []) {
            const edge = graph.flow.get(edgeId);
            if (!eligible(graph, state, reactionOf(edge))) continue;
            edges.add(edgeId);
            const neighbour = direction === 'outgoing' ? edge.target : edge.source;
            if (distance.has(neighbour)) continue;
            distance.set(neighbour, depth + 1); queue.push(neighbour);
        }
    }
    return {distance, edges};
}

export function expansionLimit(graph, state, id, direction) {
    const walk = traverse(graph, state, startingNodes(graph, state, id), direction, 50);
    let maximum = 0;
    for (const depth of walk.distance.values()) maximum = Math.max(maximum, depth);
    return maximum;
}

const equalSet = (left, right) => left.size === right.size && [...left].every(id => right.has(id));

export function expandView(graph, previous, id, direction, depth) {
    const maximum = expansionLimit(graph, previous, id, direction);
    if (typeof depth !== 'number' || !Number.isInteger(depth) || depth < 1 || depth > maximum) {
        throw new Error(maximum ? `Enter a whole number from 1 to ${maximum}.` : 'No reachable neighbours.');
    }
    const starts = startingNodes(graph, previous, id);
    const walk = traverse(graph, previous, starts, direction, depth), state = copyView(previous);
    for (const [nodeId, distance] of walk.distance) {
        const node = graph.nodes.get(nodeId);
        state.hidden.delete(nodeId);
        if (node.reaction) {
            state.reactions.add(nodeId);
            const group = graph.groupOf.get(nodeId);
            state.selected.add(group); state.hidden.delete(group);
        }
        if (distance > 0 || !graph.groups.has(id)) state.visible.add(nodeId);
    }
    for (const edge of walk.edges) state.edges.add(edge);
    if (['visible', 'reactions', 'edges', 'hidden', 'selected'].every(key => equalSet(state[key], previous[key]))) return previous;
    return {...state, explored: true};
}

export function focusView(graph, previous, id) {
    const kind = graph.groups.has(id) ? 'GROUP' : graph.nodes.get(id)?.reaction ? 'REACTION' : 'INTERFACE';
    if (kind === 'INTERFACE' && !graph.nodes.has(id)) throw new Error('Unknown interface');
    return {...entryView(graph, {kind, id}, previous.revealed), baseline: previous.baseline};
}

function endpoints(graph, ids) {
    if (new Set(ids).size !== ids.length) throw new Error('Select each interface only once.');
    for (const id of ids) {
        if (!graph.nodes.has(id) || graph.nodes.get(id).reaction) throw new Error('Select interfaces, not reactions or groups.');
    }
}

export function interfaceLabel(graph, id) {
    const node = graph.nodes.get(id);
    const owner = graph.messageSystems.get(String(node.interfaceDetails?.message?.messageType ?? '').toLowerCase());
    return labelOf(node).replaceAll('\n', ' · ') + (owner ? ` (${owner})` : '') + ` — ${node.identity.key}`;
}

export function pathCandidates(graph, state, ids) {
    endpoints(graph, ids);
    const connected = new Set();
    for (const direction of ['outgoing', 'incoming']) {
        for (const id of traverse(graph, state, ids, direction).distance.keys()) connected.add(id);
    }
    return [...graph.nodes.values()].filter(node => !node.reaction && !ids.includes(node.id)
        && (!ids.length || connected.has(node.id)))
        .map(node => ({id: node.id, label: interfaceLabel(graph, node.id)}))
        .sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id));
}

/** Union all directed pairwise connections, including cyclic detours, without enumerating walks. */
export function pathSelection(graph, state, ids) {
    endpoints(graph, ids);
    const nodes = new Set(ids), edges = new Set();
    const forward = new Map(), backward = new Map();
    for (const [direction, reachedBy] of [['outgoing', forward], ['incoming', backward]]) {
        for (const endpoint of ids) {
            for (const node of traverse(graph, state, [endpoint], direction).distance.keys()) {
                if (!reachedBy.has(node)) reachedBy.set(node, new Set());
                reachedBy.get(node).add(endpoint);
            }
        }
    }
    // An edge belongs to the union when it connects two distinct selected endpoints.
    for (const edge of graph.flow.values()) {
        const from = forward.get(edge.source), to = backward.get(edge.target);
        if (!eligible(graph, state, reactionOf(edge)) || !from?.size || !to?.size) continue;
        if (from.size === 1 && to.size === 1 && from.values().next().value === to.values().next().value) continue;
        edges.add(edge.id); nodes.add(edge.source); nodes.add(edge.target);
    }
    return {nodes, edges, connected: edges.size > 0 && ids.length >= 2};
}

export function pathView(graph, previous, ids) {
    const ordered = [...ids].sort();
    const selection = pathSelection(graph, previous, ordered);
    if (!selection.connected) throw new Error(ids.length < 2 ? 'Select at least two interfaces.' : 'No directed connection between the selected interfaces.');
    const state = createView({kind: 'PATH', interfaces: ordered}, previous.revealed);
    state.baseline = previous.baseline; state.system = previous.system;
    state.visible = selection.nodes; state.edges = selection.edges; state.pins = new Set(ordered);
    for (const id of state.visible) {
        if (graph.nodes.get(id).reaction) {
            state.reactions.add(id); state.selected.add(graph.groupOf.get(id));
        }
    }
    return state;
}

/** All semantic actions share one boundary; presentation changes never enter it. */
export function changeView(graph, previous, action) {
    if (action.type === 'focus') return focusView(graph, previous, action.id);
    if (action.type === 'expand') return expandView(graph, previous, action.id, action.direction, action.depth);
    if (action.type === 'paths') return pathView(graph, previous, action.ids);
    return changeVisibility(graph, previous, action);
}
