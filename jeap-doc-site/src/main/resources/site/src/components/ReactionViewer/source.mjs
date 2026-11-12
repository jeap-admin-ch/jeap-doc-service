/** Readable JSON for the displayed projection, with local IDs instead of JSON encoded inside strings. */
export function viewSource(elements, entry, site, environment, graph) {
    const ids = new Map(), counts = new Map();
    for (const {data} of elements) {
        const kind = data.source ? 'edge' : data.kind === 'group' ? 'group' : data.kind === 'marker' ? 'marker' : 'node';
        counts.set(kind, (counts.get(kind) ?? 0) + 1);
        ids.set(data.id, `${kind}-${counts.get(kind)}`);
    }
    const reference = value => {
        if (ids.has(value)) return ids.get(value);
        const node = graph.nodes.get(value);
        if (node) return node.identity;
        const group = graph.groups.get(value);
        if (group) return {kind: 'GROUP', system: group.owner.system ?? null,
            component: group.owner.component ?? null, grouping: group.grouping};
        throw new Error('Unknown source reference');
    };
    const exported = elements.map(({data}) => {
        const {id, parent, source, target, anchor, restore, explore, ...details} = data;
        return {data: {id: reference(id), ...details,
            ...(parent ? {parent: reference(parent)} : {}),
            ...(source ? {source: reference(source), target: reference(target)} : {}),
            ...(anchor ? {anchor: reference(anchor)} : {}),
            ...(restore ? {restore: restore.map(reference)} : {}),
            ...(explore ? {explore: {...explore, id: reference(explore.id)}} : {})}};
    });
    const {kind, system, component, message, variant, id, interfaces} = entry;
    return JSON.stringify({formatVersion: 2, site, environment,
        entry: {kind, system, component, message, variant,
            ...(id ? {target: reference(id)} : {}), ...(interfaces ? {interfaces: interfaces.map(reference)} : {})},
        elements: exported}, null, 2);
}
