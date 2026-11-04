export function overviewSize(bounds) {
    const scale = Math.min(220 / Math.max(1, bounds.w), 160 / Math.max(1, bounds.h));
    return {width: Math.max(1, bounds.w * scale), height: Math.max(1, bounds.h * scale)};
}

export function overviewFrame(bounds, extent) {
    const clip = (value, start, size) => Math.max(0, Math.min(1, (value - start) / Math.max(1, size))) * 100;
    const left = clip(extent.x1, bounds.x1, bounds.w), top = clip(extent.y1, bounds.y1, bounds.h);
    return {left, top, width: clip(extent.x2, bounds.x1, bounds.w) - left,
        height: clip(extent.y2, bounds.y1, bounds.h) - top};
}

export function overviewPoint(bounds, x, y, width, height) {
    const fraction = (value, size) => Math.max(0, Math.min(1, value / Math.max(1, size)));
    return {x: bounds.x1 + fraction(x, width) * bounds.w, y: bounds.y1 + fraction(y, height) * bounds.h};
}
