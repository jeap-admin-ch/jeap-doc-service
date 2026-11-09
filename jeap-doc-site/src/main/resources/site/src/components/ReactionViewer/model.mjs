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
        return {...edge, source, target, id: JSON.stringify([edge.edgeType, source, target])};
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
        const single = !trigger || !owner.system || !owner.component;
        const key = single ? ['single', node.id]
            : [owner.system, owner.component, node.reaction.groupKey ?? trigger];
        const id = 'group:' + JSON.stringify(key);
        if (!groups.has(id)) {
            const triggerNode = nodes.get(trigger);
            const grouping = single ? {reaction: node.identity}
                : node.reaction.groupKey != null ? {groupKey: node.reaction.groupKey}
                    : {trigger: {...triggerNode.identity, label: labelOf(triggerNode)}};
            groups.set(id, {id, owner, trigger, grouping, members: []});
        }
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
    const flow = new Map(), outgoing = new Map(), incoming = new Map();
    for (const edge of edges) {
        if (!['TRIGGER', 'ACTION'].includes(edge.edgeType)) continue;
        flow.set(edge.id, edge);
        if (!outgoing.has(edge.source)) outgoing.set(edge.source, new Set());
        if (!incoming.has(edge.target)) incoming.set(edge.target, new Set());
        outgoing.get(edge.source).add(edge.id);
        incoming.get(edge.target).add(edge.id);
    }
    return {nodes, edges, flow, outgoing, incoming, groups, groupOf, adjacent, messageSystems,
        ownershipComplete: payload.ownershipComplete};
}

export function createView(entry, revealed = new Set()) {
    return {selected: new Set(), reactions: new Set(), edges: new Set(), visible: new Set(),
        pins: new Set(), hidden: new Set(), revealed: new Set(revealed),
        system: entry.system, entry, baseline: entry};
}

/** The scope is explicit: showing a group never silently selects its other members or edges. */
export function entryView(graph, entry, revealed = new Set()) {
    const state = createView(entry, revealed);
    const interfaceEntry = entry.kind === 'MESSAGE' || entry.kind === 'INTERFACE';
    if (interfaceEntry) {
        for (const node of graph.nodes.values()) {
            const message = node.interfaceDetails?.message;
            if (entry.kind === 'INTERFACE' ? node.id === entry.id && !node.reaction
                : message && fold(message.messageType) === fold(entry.message) && (message.variant ?? '') === entry.variant) {
                state.visible.add(node.id); state.pins.add(node.id);
                for (const reaction of graph.adjacent.get(node.id) ?? []) state.reactions.add(reaction);
                state.system = graph.messageSystems.get(fold(message?.messageType));
            }
        }
    } else if (entry.kind === 'REACTION') {
        if (!graph.nodes.get(entry.id)?.reaction) throw new Error('Unknown reaction');
        state.reactions.add(entry.id); state.pins.add(entry.id);
        if (graph.nodes.get(entry.id).reaction.status === 'SUPERSEDED') state.revealed.add(entry.id);
        state.system = graph.groups.get(graph.groupOf.get(entry.id)).owner.system;
    } else if (entry.kind === 'GROUP') {
        const group = graph.groups.get(entry.id);
        if (!group) throw new Error('Unknown group');
        group.members.forEach(id => state.reactions.add(id));
        state.pins.add(entry.id); state.system = group.owner.system;
    } else if (['SYSTEM', 'COMPONENT'].includes(entry.kind)) {
        for (const group of graph.groups.values()) {
            if (fold(group.owner.system) === fold(entry.system)
                && (entry.kind === 'SYSTEM' || fold(group.owner.component) === fold(entry.component))) {
                group.members.forEach(id => state.reactions.add(id));
            }
        }
    } else throw new Error('Unknown entry');
    for (const id of state.reactions) {
        state.selected.add(graph.groupOf.get(id));
        if ((interfaceEntry || entry.kind === 'REACTION') && availableReaction(graph, state, id)) state.visible.add(id);
    }
    for (const edge of graph.flow.values()) {
        const reaction = reactionOf(edge);
        if (!state.reactions.has(reaction)) continue;
        if (interfaceEntry && !state.pins.has(edge.source) && !state.pins.has(edge.target)) continue;
        state.edges.add(edge.id);
        if (!interfaceEntry && availableReaction(graph, state, reaction)) {
            state.visible.add(edge.edgeType === 'TRIGGER' ? edge.source : edge.target);
        }
    }
    return state;
}

export const reactionOf = edge => edge.edgeType === 'TRIGGER' ? edge.target : edge.source;

export function copyView(state) {
    return {...state, selected: new Set(state.selected), reactions: new Set(state.reactions), edges: new Set(state.edges),
        visible: new Set(state.visible), pins: new Set(state.pins), hidden: new Set(state.hidden), revealed: new Set(state.revealed)};
}

/** The caption counts the selection independently of hiding and group collapse. */
export function focusedReactionCount(graph, state) {
    return [...state.reactions].filter(id => graph.nodes.get(id).reaction.status !== 'SUPERSEDED'
        || state.revealed.has(id)).length;
}

/** The table follows individual visible reactions, never bundles, markers or predecessor history. */
export function visibleReactionRows(graph, state) {
    const rows = new Map();
    for (const id of state.visible) {
        const reaction = graph.nodes.get(id).reaction;
        if (reaction && state.selected.has(graph.groupOf.get(id))) {
            rows.set(id, {id, component: reaction.component, trigger: null, answers: new Set(), median: reaction.median});
        }
    }
    for (const edge of graph.flow.values()) {
        if (!state.edges.has(edge.id)) continue;
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

/** Expand the entry selection, retaining explicit visibility and status decisions. */
export function expandGroups(graph, state) {
    const visible = new Set(state.visible);
    for (const id of state.selected) {
        for (const member of membersOf(graph, state, id)) {
            if (availableReaction(graph, state, member)) visible.add(member);
        }
    }
    return {...state, visible};
}

/** Change one group without changing other groups, interfaces, pins or the original graph. */
export function setGroupExpanded(graph, state, groupId, expanded) {
    if (!state.selected.has(groupId)) throw new Error('Group outside this view');
    const visible = new Set(state.visible);
    for (const member of membersOf(graph, state, groupId)) {
        if (expanded && availableReaction(graph, state, member)) visible.add(member);
        else if (!expanded && !state.pins.has(member)) visible.delete(member);
    }
    return {...state, visible};
}

function membersOf(graph, state, groupId) {
    return graph.groups.get(groupId).members.filter(id => state.reactions.has(id));
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
    const superseded = new Set();
    for (const id of state.selected) {
        const group = graph.groups.get(id);
        const members = membersOf(graph, state, id);
        if (state.hidden.has(id)) continue;
        const active = members.filter(member => availableReaction(graph, state, member));
        members.filter(member => graph.nodes.get(member).reaction.status === 'SUPERSEDED' && !state.revealed.has(member)).forEach(member => superseded.add(member));
        if (!active.length && !state.pins.has(id)) continue;
        elements.push({data: {id, kind: 'group', pinned: state.pins.has(id), system: group.owner.system ?? null,
            component: group.owner.component ?? null, grouping: group.grouping, label: `${group.owner.system ?? '?'} / ${group.owner.component ?? '?'}`,
            displayLabel: withoutSuffix(group.owner.component ?? '?', ['-service', '-scs']), foreign: foreign(group.owner.system)}});
        const hiddenMembers = members.filter(member => !state.visible.has(member)
            && (graph.nodes.get(member).reaction.status !== 'SUPERSEDED' || state.revealed.has(member)));
        if (hiddenMembers.length) elements.push({data: {id: 'hidden:' + id, parent: id, kind: 'marker',
            label: String(hiddenMembers.length), restore: hiddenMembers}});
    }
    for (const id of state.visible) {
        const node = graph.nodes.get(id);
        const system = node.reaction ? graph.groups.get(graph.groupOf.get(id))?.owner.system
            : graph.messageSystems.get(fold(node.interfaceDetails?.message?.messageType));
        elements.push({data: {id, identity: node.identity, parent: graph.groupOf.get(id), label: labelOf(node), displayLabel: diagramLabelOf(node),
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
        if (!state.reactions.has(reactionId) || !state.edges.has(edge.id)) continue;
        const reaction = graph.nodes.get(reactionId);
        if (!availableReaction(graph, state, reactionId) || state.hidden.has(graph.groupOf.get(reactionId))) continue;
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
    // Boundary markers expand only the missing direct connections in their arrow direction.
    for (const id of state.visible) {
        for (const direction of ['incoming', 'outgoing']) {
            const hidden = new Set();
            for (const edgeId of graph[direction].get(id) ?? []) {
                const edge = graph.flow.get(edgeId), reaction = reactionOf(edge);
                const other = direction === 'outgoing' ? edge.target : edge.source;
                if (graph.nodes.get(reaction).reaction.status === 'SUPERSEDED' && !state.revealed.has(reaction)) continue;
                if (!state.edges.has(edgeId) || graph.nodes.get(other).reaction && !state.reactions.has(other)) hidden.add(other);
            }
            if (hidden.size) elements.push({data: {id: 'boundary:' + JSON.stringify([id, direction]),
                kind: 'marker', label: String(hidden.size), anchor: id, direction,
                explore: {id, direction, depth: 1}, tooltip: `Show ${hidden.size} ${direction} connections`}});
        }
    }
    for (const id of state.revealed) superseded.delete(id);
    return {elements, superseded: superseded.size, supersededIds: [...superseded]};
}

export function availableReaction(graph, state, id) {
    return !state.hidden.has(id) && (graph.nodes.get(id).reaction.status !== 'SUPERSEDED' || state.revealed.has(id));
}
