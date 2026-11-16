import {interfaceLabel} from './exploration.mjs';

const indexes = new WeakMap();
const fold = value => String(value ?? '').trim().toLowerCase();

/** One catalogue per immutable snapshot; scope is an explicit choice of the caller. */
export function entryCandidates(graph, {scope, visibleIds} = {}) {
    if (!['graph', 'selection'].includes(scope) || scope === 'selection' && !(visibleIds instanceof Set)) {
        throw new Error('Choose graph or selection search scope');
    }
    if (!indexes.has(graph)) {
        const entries = [], components = new Map();
        for (const node of graph.nodes.values()) {
            if (node.reaction) continue;
            const {message, http, interfaceType, fqn} = node.interfaceDetails ?? {};
            const terms = message ? [message.messageType, message.variant, `${message.messageType} ${message.variant ?? ''}`]
                : http ? [http.provider, http.method, http.path, `${http.method} ${http.path}`]
                    : [interfaceType, fqn];
            entries.push({id: node.id, label: interfaceLabel(graph, node.id), terms: terms.filter(Boolean),
                entry: {kind: 'INTERFACE', id: node.id}, members: [node.id]});
        }
        for (const group of graph.groups.values()) {
            const {system, component} = group.owner;
            // A component name alone cannot establish shared ownership. Its interfaces remain searchable.
            if (!fold(system) || !fold(component)) continue;
            const key = JSON.stringify([fold(system), fold(component)]);
            if (!components.has(key)) components.set(key, {id: `component:${key}`,
                label: `${component} — Component (${system})`, terms: [component, system].filter(Boolean),
                entry: {kind: 'COMPONENT', system, component}, members: []});
            components.get(key).members.push(group.id, ...group.members);
        }
        indexes.set(graph, [...entries, ...components.values()]);
    }
    const entries = indexes.get(graph);
    return scope === 'graph' ? entries : entries.filter(entry => entry.members.some(id => visibleIds.has(id)));
}

/** Exact names, prefixes, then substrings; no cap silently drops reachable results. */
export function matchingEntries(candidates, query) {
    const term = fold(query);
    if (!term) return [];
    const rank = entry => Math.min(...[entry.label, ...(entry.terms ?? [])].map(value => {
        const text = fold(value);
        return text === term ? 0 : text.startsWith(term) ? 1 : text.includes(term) ? 2 : 3;
    }));
    return candidates.map(entry => ({entry, rank: rank(entry)})).filter(hit => hit.rank < 3)
        .sort((a, b) => a.rank - b.rank || a.entry.label.localeCompare(b.entry.label) || a.entry.id.localeCompare(b.entry.id))
        .map(hit => hit.entry);
}
