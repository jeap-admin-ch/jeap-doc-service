export const MAX_REPLAY_STEPS = 2048;
const actions = ['reset', 'entry', 'paths', 'show', 'expand', 'focus', 'pin', 'hide', 'outgoing', 'connected', 'collapse', 'expand-group'];
const invalid = () => { throw new Error('Invalid compact view state'); };

function fields(value, keys) {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).some(key => !keys.includes(key))) invalid();
}

/** Intern entries and targets once; each ordered step carries an opcode and reference indices. */
export function compactState(state) {
    if (state.version !== 3 || !Array.isArray(state.steps) || state.steps.length > MAX_REPLAY_STEPS) invalid();
    const references = [], indices = new Map();
    const intern = value => {
        const key = JSON.stringify(value);
        if (!indices.has(key)) { indices.set(key, references.length); references.push(value); }
        return indices.get(key);
    };
    const {site, environment, baseUrl, path} = state.context;
    const compact = {v: 4, c: [site, environment, baseUrl, path], r: references,
        w: intern(state.viewer), e: intern(state.entry), s: state.steps.map(step => {
            const opcode = actions.indexOf(step.type);
            if (opcode < 0) return invalid();
            if (step.type === 'reset') return [opcode];
            if (step.type === 'entry') return [opcode, intern(step.entry)];
            if (step.targets) return [opcode, step.targets.map(intern)];
            if (step.type === 'expand') {
                if (!['incoming', 'outgoing'].includes(step.direction)) invalid();
                return [opcode, intern(step.target), step.direction === 'incoming' ? 0 : 1, step.depth];
            }
            return [opcode, intern(step.target)];
        })};
    if (state.presentation) compact.p = [state.presentation.zoom, state.presentation.center.x, state.presentation.center.y];
    return compact;
}

/** Expand transport structure before applying the same semantic validation as older links. */
export function expandState(compact) {
    if (!compact || compact.v !== 4) return compact;
    fields(compact, ['v', 'c', 'r', 'w', 'e', 's', 'p']);
    if (!Array.isArray(compact.c) || compact.c.length !== 4 || compact.c.some(value => typeof value !== 'string')
        || !Array.isArray(compact.r) || !Array.isArray(compact.s) || compact.s.length > MAX_REPLAY_STEPS) invalid();
    const reference = index => {
        if (!Number.isInteger(index) || index < 0 || index >= compact.r.length) return invalid();
        const value = compact.r[index];
        if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid();
        return value;
    };
    const [site, environment, baseUrl, path] = compact.c;
    const state = {version: 3, context: {site, environment, baseUrl, path}, viewer: reference(compact.w),
        entry: reference(compact.e), steps: compact.s.map(step => {
            if (!Array.isArray(step) || !Number.isInteger(step[0]) || !actions[step[0]]) return invalid();
            const type = actions[step[0]];
            if (type === 'reset') { if (step.length !== 1) invalid(); return {type}; }
            if (type === 'expand') {
                if (step.length !== 4 || ![0, 1].includes(step[2])) invalid();
                return {type, target: reference(step[1]), direction: step[2] === 0 ? 'incoming' : 'outgoing', depth: step[3]};
            }
            if (step.length !== 2) invalid();
            if (type === 'entry') return {type, entry: reference(step[1])};
            if (type === 'paths' || type === 'show' && Array.isArray(step[1])) {
                if (!Array.isArray(step[1])) return invalid();
                return {type, targets: step[1].map(reference)};
            }
            return {type, target: reference(step[1])};
        })};
    if ('p' in compact) {
        if (!Array.isArray(compact.p) || compact.p.length !== 3) invalid();
        state.presentation = {zoom: compact.p[0], center: {x: compact.p[1], y: compact.p[2]}};
    }
    return state;
}
