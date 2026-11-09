// The canvas and legend share their shapes and colours in both colour modes.
export function nodeAppearance(dark) {
    const neutral = {'background-color': dark ? '#25354d' : '#edf3fa', 'border-color': dark ? '#8296af' : '#8c9eb5'};
    return {
        message: {shape: 'polygon', 'shape-polygon-points': '-1 0 -0.88 -1 0.88 -1 1 0 0.88 1 -0.88 1',
            width: 190, 'background-color': dark ? '#193e57' : '#e6f4fc', 'border-color': neutral['border-color']},
        http: {shape: 'round-rectangle', 'corner-radius': 16, 'background-color': dark ? '#343258' : '#f0edfc', 'border-color': neutral['border-color']},
        interface: {shape: 'ellipse', ...neutral},
        reaction: {shape: 'rectangle', 'background-color': dark ? '#4c4024' : '#fff3d6', 'border-color': '#b69045'},
        group: {shape: 'round-rectangle', ...neutral, 'background-opacity': 0.22, 'border-style': 'dashed'},
        marker: {shape: 'ellipse', ...neutral, 'background-color': dark ? '#425773' : '#dce7f3'},
    };
}

export const foreignAppearance = {'border-color': '#4a90d9', 'border-width': 3};

// A separate badge keeps ownership borders visible, including on pinned foreign nodes.
export const pinPath = 'M9 3h6 M10 3v6l-4 4v2h12v-2l-4-4V3 M12 15v6';
export const pinBadgeColor = '#c43d4f';
export const pinBadgeImage = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><circle cx="12" cy="12" r="11" fill="${pinBadgeColor}" stroke="white"/><path d="${pinPath}" transform="translate(3 3) scale(.75)" fill="none" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`);
export const pinnedAppearance = {
    // Include the overhanging badge in canvas texture bounds, not only in the live draw.
    'bounds-expansion': 14,
    'background-image': pinBadgeImage, 'background-width': 24, 'background-height': 24,
    'background-position-x': '100%', 'background-position-y': '0%',
    'background-offset-x': 12, 'background-offset-y': -12,
    'background-clip': 'none', 'background-image-containment': 'over', 'background-image-opacity': 1,
};
