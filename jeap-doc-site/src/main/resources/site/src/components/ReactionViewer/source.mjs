/** The source of this projection, without unrelated or hidden raw graph data and link catalogues. */
export function viewSource(elements, entry, site, environment) {
    const {kind, system, component, message, variant, id, interfaces} = entry;
    return JSON.stringify({formatVersion: 1, site, environment,
        entry: {kind, system, component, message, variant, id, interfaces}, elements}, null, 2);
}
