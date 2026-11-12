import {project} from './model.mjs';
import {advanceHistory, startHistory} from './history.mjs';
import {sharedPresentation, validPresentation} from './presentation.mjs';
import {decodeLinkText, encodeLinkText, MAX_LINK_LENGTH} from './linkCodec.mjs';
import {compactState, expandState, MAX_REPLAY_STEPS} from './linkState.mjs';

export {MAX_LINK_LENGTH} from './linkCodec.mjs';
export const LINK_PREFIX = '#reaction-view=';
const fail = () => { throw new Error('This view cannot be restored on the current graph.'); };
const json = value => JSON.stringify(value);
const text = value => typeof value === 'string' && value.length > 0;

// Snapshots and sessions are immutable. Weak keys release their indexes with the snapshot/view.
const graphCaches = new WeakMap(), sessionPayloads = new WeakMap();
function cacheOf(graph) {
    if (!graphCaches.has(graph)) graphCaches.set(graph, {references: new Map(), entries: new Map(), indexes: new Map()});
    return graphCaches.get(graph);
}
function uniqueIndex(graph, kind) {
    const indexes = cacheOf(graph).indexes;
    if (!indexes.has(kind)) {
        const index = new Map();
        const add = (key, id) => index.set(key, index.has(key) ? null : id);
        if (kind === 'interface') {
            for (const node of graph.nodes.values()) if (!node.reaction) add(json(interfaceIdentity(node)), node.id);
        } else if (kind === 'group') {
            for (const id of graph.groups.keys()) {
                // An unidentifiable group must not prevent sharing unrelated targets.
                try { add(json(groupReference(graph, id)), id); } catch { /* Not a shareable target. */ }
            }
        } else {
            for (const id of graph.groups.get(kind).members) {
                try { add(json(reactionReference(graph, id)), id); } catch { /* Not a shareable target. */ }
            }
        }
        indexes.set(kind, index);
    }
    return indexes.get(kind);
}

function fields(value, keys) {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).some(key => !keys.includes(key))) fail();
}

function interfaceIdentity(node) {
    if (!node || node.reaction) return null;
    const details = node.interfaceDetails ?? {}, message = details.message, http = details.http;
    if (!text(message?.messageType) && !(text(http?.provider) && text(http?.method) && text(http?.path))
        && !text(details.fqn)) return null;
    return {representation: node.nodeType, type: details.interfaceType ?? null, fqn: details.fqn ?? null,
        message: message ? [message.messageType, message.variant ?? ''] : null,
        http: http ? [http.provider, http.method, http.path] : null};
}

function interfaceIdentities(graph, edgeIds, endpoint) {
    const byIdentity = new Map();
    for (const edgeId of edgeIds ?? []) {
        const value = interfaceIdentity(graph.nodes.get(graph.flow.get(edgeId)[endpoint]));
        if (!value) fail();
        byIdentity.set(json(value), value);
    }
    return [...byIdentity].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, value]) => value);
}

function groupReference(graph, id) {
    const group = graph.groups.get(id);
    if (!group) return fail();
    const key = graph.nodes.get(group.members[0]).reaction.groupKey ?? null;
    return {kind: 'group', system: group.owner.system ?? null, component: group.owner.component ?? null,
        key, trigger: key !== null || !group.trigger ? null : interfaceIdentity(graph.nodes.get(group.trigger)) ?? fail()};
}

function reactionReference(graph, id) {
    const node = graph.nodes.get(id);
    return {kind: 'reaction', group: groupReference(graph, graph.groupOf.get(id)),
        trigger: interfaceIdentities(graph, graph.incoming.get(id), 'source'),
        actions: interfaceIdentities(graph, graph.outgoing.get(id), 'target'),
        model: node.reaction.reactionModelVersion ?? null, status: node.reaction.status};
}

/** Frequencies and mutable reaction IDs never identify a shared target. Ambiguity fails closed. */
export function referenceOf(graph, id) {
    const references = cacheOf(graph).references;
    if (references.has(id)) return references.get(id);
    const node = graph.nodes.get(id);
    const ref = graph.groups.has(id) ? groupReference(graph, id) : node?.reaction ? reactionReference(graph, id)
        : node ? {kind: 'interface', key: node.identity.key, identity: interfaceIdentity(node) ?? fail()} : fail();
    if (resolveReference(graph, ref) !== id) fail();
    references.set(id, ref);
    return ref;
}

export function resolveReference(graph, ref) {
    if (!ref || typeof ref !== 'object') return fail();
    if (ref.kind === 'interface') {
        fields(ref, ['kind', 'key', 'identity']);
        if (!text(ref.key) || !ref.identity) fail();
        return uniqueIndex(graph, 'interface').get(json(ref.identity)) ?? fail();
    }
    if (ref.kind === 'group') {
        fields(ref, ['kind', 'system', 'component', 'key', 'trigger']);
        return uniqueIndex(graph, 'group').get(json(ref)) ?? fail();
    }
    if (ref.kind === 'reaction') {
        fields(ref, ['kind', 'group', 'trigger', 'actions', 'model', 'status']);
        // Ambiguous standalone groups cannot choose a member by accident.
        const group = resolveReference(graph, ref.group);
        return uniqueIndex(graph, group).get(json(ref)) ?? fail();
    }
    return fail();
}

export function entryReference(graph, entry, reference = id => referenceOf(graph, id)) {
    if (['INTERFACE', 'REACTION', 'GROUP'].includes(entry.kind)) return {kind: entry.kind, target: reference(entry.id)};
    if (entry.kind === 'SYSTEM') return {kind: entry.kind, system: entry.system};
    if (entry.kind === 'COMPONENT') return {kind: entry.kind, system: entry.system, component: entry.component};
    if (entry.kind === 'MESSAGE') {
        const entries = cacheOf(graph).entries, key = json(entry);
        if (!entries.has(key)) entries.set(key, {kind: entry.kind, targets: [...startHistory(graph, entry).state.pins]
            .map(reference).sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0)});
        return entries.get(key);
    }
    return fail();
}

function resolveEntry(graph, entry) {
    let resolved;
    if (['INTERFACE', 'REACTION', 'GROUP'].includes(entry?.kind)) {
        fields(entry, ['kind', 'target']);
        if (entry.target?.kind !== entry.kind.toLowerCase()) fail();
        resolved = {kind: entry.kind, id: resolveReference(graph, entry.target)};
    } else if (entry?.kind === 'MESSAGE') {
        fields(entry, ['kind', 'targets']);
        if (!Array.isArray(entry.targets) || !entry.targets.length) fail();
        const messages = entry.targets.map(ref => graph.nodes.get(resolveReference(graph, ref)).interfaceDetails?.message);
        if (messages.some(message => !message)) fail();
        resolved = {kind: 'MESSAGE', message: messages[0].messageType, variant: messages[0].variant ?? ''};
        const targets = new Set(entry.targets.map(ref => resolveReference(graph, ref)));
        const expected = startHistory(graph, resolved).state.pins;
        if (targets.size !== entry.targets.length || targets.size !== expected.size || [...targets].some(id => !expected.has(id))) fail();
    } else {
        const keys = {SYSTEM: ['kind', 'system'], COMPONENT: ['kind', 'system', 'component']}[entry?.kind];
        if (!keys) return fail();
        fields(entry, keys);
        if (keys.some(key => !text(entry[key]))) fail();
        resolved = entry;
    }
    const state = startHistory(graph, resolved).state;
    if (!state.visible.size && !state.selected.size) fail();
    return resolved;
}

function encodeAction(graph, action, reference) {
    if (action.type === 'reset') return {type: 'reset'};
    if (action.type === 'entry') return {type: 'entry', entry: entryReference(graph, action.entry, reference)};
    if (action.type === 'paths' || action.type === 'show' && action.ids) {
        return {type: action.type, targets: action.ids.map(reference)};
    }
    return {type: action.type, target: reference(action.id),
        ...(action.type === 'expand' ? {direction: action.direction, depth: action.depth} : {})};
}

function decodeAction(graph, state, step) {
    if (!step || typeof step !== 'object') return fail();
    if (step.type === 'reset') { fields(step, ['type']); return {type: 'reset'}; }
    if (step.type === 'entry') { fields(step, ['type', 'entry']); return {type: 'entry', entry: resolveEntry(graph, step.entry)}; }
    if (!['paths', 'show', 'expand', 'focus', 'pin', 'hide', 'outgoing', 'connected', 'collapse', 'expand-group'].includes(step.type)) fail();
    fields(step, ['type', 'target', 'targets', ...(step.type === 'expand' ? ['direction', 'depth'] : [])]);
    if (step.type === 'paths' || step.type === 'show' && step.targets) {
        if (!Array.isArray(step.targets) || !step.targets.length || step.target) fail();
        const ids = step.targets.map(ref => resolveReference(graph, ref));
        if (new Set(ids).size !== ids.length) fail();
        if (step.type === 'show' && ids.some(id => !state.hidden.has(id))) fail();
        return {type: step.type, ids};
    }
    if (step.targets) fail();
    const id = resolveReference(graph, step.target);
    const displayed = new Set(project(graph, state).elements.filter(e => !e.data.source).map(e => e.data.id));
    if (step.type === 'show' ? !state.hidden.has(id) && !state.reactions.has(id) : !displayed.has(id)) fail();
    return {type: step.type, id, ...(step.type === 'expand' ? {direction: step.direction, depth: step.depth} : {})};
}

export async function shareLink(graph, session, context, viewer, location, viewport) {
    if (session.steps.length > MAX_REPLAY_STEPS) throw new Error('This view has too many steps to share (maximum 2,048).');
    const presentation = sharedPresentation(viewport);
    if (presentation && !validPresentation(presentation)) throw new Error('This view cannot be shared: its viewport is invalid.');
    const key = json([context, viewer]);
    let cached = sessionPayloads.get(session);
    if (!cached || cached.graph !== graph || cached.key !== key) {
        try {
            const payload = {version: 1, context, viewer: entryReference(graph, viewer),
                entry: entryReference(graph, session.entry),
                steps: session.steps.map(action => encodeAction(graph, action, id => referenceOf(graph, id)))};
            resolveEntry(graph, payload.viewer);
            resolveEntry(graph, payload.entry);
            cached = {graph, key, compact: compactState(payload)};
            sessionPayloads.set(session, cached);
        } catch {
            throw new Error('This view cannot be shared: an entry or target is not uniquely identifiable on the current graph.');
        }
    }
    const compact = {...cached.compact, ...(presentation ? {p: [presentation.zoom, presentation.center.x, presentation.center.y]} : {})};
    const encoded = await encodeLinkText(json(compact));
    const url = new URL(location);
    url.hash = LINK_PREFIX + encoded;
    if (url.href.length > MAX_LINK_LENGTH) throw new Error('This view is too large to share as a link (maximum 16,384 characters).');
    return url.href;
}

function matchesViewer(graph, linked, viewer) {
    if (linked?.kind !== viewer.kind) return false;
    if (viewer.kind === 'MESSAGE') {
        // The variant still identifies its viewer even if its interface disappeared from the snapshot.
        return Array.isArray(linked.targets) && linked.targets.length > 0 && linked.targets.every(target =>
            json(target.identity?.message) === json([viewer.message, viewer.variant ?? '']));
    }
    const semantic = value => JSON.stringify(value, function(key, item) {
        return key === 'key' && this.kind === 'interface' ? undefined : item;
    });
    return semantic(linked) === semantic(entryReference(graph, viewer));
}

/** Replay is atomic. The URL script is separate from the recipient's empty undo buffer. */
export async function restoreLink(graph, hash, context, viewer) {
    if (!hash.startsWith(LINK_PREFIX)) return null;
    let payload;
    try {
        const encoded = hash.slice(LINK_PREFIX.length);
        if (!encoded || hash.length > MAX_LINK_LENGTH) fail();
        payload = expandState(JSON.parse(await decodeLinkText(encoded)));
    } catch {
        const error = new Error('This view cannot be restored on the current graph.');
        error.unaddressed = true;
        throw error;
    }
    try {
        // Compare semantic viewer identities before resolving the entry, context or replay targets.
        // Upstream keys may change while the same message variant remains on the page.
        if (!matchesViewer(graph, payload.viewer, viewer)) return null;
        if (payload.version !== 1 || json(payload.context) !== json(context) || !Array.isArray(payload.steps)
            || payload.steps.length > MAX_REPLAY_STEPS) fail();
        if ('presentation' in payload && !validPresentation(payload.presentation)) fail();
        resolveEntry(graph, payload.viewer);
        const entry = resolveEntry(graph, payload.entry);
        let session = startHistory(graph, entry);
        for (const step of payload.steps) {
            const action = decodeAction(graph, session.state, step);
            const next = advanceHistory(graph, session, action);
            if (next === session) fail();
            session = {...next, history: []};
        }
        return {...session, history: [], ...(payload.presentation ? {presentation: payload.presentation} : {})};
    } catch { return fail(); }
}
