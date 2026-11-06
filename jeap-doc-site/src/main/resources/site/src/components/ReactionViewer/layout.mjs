/** Reorder siblings only when a swap removes more edge crossings than it introduces. */
export function orderGroupChildren(children, parents, elements, positions) {
    const neighbours = new Map();
    const connect = (child, other) => {
        const parent = parents.get(child);
        if (!parent) return;
        const origin = positions.get(parent), target = positions.get(parents.get(other) ?? other);
        if (!origin || !target || target.x === origin.x) return;
        if (!neighbours.has(child)) neighbours.set(child, {left: [], right: []});
        neighbours.get(child)[target.x < origin.x ? 'left' : 'right'].push(target.y);
    };
    for (const {data} of elements) {
        if (!data.source) continue;
        connect(data.source, data.target);
        connect(data.target, data.source);
    }
    const crossings = (upper, lower) => {
        const a = neighbours.get(upper), b = neighbours.get(lower);
        if (!a || !b) return 0;
        let count = 0;
        for (const side of ['left', 'right']) {
            for (const y of a[side]) for (const otherY of b[side]) if (y > otherY) count++;
        }
        return count;
    };
    return new Map([...children].map(([group, members]) => {
        const ordered = [...members];
        // Each swap strictly reduces crossings; ties retain the existing order.
        let changed;
        do {
            changed = false;
            for (let index = 0; index + 1 < ordered.length; index++) {
                const a = ordered[index], b = ordered[index + 1];
                if (crossings(a, b) > crossings(b, a)) {
                    [ordered[index], ordered[index + 1]] = [b, a];
                    changed = true;
                }
            }
        } while (changed);
        return [group, ordered];
    }));
}
