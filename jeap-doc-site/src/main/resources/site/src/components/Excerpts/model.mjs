/**
 * What the excerpts on a system's and a component's page show, without React and without the browser - so that
 * every rule of the presentation is a plain function a test can call.
 *
 * The excerpts are read live from the doc service's UI API (`/ui-api`), never written into the page: the page
 * carries only the placeholder the generator wrote, `jeap-excerpts`, with the names the architecture model has.
 */

/** How long a page waits for an excerpt before it gives up and shows nothing. */
export const TIMEOUT_MS = 5000;

/** How long a shortened comment may be before it is cut. */
export const COMMENT_LIMIT = 200;

/**
 * The placeholder's JSON, or null where it is not one: a page shows nothing rather than a broken excerpt.
 */
export function readEntry(text) {
    try {
        const entry = JSON.parse(String(text).trim());
        const valid = entry && (entry.page === 'system' || entry.page === 'component')
            && typeof entry.system === 'string' && entry.system !== ''
            && Array.isArray(entry.components) && entry.components.every(name => typeof name === 'string');
        return valid ? entry : null;
    } catch {
        return null;
    }
}

/**
 * The context path of the service, from the base URL of the site the page belongs to: the default site is served
 * at the context root, every other one below `/site/<id>/`.
 */
export function contextPathOf(baseUrl, site) {
    const base = String(baseUrl ?? '/').replace(/\/$/, '');
    const suffix = `/site/${site}`;
    if (site && site !== 'default' && base.endsWith(suffix)) {
        return base.slice(0, -suffix.length);
    }
    return base;
}

/** Where the governance excerpt of a system is read. */
export function governanceUrl(contextPath, system) {
    return `${contextPath}/ui-api/excerpts/governance/systems/${encodeURIComponent(system)}`;
}

/** The governance site's markers: green from 80 percent, yellow from 50, red below. */
export function scoreMarker(score) {
    if (typeof score !== 'number') return '';
    if (score >= 80) return '🟢';
    if (score >= 50) return '🟡';
    return '🔴';
}

/** An arrow and a word, as on the governance site; nothing where there is no trend yet. */
export function trendLabel(trend) {
    switch (trend) {
        case 'UP': return {arrow: '↑', text: 'up'};
        case 'DOWN': return {arrow: '↓', text: 'down'};
        case 'STABLE': return {arrow: '→', text: 'stable'};
        case null:
        case undefined:
        case 'NO_DATA':
        case 'UNKNOWN':
            return null;
        default: return {arrow: '', text: String(trend).toLowerCase()};
    }
}

/**
 * The comment and its first detail, joined by `: `, cut at {@link COMMENT_LIMIT} characters with `…`; the further
 * details are counted as `+ n more`. The full list is on the governance site.
 */
export function shortenComment(comment, details = []) {
    const parts = [comment, details[0]].filter(part => typeof part === 'string' && part !== '');
    let text = parts.join(': ');
    if (text.length > COMMENT_LIMIT) {
        text = text.slice(0, COMMENT_LIMIT).trimEnd() + '…';
    }
    const more = Math.max(0, details.length - 1);
    return {text, more: more > 0 ? `+ ${more} more` : ''};
}

const ISO = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/;

/**
 * `dd.MM.yyyy HH:mm` in the offset the service delivered - not converted to the browser's time zone, so that the
 * excerpts read like the generated pages.
 */
export function formatDateTime(iso) {
    const match = ISO.exec(iso ?? '');
    if (!match || match[4] === undefined) return '';
    return `${match[3]}.${match[2]}.${match[1]} ${match[4]}:${match[5]}`;
}

/** `dd.MM.yyyy`, read off the delivered value as {@link formatDateTime} does. */
export function formatDate(iso) {
    const match = ISO.exec(iso ?? '');
    return match ? `${match[3]}.${match[2]}.${match[1]}` : '';
}

/**
 * The entry the answer has for a component of the model: the one of that name, else the one whose name differs
 * in case only - the rule the service matches the system by.
 */
function entryOf(components, name) {
    const lower = String(name).toLowerCase();
    return components.find(component => component.name === name)
        ?? components.find(component => component.name?.toLowerCase() === lower)
        ?? null;
}

/**
 * The rows of the system page: the components of the placeholder the answer knows, lowest score first, then by
 * name. A component without a score sorts last. Empty where the answer knows none of them.
 */
export function systemRows(excerpt, componentNames) {
    const components = excerpt?.components ?? [];
    const known = new Set(componentNames.map(name => entryOf(components, name)).filter(Boolean));
    return [...known]
        .sort((a, b) => {
            const sa = typeof a.score === 'number' ? a.score : Number.POSITIVE_INFINITY;
            const sb = typeof b.score === 'number' ? b.score : Number.POSITIVE_INFINITY;
            return sa - sb || a.name.localeCompare(b.name);
        });
}

/** The entry of the one component a component page is about, or null where the answer does not know it. */
export function componentEntry(excerpt, componentName) {
    return entryOf(excerpt?.components ?? [], componentName);
}

/**
 * The line under the component page's table: `n further rules compliant` - without `further` where nothing fails -
 * and `, m paused or disabled` when m is above 0.
 */
export function complianceLine(component) {
    const failing = component.failingRules?.length ?? 0;
    const compliant = component.compliant ?? 0;
    const noun = compliant === 1 ? 'rule' : 'rules';
    let line = `${compliant} ${failing > 0 ? 'further ' : ''}${noun} compliant`;
    if ((component.pausedOrDisabled ?? 0) > 0) {
        line += `, ${component.pausedOrDisabled} paused or disabled`;
    }
    return line + '.';
}

/**
 * The points of the history line, in a box of the given size, without axes: the oldest day on the left, the
 * lowest score of the period at the bottom and the highest at the top, so that a change of a few points shows. A
 * flat history is a line through the middle. Empty with fewer than two days.
 */
export function sparkline(history, width, height) {
    const days = (history ?? []).filter(day => typeof day.score === 'number');
    if (days.length < 2) return '';
    const scores = days.map(day => day.score);
    const low = Math.min(...scores);
    const high = Math.max(...scores);
    const step = width / (days.length - 1);
    const round = value => Math.round(value * 10) / 10;
    return days.map((day, index) => {
        const y = high === low ? height / 2 : height - ((day.score - low) / (high - low)) * height;
        return `${round(index * step)},${round(y)}`;
    }).join(' ');
}
