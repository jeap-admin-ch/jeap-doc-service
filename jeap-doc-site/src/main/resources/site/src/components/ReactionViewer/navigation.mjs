const fold = value => String(value ?? '').toLowerCase();
const links = value => Array.isArray(value) ? value.filter(link => link && typeof link === 'object') : [];

/** Only use generated component destinations within this site's current environment. */
export function componentHref(entry, owner, environmentPrefix) {
    if (!owner?.system || !owner?.component) return null;
    if (entry.kind === 'COMPONENT' && fold(entry.system) === fold(owner.system)
        && fold(entry.component) === fold(owner.component)) return null;
    const matches = links(entry.componentLinks).filter(link => fold(link.system) === fold(owner.system)
        && fold(link.component) === fold(owner.component));
    const destinations = new Set(matches.map(link => link.href));
    return safeHref(destinations.size === 1 ? matches[0]?.href : null, environmentPrefix);
}

export function interfaceDestination(entry, node, environmentPrefix) {
    const message = node?.interfaceDetails?.message;
    const http = node?.interfaceDetails?.http;
    const matches = links(entry.interfaceLinks).filter(link => message
        ? ['EVENT', 'COMMAND'].includes(link.kind) && fold(link.name) === fold(message.messageType)
        : http && link.kind === 'REST' && fold(link.name) === fold(http.provider));
    const destinations = new Set(matches.map(link => link.href));
    const link = destinations.size === 1 ? matches[0] : null;
    return {href: safeHref(link?.href, environmentPrefix), label: http ? 'Go to REST documentation'
        : link?.kind === 'COMMAND' ? 'Go to command documentation' : 'Go to event documentation'};
}

/** Each template may offer an alternative page, but must identify an unambiguous destination itself. */
export function documentationDestinations(catalog, owner, node, environmentPrefix) {
    const field = owner ? 'componentLinks' : 'interfaceLinks';
    const templates = new Map();
    for (const link of links(catalog[field])) {
        const id = typeof link.templateId === 'string' ? link.templateId : '';
        if (!templates.has(id)) templates.set(id, []);
        templates.get(id).push(link);
    }
    const destinations = new Map();
    for (const [template, values] of [...templates].sort(([a], [b]) => a.localeCompare(b))) {
        const entry = {[field]: values};
        const destination = owner
            ? {href: componentHref(entry, owner, environmentPrefix), label: 'Go to component documentation'}
            : interfaceDestination(entry, node, environmentPrefix);
        if (destination.href && !destinations.has(destination.href)) {
            destinations.set(destination.href, {...destination, template});
        }
    }
    const result = [...destinations.values()];
    return result.map(({href, label, template}) => ({href,
        label: result.length > 1 && template ? `${label} (${template})` : label}));
}

function safeHref(href, environmentPrefix) {
    if (typeof href !== 'string' || !href.startsWith('/') || href.startsWith('//')
        || /[\\\s\x00-\x1f\x7f]/.test(href)) return null;
    try {
        const url = new URL(href, 'https://documentation.invalid');
        return url.origin === 'https://documentation.invalid' && url.pathname.startsWith(environmentPrefix + 'systems/')
            && !url.search && !url.hash ? url.pathname : null;
    } catch { return null; }
}
