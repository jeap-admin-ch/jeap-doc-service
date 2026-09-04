/**
 * Rewrites root-relative documentation links so they stay inside the environment the page belongs to.
 *
 * Pages are generated once per environment and their links are written environment-relative:
 *
 *     [5. Building Block View](/systems/orders/system-architecture/building-block-view/)
 *
 * In the DEV tree that link has to resolve to `/dev/systems/…`, or a reader would silently fall out of DEV and
 * into the main environment. The prefix is added at build time, once per docs plugin instance, so nothing that
 * writes a page has to know which environment it is being written into.
 */

/** A link that belongs to the documentation tree, as opposed to an external, anchor-only or relative one. */
function isEnvironmentRelative(url) {
    return typeof url === 'string' && url.startsWith('/') && !url.startsWith('//');
}

/**
 * @param {{prefix?: string}} options `/dev`, or the empty string for the environment at the site root.
 */
module.exports = function remarkEnvLinks(options = {}) {
    const prefix = options.prefix ?? '';

    return async (root) => {
        if (!prefix) {
            return; // the main environment needs no rewriting
        }
        const {visit} = await import('unist-util-visit');
        visit(root, ['link', 'definition'], (node) => {
            if (isEnvironmentRelative(node.url)) {
                node.url = `${prefix}${node.url}`;
            }
        });
    };
};
