// Identity namespaces are independent of how an interface is drawn. Never coerce keys to numbers.
export const identity = value => JSON.stringify([value.nodeType, value.key]);
const fold = value => String(value ?? '').toLowerCase();
export const widthOf = median => Math.min(6, 1 + Math.log10(1 + median));

export function readGraph(payload, site, environment) {
    if (payload.formatVersion !== 1 || payload.site !== site || payload.environment !== environment
        || !payload.available || !Array.isArray(payload.graph?.nodes) || !Array.isArray(payload.graph?.edges)) {
        throw new Error('Invalid reaction snapshot');
    }
    const nodes = new Map();
    for (const node of payload.graph.nodes) {
        if (typeof node.identity?.key !== 'string' || typeof node.identity?.nodeType !== 'string') throw new Error('Invalid identity');
        if (typeof node.nodeType !== 'string' || (node.nodeType === 'REACTION') !== Boolean(node.reaction)) throw new Error('Invalid node kind');
        const details = node.interfaceDetails;
        const labels = node.reaction ? [node.reaction.component, node.reaction.system, node.reaction.groupKey]
            : [details?.interfaceType, details?.fqn, details?.message?.messageType, details?.message?.variant,
                details?.http?.provider, details?.http?.method, details?.http?.path];
        if (labels.some(label => label != null && typeof label !== 'string')) throw new Error('Invalid label');
        const id = identity(node.identity);
        if (nodes.has(id)) throw new Error('Duplicate identity');
        if (node.nodeType === 'REACTION' && (typeof node.reaction?.median !== 'number'
            || !Number.isFinite(node.reaction.median) || node.reaction.median < 0)) throw new Error('Invalid median');
        nodes.set(id, {...node, id});
    }
    const edges = payload.graph.edges.map(edge => {
        const source = identity(edge.source), target = identity(edge.target);
        if (!nodes.has(source) || !nodes.has(target)) throw new Error('Missing endpoint');
        if (edge.edgeType === 'TRIGGER' && (nodes.get(source).reaction || !nodes.get(target).reaction)
            || edge.edgeType === 'ACTION' && (!nodes.get(source).reaction || nodes.get(target).reaction)) throw new Error('Invalid flow');
        return {...edge, source, target};
    });
    const owners = new Map((payload.ownership ?? []).map(owner => [identity(owner.reaction), owner]));
    const triggers = new Map();
    for (const edge of edges.filter(e => e.edgeType === 'TRIGGER')) {
        if (triggers.has(edge.target) && triggers.get(edge.target) !== edge.source) throw new Error('Multiple triggers');
        triggers.set(edge.target, edge.source);
    }
    const adjacent = new Map();
    for (const edge of edges) {
        const interfaceId = edge.edgeType === 'TRIGGER' ? edge.source : edge.edgeType === 'ACTION' ? edge.target : null;
        if (!interfaceId) continue;
        if (!adjacent.has(interfaceId)) adjacent.set(interfaceId, new Set());
        adjacent.get(interfaceId).add(edge.edgeType === 'TRIGGER' ? edge.target : edge.source);
    }
    const groups = new Map(), groupOf = new Map();
    for (const node of nodes.values()) {
        if (!node.reaction) continue;
        const owner = owners.get(node.id) ?? node.reaction;
        const trigger = triggers.get(node.id);
        // Unknown ownership cannot merge unrelated reactions; triggerless reactions always stand alone.
        const key = !trigger || !owner.system || !owner.component ? ['single', node.id]
            : [owner.system, owner.component, node.reaction.groupKey ?? trigger];
        const id = 'group:' + JSON.stringify(key);
        if (!groups.has(id)) groups.set(id, {id, owner, trigger, members: []});
        groups.get(id).members.push(node.id);
        groupOf.set(node.id, id);
    }
    const messageSystems = new Map();
    for (const owner of payload.messageOwnership ?? []) {
        if (typeof owner.message !== 'string' || typeof owner.system !== 'string') throw new Error('Invalid message ownership');
        const name = fold(owner.message);
        if (messageSystems.has(name) && fold(messageSystems.get(name)) !== fold(owner.system)) throw new Error('Ambiguous message ownership');
        messageSystems.set(name, owner.system);
    }
    return {nodes, edges, groups, groupOf, adjacent, messageSystems, ownershipComplete: payload.ownershipComplete};
}

export function entryView(graph, entry) {
    const selected = new Set(), visible = new Set(), pins = new Set(), hiddenSuperseded = new Set();
    const active = id => graph.nodes.get(id).reaction?.status !== 'SUPERSEDED';
    if (entry.kind === 'MESSAGE') {
        for (const node of graph.nodes.values()) {
            const message = node.interfaceDetails?.message;
            if (message && fold(message.messageType) === fold(entry.message) && (message.variant ?? '') === entry.variant) {
                visible.add(node.id); pins.add(node.id);
            }
        }
        for (const edge of graph.edges) {
            const reaction = edge.edgeType === 'TRIGGER' && pins.has(edge.source) ? edge.target
                : edge.edgeType === 'ACTION' && pins.has(edge.target) ? edge.source : null;
            if (reaction && active(reaction)) { selected.add(graph.groupOf.get(reaction)); visible.add(reaction); }
            else if (reaction) hiddenSuperseded.add(reaction);
        }
    } else {
        for (const group of graph.groups.values()) {
            if (fold(group.owner.system) === fold(entry.system)
                && (entry.kind === 'SYSTEM' || fold(group.owner.component) === fold(entry.component))) selected.add(group.id);
        }
    }
    const focusedReactions = entry.kind === 'MESSAGE' ? new Set([...visible].filter(id => graph.nodes.get(id).reaction)) : null;
    // Every selected group supplies its adjacent interfaces, including hidden members' actions.
    for (const edge of focusedReactions ? [] : graph.edges) {
        if (edge.edgeType === 'TRIGGER' && selected.has(graph.groupOf.get(edge.target)) && active(edge.target)) visible.add(edge.source);
        if (edge.edgeType === 'ACTION' && selected.has(graph.groupOf.get(edge.source)) && active(edge.source)) visible.add(edge.target);
    }
    const system = entry.kind === 'MESSAGE' ? graph.messageSystems.get(fold(entry.message)) : entry.system;
    return {selected, visible, pins, hiddenSuperseded, system, focusedReactions};
}

/** Count the entry's reactions independently of group expansion, excluding superseded history. */
export function focusedReactionCount(graph, state) {
    const reactions = new Set();
    if (state.pins.size) {
        for (const id of state.pins) {
            for (const reaction of graph.adjacent.get(id) ?? []) reactions.add(reaction);
        }
    } else {
        for (const id of state.selected) {
            for (const reaction of graph.groups.get(id).members) reactions.add(reaction);
        }
    }
    return [...reactions].filter(id => graph.nodes.get(id).reaction.status !== 'SUPERSEDED').length;
}

/** The table follows individual visible reactions, never bundles, markers or predecessor history. */
export function visibleReactionRows(graph, state) {
    const rows = new Map();
    for (const id of state.visible) {
        const reaction = graph.nodes.get(id).reaction;
        if (reaction && reaction.status !== 'SUPERSEDED' && state.selected.has(graph.groupOf.get(id))) {
            rows.set(id, {id, component: reaction.component, trigger: null, answers: new Set(), median: reaction.median});
        }
    }
    for (const edge of graph.edges) {
        if (state.focusedReactions && !state.pins.has(edge.source) && !state.pins.has(edge.target)) continue;
        if (edge.edgeType === 'TRIGGER' && rows.has(edge.target)) {
            rows.get(edge.target).trigger = tableInterfaceLabel(graph.nodes.get(edge.source));
        } else if (edge.edgeType === 'ACTION' && rows.has(edge.source)) {
            rows.get(edge.source).answers.add(tableInterfaceLabel(graph.nodes.get(edge.target)));
        }
    }
    const compare = (left, right) => left < right ? -1 : left > right ? 1 : 0;
    return [...rows.values()].map(row => ({...row, answers: [...row.answers].sort()}))
        .sort((left, right) => compare(left.component, right.component)
            || compare(left.trigger ?? '', right.trigger ?? '') || compare(left.id, right.id));
}

function tableInterfaceLabel(node) {
    const message = node.interfaceDetails?.message;
    if (message) return message.messageType + (message.variant ? ` [${message.variant}]` : '');
    return labelOf(node).replaceAll('\n', ' ');
}

/** Expand only the selected groups; never pull foreign or superseded reactions into an entry view. */
export function expandGroups(graph, state) {
    const visible = new Set(state.visible);
    for (const id of state.selected) {
        for (const member of membersOf(graph, state, id)) {
            if (graph.nodes.get(member).reaction.status !== 'SUPERSEDED') visible.add(member);
        }
    }
    return {...state, visible};
}

/** Change one group without changing other groups, interfaces, pins or the original graph. */
export function setGroupExpanded(graph, state, groupId, expanded) {
    if (!state.selected.has(groupId)) throw new Error('Group outside this view');
    const visible = new Set(state.visible);
    for (const member of membersOf(graph, state, groupId)) {
        if (expanded && graph.nodes.get(member).reaction.status !== 'SUPERSEDED') visible.add(member);
        else if (!expanded && !state.pins.has(member)) visible.delete(member);
    }
    return {...state, visible};
}

function membersOf(graph, state, groupId) {
    return graph.groups.get(groupId).members.filter(id => !state.focusedReactions || state.focusedReactions.has(id));
}

export function labelOf(node) {
    const details = node.interfaceDetails;
    if (node.reaction) return node.reaction.component + '\n' + node.identity.key;
    if (details?.message) return details.message.messageType + (details.message.variant ? '\n' + details.message.variant : '');
    if (details?.http) return `${details.http.provider}\n${details.http.method} ${details.http.path}`;
    return `${details?.interfaceType ?? node.nodeType}\n${details?.fqn ?? node.identity.key}`;
}

// Match ReactionView's diagram labels; full names remain available when selecting a node.
function withoutSuffix(name, suffixes) {
    const suffix = suffixes.find(suffix => name.length > suffix.length && name.endsWith(suffix));
    return suffix ? name.slice(0, -suffix.length) : name;
}

function diagramLabelOf(node) {
    if (node.reaction) return String(node.reaction.median);
    const message = node.interfaceDetails?.message;
    if (message) return withoutSuffix(message.messageType ?? node.identity.key, ['Event', 'Command'])
        + (message.variant ? `\n[${message.variant}]` : '');
    return labelOf(node);
}

export function project(graph, state) {
    const elements = [], bundles = new Map();
    const foreign = system => Boolean(state.system && system && fold(system) !== fold(state.system));
    const superseded = new Set(state.hiddenSuperseded);
    for (const id of state.selected) {
        const group = graph.groups.get(id);
        const members = membersOf(graph, state, id);
        const active = members.filter(member => graph.nodes.get(member).reaction.status !== 'SUPERSEDED');
        members.filter(member => graph.nodes.get(member).reaction.status === 'SUPERSEDED').forEach(member => superseded.add(member));
        if (!active.length) continue;
        elements.push({data: {id, kind: 'group', label: `${group.owner.system ?? '?'} / ${group.owner.component ?? '?'}`,
            displayLabel: withoutSuffix(group.owner.component ?? '?', ['-service', '-scs']), foreign: foreign(group.owner.system)}});
        const hidden = active.filter(member => !state.visible.has(member)).length;
        if (hidden) elements.push({data: {id: 'hidden:' + id, parent: id, kind: 'marker', label: String(hidden)}});
    }
    for (const id of state.visible) {
        const node = graph.nodes.get(id);
        const system = node.reaction ? graph.groups.get(graph.groupOf.get(id))?.owner.system
            : graph.messageSystems.get(fold(node.interfaceDetails?.message?.messageType));
        elements.push({data: {id, parent: graph.groupOf.get(id), label: labelOf(node), displayLabel: diagramLabelOf(node),
            foreign: foreign(system), system,
            tooltip: node.reaction ? `Median per day: ${node.reaction.median}` : undefined,
            kind: node.reaction ? 'reaction' : node.nodeType === 'MESSAGE' ? 'message'
                : node.nodeType === 'HTTP_OPERATION' ? 'http' : 'interface', pinned: state.pins.has(id)}});
    }
    const add = (source, target, reaction) => {
        const key = JSON.stringify([source, target]);
        if (!bundles.has(key)) bundles.set(key, {source, target, contributions: new Map()});
        bundles.get(key).contributions.set(reaction.id, reaction.reaction.median);
    };
    for (const edge of graph.edges) {
        const reactionId = edge.edgeType === 'TRIGGER' ? edge.target : edge.edgeType === 'ACTION' ? edge.source : null;
        if (!reactionId || !state.selected.has(graph.groupOf.get(reactionId))) continue;
        if (state.focusedReactions && (!state.focusedReactions.has(reactionId)
            || !state.pins.has(edge.source) && !state.pins.has(edge.target))) continue;
        const reaction = graph.nodes.get(reactionId);
        if (reaction.reaction.status === 'SUPERSEDED') continue;
        const group = graph.groupOf.get(reactionId);
        if (edge.edgeType === 'TRIGGER' && state.visible.has(edge.source)) add(edge.source, state.visible.has(reactionId) ? reactionId : group, reaction);
        if (edge.edgeType === 'ACTION' && state.visible.has(edge.target)) add(state.visible.has(reactionId) ? reactionId : group, edge.target, reaction);
    }
    for (const [id, bundle] of bundles) {
        const values = [...bundle.contributions.values()];
        const sum = values.reduce((a, b) => a + b, 0);
        if (!Number.isFinite(sum)) throw new Error('Invalid frequency sum');
        const tooltip = values.length === 1 ? `Median per day: ${sum}`
            : `Sum of individual medians – approximation: ${sum}\n` + [...bundle.contributions].map(([key, value]) => `${graph.nodes.get(key).identity.key}: ${value}`).join('\n');
        elements.push({data: {id: 'edge:' + id, source: bundle.source, target: bundle.target, width: widthOf(sum), tooltip}});
    }
    // Counts are scoped to the displayed interfaces, not to every unrelated node in the environment.
    for (const id of state.visible) {
        if (graph.nodes.get(id).reaction) continue;
        const hidden = new Set();
        for (const other of graph.adjacent.get(id) ?? []) {
            if (!state.selected.has(graph.groupOf.get(other))) hidden.add(other);
        }
        if (hidden.size) elements.push({data: {id: 'boundary:' + id, kind: 'marker', label: String(hidden.size), anchor: id}});
    }
    return {elements, superseded: superseded.size};
}
