import {entryView, expandGroups} from './model.mjs';
import {changeView} from './exploration.mjs';

export const HISTORY_LIMIT = 100;
const sets = ['selected', 'reactions', 'edges', 'visible', 'pins', 'hidden', 'revealed'];

export function sameView(left, right) {
    return JSON.stringify(left.entry) === JSON.stringify(right.entry) && left.explored === right.explored
        && sets.every(key => left[key].size === right[key].size && [...left[key]].every(id => right[key].has(id)));
}

export function initialView(graph, entry) {
    const state = entryView(graph, entry);
    return entry.kind === 'COMPONENT' ? state : expandGroups(graph, state);
}

export function startHistory(graph, entry) {
    const state = initialView(graph, entry);
    return {state, baseline: state, entry, steps: [], history: []};
}

/** Search selection establishes a new reset baseline; focus keeps the existing one. */
export function advanceHistory(graph, session, action, presentation) {
    const baseline = action.type === 'entry' ? initialView(graph, action.entry) : session.baseline;
    const state = action.type === 'reset' ? baseline : action.type === 'entry' ? baseline
        : changeView(graph, session.state, action);
    if (sameView(state, session.state) && sameView(baseline, session.baseline)) return session;
    const {history, ...before} = session;
    delete before.presentation;
    const checkpoint = presentation ? {...before, presentation} : before;
    return {...before, state, baseline, steps: [...session.steps, action],
        history: [...history.slice(-(HISTORY_LIMIT - 1)), checkpoint]};
}

export function backHistory(session) {
    if (!session.history.length) return session;
    return {...session.history.at(-1), history: session.history.slice(0, -1)};
}
