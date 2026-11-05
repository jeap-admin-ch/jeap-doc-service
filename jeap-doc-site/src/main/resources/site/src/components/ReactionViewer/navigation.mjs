const fold = value => String(value ?? '').toLowerCase();

/** Only use generated component destinations within this site's current environment. */
export function componentHref(entry, owner, environmentPrefix) {
    if (!owner?.system || !owner?.component) return null;
    if (entry.kind === 'COMPONENT' && fold(entry.system) === fold(owner.system)
        && fold(entry.component) === fold(owner.component)) return null;
    const link = entry.componentLinks?.find(link => fold(link.system) === fold(owner.system)
        && fold(link.component) === fold(owner.component));
    return safeHref(link?.href, environmentPrefix);
}

export function interfaceDestination(entry, node, environmentPrefix) {
    const message = node?.interfaceDetails?.message;
    const http = node?.interfaceDetails?.http;
    const matches = (entry.interfaceLinks ?? []).filter(link => message
        ? ['EVENT', 'COMMAND'].includes(link.kind) && fold(link.name) === fold(message.messageType)
        : http && link.kind === 'REST' && fold(link.name) === fold(http.provider));
    const destinations = new Set(matches.map(link => link.href));
    const link = destinations.size === 1 ? matches[0] : null;
    return {href: safeHref(link?.href, environmentPrefix), label: http ? 'Go to REST documentation'
        : link?.kind === 'COMMAND' ? 'Go to command documentation' : 'Go to event documentation'};
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
