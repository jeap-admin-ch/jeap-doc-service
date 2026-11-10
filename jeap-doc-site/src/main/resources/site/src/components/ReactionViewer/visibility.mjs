import {availableReaction, copyView, project, setGroupExpanded} from './model.mjs';

function topology(graph, state, directed = false) {
    const adjacency = new Map();
    const connect = (from, to) => {
        if (!adjacency.has(from)) adjacency.set(from, new Set());
        adjacency.get(from).add(to);
    };
    for (const {data} of project(graph, state).elements) {
        if (data.source) {
            connect(data.source, data.target);
            if (!directed) connect(data.target, data.source);
        } else if (data.kind !== 'marker' && !adjacency.has(data.id)) adjacency.set(data.id, new Set());
    }
    return adjacency;
}

function reach(adjacency, seeds) {
    const visited = new Set(), pending = [...seeds];
    while (pending.length) {
        const id = pending.pop();
        if (visited.has(id) || !adjacency.has(id)) continue;
        visited.add(id);
        for (const next of adjacency.get(id)) pending.push(next);
    }
    return visited;
}

function seeds(graph, state, ids) {
    return [...ids].flatMap(id => graph.groups.has(id)
        ? [id, ...graph.groups.get(id).members.filter(member => state.visible.has(member))] : [id]);
}

function remove(graph, state, ids) {
    for (const id of ids) {
        state.hidden.add(id);
        state.visible.delete(id);
        if (graph.groups.has(id)) {
            for (const member of graph.groups.get(id).members) {
                if (!state.reactions.has(member)) continue;
                state.hidden.add(member);
                state.visible.delete(member);
            }
        }
    }
}

function removeProjected(graph, state, ids) {
    const targets = new Set();
    for (const id of ids) {
        if (graph.groups.has(id)) {
            // A bundled endpoint represents collapsed members, not their visible siblings.
            for (const member of graph.groups.get(id).members) {
                if (!state.visible.has(member) && availableReaction(graph, state, member)
                    && state.reactions.has(member)) targets.add(member);
            }
        } else targets.add(id);
    }
    remove(graph, state, targets);
}

function showNode(graph, state, id) {
    state.hidden.delete(id);
    state.visible.add(id);
    if (!graph.nodes.get(id).reaction) return;
    const group = graph.groupOf.get(id);
    state.selected.add(group);
    state.hidden.delete(group);
    if (graph.nodes.get(id).reaction.status === 'SUPERSEDED') state.revealed.add(id);
    for (const edgeId of [...(graph.incoming.get(id) ?? []), ...(graph.outgoing.get(id) ?? [])]) {
        const edge = graph.flow.get(edgeId);
        if (!state.edges.has(edge.id)) continue;
        const neighbour = edge.edgeType === 'TRIGGER' && edge.target === id ? edge.source
            : edge.edgeType === 'ACTION' && edge.source === id ? edge.target : null;
        if (neighbour && !state.hidden.has(neighbour)) state.visible.add(neighbour);
    }
}

/** One command produces one complete semantic state; neither input nor snapshot is changed. */
export function changeVisibility(graph, previous, action) {
    let state = copyView(previous);
    const {id} = action;
    if (action.type === 'pin') {
        if (state.pins.has(id)) state.pins.delete(id); else state.pins.add(id);
    } else if (action.type === 'collapse' || action.type === 'expand-group') {
        state = setGroupExpanded(graph, state, id, action.type === 'expand-group');
    } else if (action.type === 'show') {
        for (const target of action.ids ?? [id]) {
            state.hidden.delete(target);
            if (graph.groups.has(target)) {
                for (const member of graph.groups.get(target).members) {
                    if (!state.reactions.has(member)) continue;
                    showNode(graph, state, member);
                }
            } else showNode(graph, state, target);
        }
    } else if (action.type === 'hide') {
        const before = topology(graph, previous);
        const regions = [];
        const seen = new Set();
        for (const seed of seeds(graph, previous, [id])) {
            if (seen.has(seed)) continue;
            const region = reach(before, [seed]);
            region.forEach(node => seen.add(node));
            regions.push(region);
        }
        state.pins.delete(id);
        for (const member of graph.groups.get(id)?.members ?? []) state.pins.delete(member);
        remove(graph, state, [id]);
        const after = topology(graph, state), removed = new Set();
        for (const region of regions) {
            const remainingPins = [...state.pins].filter(pin =>
                seeds(graph, previous, [pin]).some(seed => region.has(seed)));
            if (!remainingPins.length) continue;
            const retained = reach(after, seeds(graph, state, remainingPins));
            for (const node of region) if (!retained.has(node)) removed.add(node);
        }
        removeProjected(graph, state, removed);
    } else if (action.type === 'outgoing') {
        const starts = seeds(graph, previous, [id]);
        const reached = reach(topology(graph, previous, true), starts);
        const protectedIds = new Set([id, ...state.pins]);
        if (graph.groups.has(id)) starts.forEach(member => protectedIds.add(member));
        removeProjected(graph, state, [...reached].filter(node => !protectedIds.has(node)));
    } else throw new Error('Unknown visibility action');
    return state;
}

/** Markers only restore this entry's hidden selection; exploration is a separate operation. */
export function hiddenMarkers(graph, state) {
    if (!state.hidden.size) return [];
    const markers = [];
    const full = copyView(state);
    full.hidden.clear();
    for (const id of state.hidden) if (!graph.groups.has(id) && (!graph.nodes.get(id).reaction
        || graph.nodes.get(id).reaction.status !== 'SUPERSEDED' || state.revealed.has(id))) full.visible.add(id);
    const displayed = topology(graph, state);
    const hiddenByAnchor = new Map();
    for (const {data} of project(graph, full).elements) {
        if (!data.source) continue;
        for (const [anchor, target, direction] of [[data.source, data.target, 'outgoing'], [data.target, data.source, 'incoming']]) {
            if (!displayed.has(anchor) || displayed.has(target) || !state.hidden.has(target)) continue;
            const key = JSON.stringify([anchor, direction]);
            if (!hiddenByAnchor.has(key)) hiddenByAnchor.set(key, {anchor, direction, ids: new Set()});
            hiddenByAnchor.get(key).ids.add(target);
        }
    }
    for (const [key, value] of hiddenByAnchor) markers.push({data: {id: 'restore:' + key, kind: 'marker',
        anchor: value.anchor, direction: value.direction, label: String(value.ids.size), restore: [...value.ids]}});
    return markers;
}
