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
