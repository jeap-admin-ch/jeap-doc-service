/** Keep the action target and the complete addition in view with minimal panning. */
export function expansionViewport({bounds, width, height, zoom, pan, anchor}) {
    const margin = 32;
    const fit = Math.min(Math.max(1, width - 2 * margin) / Math.max(1, bounds.x2 - bounds.x1),
        Math.max(1, height - 2 * margin) / Math.max(1, bounds.y2 - bounds.y1));
    const nextZoom = Math.min(zoom, fit);
    const desired = {x: pan.x + anchor.x * (zoom - nextZoom), y: pan.y + anchor.y * (zoom - nextZoom)};
    const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
    return {zoom: nextZoom, pan: {
        x: clamp(desired.x, margin - bounds.x1 * nextZoom, width - margin - bounds.x2 * nextZoom),
        y: clamp(desired.y, margin - bounds.y1 * nextZoom, height - margin - bounds.y2 * nextZoom),
    }};
}
