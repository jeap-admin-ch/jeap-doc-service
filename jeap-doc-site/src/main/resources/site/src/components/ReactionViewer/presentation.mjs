/** Share the model point at the canvas centre, independently of the recipient's canvas size. */
export function sharedPresentation(viewport) {
    if (!viewport) return undefined;
    return {zoom: viewport.zoom, center: {
        x: (viewport.width / 2 - viewport.pan.x) / viewport.zoom,
        y: (viewport.height / 2 - viewport.pan.y) / viewport.zoom,
    }};
}

export function validPresentation(value) {
    const coordinate = n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1e9;
    return value && typeof value === 'object' && Object.keys(value).length === 2
        && typeof value.zoom === 'number' && Number.isFinite(value.zoom) && value.zoom > 0 && value.zoom <= 3
        && value.center && typeof value.center === 'object' && Object.keys(value.center).length === 2
        && coordinate(value.center.x) && coordinate(value.center.y);
}

export function presentationPan(viewport, width, height) {
    return viewport.center ? {x: width / 2 - viewport.center.x * viewport.zoom,
        y: height / 2 - viewport.center.y * viewport.zoom} : viewport.pan;
}
