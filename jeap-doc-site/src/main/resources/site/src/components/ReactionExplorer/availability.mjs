const storagePrefix = 'jeap.reaction-explorer.v1:';
const availableLifetime = 10 * 60 * 1000;
const unavailableLifetime = 60 * 1000;

/** Share checks in memory and across full-page navigation between independently built system parts. */
export function createExplorerAvailability({fetcher = fetch, storage = () => globalThis.sessionStorage,
    now = () => Date.now()} = {}) {
    const entries = new Map();
    return (url, href, site, environment) => {
        const key = storagePrefix + JSON.stringify([url, href, site, environment]);
        const existing = entries.get(key);
        if (existing?.request) return existing.request;
        if (existing?.expires > now()) return Promise.resolve(existing.available);
        try {
            const saved = JSON.parse(storage()?.getItem(key) ?? 'null');
            const lifetime = saved?.available ? availableLifetime : unavailableLifetime;
            if (typeof saved?.available === 'boolean' && Number.isFinite(saved.expires)
                && saved.expires > now() && saved.expires <= now() + lifetime) {
                entries.set(key, saved);
                return Promise.resolve(saved.available);
            }
        } catch { /* Storage may be blocked or contain an obsolete value; use the normal check. */ }

        const request = Promise.resolve().then(() => fetcher(url, {redirect: 'error'}))
            .then(response => response.ok ? response.json() : null)
            .then(async status => {
                if (status?.formatVersion !== 1 || status.site !== site
                    || status.environment !== environment || status.available !== true) return false;
                // A differently configured system part may still carry another Explorer path.
                const route = await fetcher(href, {method: 'HEAD', redirect: 'error'});
                return route.ok && route.headers.get('content-type')?.includes('text/html') === true;
            })
            .catch(() => false)
            .then(available => {
                const saved = {available, expires: now() + (available ? availableLifetime : unavailableLifetime)};
                entries.set(key, saved);
                try { storage()?.setItem(key, JSON.stringify(saved)); } catch { /* Keep the in-memory result. */ }
                return available;
            });
        entries.set(key, {request});
        return request;
    };
}

/** The marker and route must belong to the published shell, not a system part's build-time configuration. */
export const publishedExplorerAvailable = createExplorerAvailability();
