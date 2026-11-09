import cytoscape from 'cytoscape';
import dagre from 'cytoscape-dagre';
import {orderGroupChildren} from './layout.mjs';
import {nodeAppearance, foreignAppearance, pinnedAppearance} from './appearance.mjs';
cytoscape.use(dagre);

function wrap(label, kind) {
    if (kind === 'reaction' || kind === 'marker') return String(label);
    return String(label).split('\n').map(line => {
        if (line.startsWith('[') && line.endsWith(']')) return line;
        const words = line.split(/(?<=[a-z0-9])(?=[A-Z])|(?<=-)/);
        const rows = [''];
        for (const word of words) {
            if (rows.at(-1).length && rows.at(-1).length + word.length > (kind === 'message' ? 18 : 22)) rows.push('');
            rows[rows.length - 1] += word;
        }
        return rows.join('\n');
    }).join('\n');
}

const httpHeight = node => Math.max(60, node.data('displayLabel').split('\n')
    .reduce((lines, line) => lines + Math.max(1, Math.ceil(line.length / 22)), 0) * 20 + 20);

const groupPadding = node => node.data('displayLabel').split('\n').length * 18 + 18;

export function render(container, elements, dark, onZoom, {onNodeMenu, onDismissMenu, onHover, onRestore, onExpand, viewport, actionFocus} = {}) {
    // Route between groups, then place their children inside. Dagre cannot route to compound parents.
    const parents = new Map(elements.filter(e => e.data.parent).map(e => [e.data.id, e.data.parent]));
    const children = new Map();
    for (const [child, parent] of parents) {
        if (!children.has(parent)) children.set(parent, []);
        children.get(parent).push(child);
    }
    const flat = elements.filter(e => !e.data.parent && !e.data.anchor).map(e => ({data: {...e.data,
        displayLabel: wrap(e.data.displayLabel ?? e.data.label ?? '', e.data.kind),
        ...(e.data.source ? {source: parents.get(e.data.source) ?? e.data.source,
            target: parents.get(e.data.target) ?? e.data.target} : {})}}));
    const layout = cytoscape({headless: true, styleEnabled: true, elements: flat,
        style: [{selector: 'node', style: {width: node => node.data('kind') === 'message' ? 190 : node.data('kind') === 'http' ? 200 : 280,
            height: node => node.data('kind') === 'http'
                ? httpHeight(node)
                : Math.max(120, (children.get(node.id())?.length ?? 0) * 54 + groupPadding(node) * 2)}}]});
    // Pack disconnected chains instead of letting dagre make one very tall column of unrelated nodes.
    const components = layout.elements().components().sort((a, b) => b.nodes().length - a.nodes().length);
    let x = 0, y = 0, rowHeight = 0;
    for (const component of components) {
        component.layout({name: 'dagre', rankDir: 'LR', nodeSep: 45, rankSep: 100, fit: false}).run();
        const bounds = component.boundingBox();
        if (x && x + bounds.w > 1500) { x = 0; y += rowHeight + 70; rowHeight = 0; }
        component.nodes().shift({x: x - bounds.x1, y: y - bounds.y1});
        x += bounds.w + 70;
        rowHeight = Math.max(rowHeight, bounds.h);
    }
    const orderedChildren = orderGroupChildren(children, parents, elements,
        new Map(layout.nodes().map(node => [node.id(), node.position()])));
    const positioned = elements.map(element => {
        const data = {...element.data, displayLabel: wrap(element.data.displayLabel ?? element.data.label ?? '', element.data.kind)};
        let position;
        if (!data.source) {
            const anchorParent = parents.get(data.anchor);
            position = {...layout.getElementById(data.parent ?? anchorParent ?? data.anchor ?? data.id).position()};
            if (anchorParent) position.y += (orderedChildren.get(anchorParent).indexOf(data.anchor) - (children.get(anchorParent).length - 1) / 2) * 54;
            if (data.parent) position.y += (orderedChildren.get(data.parent).indexOf(data.id) - (children.get(data.parent).length - 1) / 2) * 54;
            if (data.anchor) {
                position.x += data.direction === 'incoming' ? -118 : 118;
                position.y += data.restore ? -38 : 38;
            }
        }
        return {...element, data, position};
    });
    layout.destroy();
    const color = dark ? '#e5edf8' : '#172b4d';
    const arrow = dark ? '#9CA3AF' : '#6B7280';
    const appearance = nodeAppearance(dark);
    const cy = cytoscape({container, elements: positioned, layout: {name: 'preset', fit: false},
        minZoom: 0.03, maxZoom: 3, autoungrabify: true,
        style: [
            {selector: 'node', style: {label: 'data(displayLabel)', color, 'font-family': 'system-ui, sans-serif',
                'font-size': 15, 'text-wrap': 'wrap', 'text-max-width': 180, 'text-valign': 'center',
                'text-overflow-wrap': 'anywhere', width: 210, height: 72,
                'background-color': dark ? '#25354d' : '#edf3fa', 'border-color': dark ? '#8296af' : '#8c9eb5', 'border-width': 1.5}},
            {selector: '[kind="message"]', style: {...appearance.message, 'text-max-width': 160,
                height: node => Math.max(72, node.data('displayLabel').split('\n')
                    .reduce((lines, line) => lines + Math.max(1, Math.ceil(line.length / 18)), 0) * 20 + 24)}},
            {selector: '[kind="http"]', style: {...appearance.http, width: 200, height: httpHeight}},
            {selector: '[kind="interface"]', style: appearance.interface},
            {selector: '[kind="reaction"]', style: {...appearance.reaction, width: 130, height: 36}},
            {selector: '[kind="group"]', style: {...appearance.group, 'text-valign': 'top',
                'text-margin-y': node => groupPadding(node) - 8,
                'font-weight': 600, 'text-max-width': 210, 'min-width': 130, padding: groupPadding}},
            {selector: '[kind="marker"]', style: {...appearance.marker, width: 34, height: 34, 'font-weight': 700,
                'z-compound-depth': 'top', 'z-index': 10}},
            {selector: 'node[?pinned]', style: pinnedAppearance},
            {selector: 'node[?foreign][kind != "reaction"]', style: foreignAppearance},
            {selector: 'edge', style: {width: 'data(width)', 'line-color': arrow, 'target-arrow-color': arrow,
                'target-arrow-shape': 'triangle', 'arrow-scale': 1.15, 'curve-style': 'bezier', 'line-style': 'solid',
                // Keep left-to-right reaction arrows attached to the box edges, away from labels.
                'target-endpoint': edge => edge.target().data('kind') === 'reaction' ? '-50% 0%' : 'outside-to-node',
                'source-endpoint': edge => edge.source().data('kind') === 'reaction' ? '50% 0%' : 'outside-to-node'}},
        ]});
    let activeGroup = viewport?.group, activeNode = viewport?.node;
    const groupOf = node => node.data('kind') === 'group' ? node : node.parent();
    const openNodeMenu = (node, position) => {
        if (!node.length) return;
        const target = node.data('kind') === 'marker'
            ? (node.data('anchor') ? cy.getElementById(node.data('anchor')) : groupOf(node)) : node;
        const group = groupOf(target);
        if (!target.length || !['reaction', 'group', 'message', 'http', 'interface'].includes(target.data('kind'))) return;
        activeGroup = group.id(); activeNode = target.id();
        position ??= target.renderedPosition();
        onHover?.(null);
        onNodeMenu?.({kind: target.data('kind'), id: target.id(), group: group.id(),
            label: target.data('displayLabel').replaceAll('\n', ' '), x: position.x, y: position.y});
    };
    cy.on('cxttap', 'node', event => openNodeMenu(event.target, event.renderedPosition));
    cy.on('tapstart pan zoom', () => { onDismissMenu?.(); onHover?.(null); });
    cy.on('tap', 'node', event => {
        const node = event.target;
        if (node.data('restore')) { onRestore?.(node.data('restore'), node.data('anchor') ?? node.parent().id()); return; }
        if (node.data('explore')) { onExpand?.(node.data('explore')); return; }
        activeGroup = groupOf(node).id(); activeNode = node.id();
    });
    cy.on('mouseover mousemove', 'edge, node[kind="reaction"], node[kind="marker"][tooltip]', event => {
        onHover?.({text: event.target.data('tooltip'), x: event.renderedPosition.x, y: event.renderedPosition.y});
    });
    cy.on('mouseout', 'edge, node[kind="reaction"], node[kind="marker"][tooltip]', () => onHover?.(null));
    cy.on('zoom', () => onZoom(Math.round(cy.zoom() * 100)));
    const fit = () => { cy.resize(); cy.fit(undefined, 45); onZoom(Math.round(cy.zoom() * 100)); };
    const reset = () => {
        fit();
        if (cy.zoom() > 1) cy.zoom(1);
        cy.center();
    };
    if (viewport) { cy.zoom(viewport.zoom); cy.pan(viewport.pan); }
    else reset();
    // A semantic redraw may move nodes in model coordinates. Keep the action target at its screen position.
    if (actionFocus) {
        const target = actionFocus.candidates.map(candidate => ({...candidate, node: cy.getElementById(candidate.id)}))
            .find(candidate => candidate.node.length);
        if (target) {
            const position = target.node.position(), zoom = cy.zoom();
            const bounds = target.node.boundingBox({includeLabels: true, includeOverlays: false});
            const keepVisible = (desired, size, before, after) => {
                const low = 24 + before * zoom, high = size - 24 - after * zoom;
                return low <= high ? Math.max(low, Math.min(high, desired)) : size / 2;
            };
            cy.pan({x: keepVisible(target.point.x, cy.width(), position.x - bounds.x1, bounds.x2 - position.x) - position.x * zoom,
                y: keepVisible(target.point.y, cy.height(), position.y - bounds.y1, bounds.y2 - position.y) - position.y * zoom});
            activeNode = target.id; activeGroup = groupOf(target.node).id();
        }
    }
    const resize = new ResizeObserver(() => cy.resize());
    resize.observe(container);
    return {
        fit, reset,
        actionFocus: id => {
            const node = cy.getElementById(id);
            if (!node.length) return {candidates: [{id, point: {x: cy.width() / 2, y: cy.height() / 2}}]};
            // Hiding or collapsing the target falls back to its group, then a surviving direct neighbour.
            const candidates = node.union(node.parent()).union(node.connectedEdges().connectedNodes())
                .filter(candidate => candidate.data('kind') !== 'marker');
            return {candidates: candidates.map(candidate => ({id: candidate.id(), point: {...candidate.renderedPosition()}}))};
        },
        overview: () => ({bounds: cy.elements().boundingBox(),
            image: cy.png({full: true, maxWidth: 440, maxHeight: 320})}),
        observeViewport: listener => {
            const changed = () => listener(cy.extent());
            cy.on('pan zoom resize', changed); changed();
            return () => cy.off('pan zoom resize', changed);
        },
        centerAt: point => cy.pan({x: cy.width() / 2 - point.x * cy.zoom(), y: cy.height() / 2 - point.y * cy.zoom()}),
        viewport: () => ({zoom: cy.zoom(), pan: {...cy.pan()}, group: activeGroup, node: activeNode}),
        openNodeMenu: () => {
            const node = activeNode ? cy.getElementById(activeNode) : cy.collection();
            openNodeMenu(node.length ? node : activeGroup ? cy.getElementById(activeGroup) : cy.nodes('[kind="group"]').first());
        },
        zoom: factor => cy.zoom({level: cy.zoom() * factor, renderedPosition: {x: cy.width() / 2, y: cy.height() / 2}}),
        pan: (x, y) => cy.panBy({x, y}),
        destroy: () => { resize.disconnect(); cy.destroy(); },
    };
}
