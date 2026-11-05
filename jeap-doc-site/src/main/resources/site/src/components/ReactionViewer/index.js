import React, {useContext, useEffect, useId, useMemo, useRef, useState} from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useColorMode} from '@docusaurus/theme-common';
import {
    CloseIcon, FitIcon, MaximizeIcon, MinimapIcon, ResetZoomIcon, SourceIcon, ZoomInIcon, ZoomOutIcon,
} from '@matfsw/docusaurus-plantuml-plugin/theme/PlantUmlDiagram/icons.js';
import {useEnvironment, routePrefixOf} from '../../data/environments';
import {readGraph, entryView, expandGroups, setGroupExpanded, project, focusedReactionCount, visibleReactionRows} from './model.mjs';
import styles from './styles.module.css';
import {SnapshotRequests} from './SnapshotProvider';
import Legend from './Legend';
import NodeMenu from './NodeMenu';
import Tooltip from './Tooltip';
import Minimap from './Minimap';
import SourceView from './SourceView';
import ReactionTable from './ReactionTable';
import {viewSource} from './source.mjs';
import {componentHref, interfaceDestination} from './navigation.mjs';

// A page with many variants fetches and validates one snapshot, with independent view states.
function load(pending, url, site, environment) {
    if (!pending.has(url)) {
        const request = fetch(url, {credentials: 'same-origin', cache: 'no-cache'}).then(response => {
            if (!response.ok) throw new Error('Graph unavailable');
            return response.json();
        }).then(payload => readGraph(payload, site, environment));
        pending.set(url, request);
        request.catch(() => pending.delete(url));
    }
    return pending.get(url);
}

export default function ReactionViewer({entry}) {
    const environment = useEnvironment();
    if (!environment) return <p role="status">Reaction graph currently unavailable</p>;
    return <EnvironmentReactionViewer entry={entry} environment={environment}/>;
}

function EnvironmentReactionViewer({entry, environment}) {
    const {siteConfig: {baseUrl, customFields: {docSite}}} = useDocusaurusContext();
    const {colorMode} = useColorMode();
    const requests = useContext(SnapshotRequests);
    const url = baseUrl + routePrefixOf(environment).replace(/^\//, '') + (environment.main ? '' : '/') + 'reaction-graph.json';
    const [snapshot, setSnapshot] = useState(null), [failed, setFailed] = useState(false);
    const [notice, setNotice] = useState('');
    const [hover, setHover] = useState(null);
    const tooltipId = useId();
    const minimapId = useId(), sourceId = useId();
    const [minimapOpen, setMinimapOpen] = useState(false), [sourceOpen, setSourceOpen] = useState(false);
    const [renderer, setRenderer] = useState(null);
    const [tableReady, setTableReady] = useState(false);
    const sourceToggle = useRef(null);
    const [collapsedGroups, setCollapsedGroups] = useState(() => new Set());
    const [menu, setMenu] = useState(null);
    useEffect(() => { setCollapsedGroups(new Set()); setMenu(null); setHover(null); setNotice('');
        setMinimapOpen(false); setSourceOpen(false); setTableReady(false); }, [entry, url]);
    const container = useRef(null), panel = useRef(null), controls = useRef(null);
    const viewport = useRef(null);
    const viewKey = JSON.stringify([url, entry]);
    const [activatedView, setActivatedView] = useState(null);
    const [zoom, setZoom] = useState(100);
    const [fullscreen, setFullscreen] = useState(false);
    useEffect(() => {
        const changed = () => { setFullscreen(document.fullscreenElement === panel.current); };
        document.addEventListener('fullscreenchange', changed);
        return () => document.removeEventListener('fullscreenchange', changed);
    }, []);
    const toggleFullscreen = async () => {
        try {
            if (document.fullscreenElement === panel.current) await document.exitFullscreen();
            else await panel.current.requestFullscreen();
        } catch { setNotice('Fullscreen is unavailable in this browser.'); }
    };
    const onKeyDown = event => {
        if (event.key === 'Escape') { setHover(null); return; }
        if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
            event.preventDefault(); controls.current?.openNodeMenu(); return;
        }
        const actions = {
            '+': () => controls.current?.zoom(1.25), '=': () => controls.current?.zoom(1.25),
            '-': () => controls.current?.zoom(0.8), '0': () => controls.current?.fit(),
            ArrowLeft: () => controls.current?.pan(60, 0), ArrowRight: () => controls.current?.pan(-60, 0),
            ArrowUp: () => controls.current?.pan(0, 60), ArrowDown: () => controls.current?.pan(0, -60),
        };
        if (actions[event.key]) { event.preventDefault(); actions[event.key](); }
    };
    useEffect(() => {
        let disposed = false;
        setSnapshot(null); setFailed(false);
        load(requests, url, docSite, environment.id).then(graph => {
            if (!disposed) setSnapshot({url, graph});
        }).catch(() => { if (!disposed) setFailed(true); });
        return () => { disposed = true; };
    }, [requests, url, docSite, environment.id]);
    const view = useMemo(() => {
        if (!snapshot || snapshot.url !== url) return null;
        try {
            let state = expandGroups(snapshot.graph, entryView(snapshot.graph, entry));
            for (const group of collapsedGroups) {
                if (state.selected.has(group)) state = setGroupExpanded(snapshot.graph, state, group, false);
            }
            return {...project(snapshot.graph, state), state, reactionCount: focusedReactionCount(snapshot.graph, state),
                rows: visibleReactionRows(snapshot.graph, state)};
        }
        catch { return {invalid: true}; }
    }, [snapshot, url, entry, collapsedGroups]);
    const source = useMemo(() => sourceOpen && view && !view.invalid
        ? viewSource(view.elements, entry, docSite, environment.id) : '', [sourceOpen, view, entry, docSite, environment.id]);
    useEffect(() => {
        if (activatedView === viewKey || !panel.current) return;
        if (typeof IntersectionObserver === 'undefined') {
            setActivatedView(viewKey);
            return;
        }
        // Variants share the snapshot, but only nearby diagrams allocate canvases and run their layout.
        const observer = new IntersectionObserver(entries => {
            if (entries.some(entry => entry.isIntersecting)) {
                setActivatedView(viewKey);
                observer.disconnect();
            }
        }, {rootMargin: '300px'});
        observer.observe(panel.current);
        return () => observer.disconnect();
    }, [view, viewKey, activatedView]);
    useEffect(() => {
        setRenderer(null);
        if (activatedView !== viewKey || !view || view.invalid || !view.elements.length) return;
        setMenu(null); setHover(null);
        let disposed = false, destroy;
        import('./renderer').then(({render}) => {
            if (!disposed) {
                const instance = render(container.current, view.elements, colorMode === 'dark', setZoom, {
                    onNodeMenu: setMenu, onDismissMenu: () => setMenu(null), onHover: setHover,
                    viewport: viewport.current?.key === viewKey ? viewport.current.value : null,
                });
                controls.current = instance;
                setRenderer(instance);
                setTableReady(true);
                destroy = () => {
                    viewport.current = {key: viewKey, value: instance.viewport()};
                    instance.destroy(); controls.current = null;
                };
            }
        }).catch(() => { if (!disposed) setFailed(true); });
        return () => { disposed = true; destroy?.(); };
    }, [view, colorMode, viewKey, activatedView]);
    const closeMenu = (restoreFocus = true) => { setMenu(null); if (restoreFocus) container.current?.focus(); };
    const changeGroup = expanded => {
        setCollapsedGroups(previous => {
            const next = new Set(previous);
            if (expanded) next.delete(menu.group); else next.add(menu.group);
            return next;
        });
        closeMenu();
    };
    const menuGroup = menu && snapshot?.graph.groups.get(menu.group);
    const environmentPrefix = url.slice(0, -'reaction-graph.json'.length);
    const destination = !menu ? null : menu.kind === 'group'
        ? {href: componentHref(entry, menuGroup?.owner, environmentPrefix), label: 'Go to component documentation'}
        : interfaceDestination(entry, snapshot?.graph.nodes.get(menu.id), environmentPrefix);
    if (failed || view?.invalid) return <p role="status">Reaction graph currently unavailable</p>;
    if (!view) return <p role="status">Loading reaction graph …</p>;
    if (!view.elements.length && !view.superseded && snapshot.graph.ownershipComplete) return null;
    let focus = `${entry.system} (System)`;
    if (entry.kind === 'COMPONENT') focus = entry.component;
    if (entry.kind === 'MESSAGE') focus = entry.message + (entry.variant ? ` [${entry.variant}]` : '');
    const count = view.reactionCount;
    const summary = `${count} ${entry.kind === 'MESSAGE' ? 'directly connected ' : ''}${count === 1 ? 'reaction' : 'reactions'}`;
    return <><section ref={panel} className={styles.viewer} aria-label="Reaction chains">
        <div className={styles.toolbar}>
            <div><strong>{focus}</strong><span className={styles.caption}>{summary}</span></div>
            <div className={styles.actions} role="group" aria-label="Graph view">
                <button type="button" disabled={sourceOpen} aria-label="Zoom out" title="Zoom out" onClick={() => controls.current?.zoom(0.8)}><ZoomOutIcon/></button>
                <output aria-label="Zoom">{zoom}%</output>
                <button type="button" disabled={sourceOpen} aria-label="Zoom in" title="Zoom in" onClick={() => controls.current?.zoom(1.25)}><ZoomInIcon/></button>
                <button type="button" disabled={sourceOpen} aria-label="Fit all" title="Fit all" onClick={() => controls.current?.fit()}><FitIcon/></button>
                <button type="button" disabled={sourceOpen} aria-label="Reset view" title="Reset view" onClick={() => controls.current?.reset()}><ResetZoomIcon/></button>
                <button type="button" disabled={sourceOpen} aria-expanded={minimapOpen} aria-controls={minimapId}
                    aria-label={minimapOpen ? 'Hide minimap' : 'Show minimap'} title={minimapOpen ? 'Hide minimap' : 'Show minimap'}
                    onClick={() => setMinimapOpen(open => !open)}><MinimapIcon/></button>
                <button ref={sourceToggle} type="button" aria-expanded={sourceOpen} aria-controls={sourceId}
                    aria-label={sourceOpen ? 'Hide source' : 'Show source'} title={sourceOpen ? 'Hide source' : 'Show source'}
                    onClick={() => { setSourceOpen(open => !open); setMenu(null); setHover(null); }}><SourceIcon/></button>
                <button type="button" aria-pressed={fullscreen} onClick={toggleFullscreen}
                    aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
                    {fullscreen ? <CloseIcon/> : <MaximizeIcon/>}
                </button>
            </div>
        </div>
        {!snapshot.graph.ownershipComplete && <p role="status">Incomplete ownership: Not all reactions can be assigned to a system.</p>}
        {view.superseded > 0 && <p>{view.superseded} superseded reactions hidden.</p>}
        <div className={styles.graph}>
            <div ref={container} className={`${styles.canvas} ${sourceOpen ? styles.coveredCanvas : ''}`} tabIndex={sourceOpen ? -1 : 0} role="group"
                aria-hidden={sourceOpen || undefined}
                aria-label="Reaction graph. Use arrow keys to pan, plus and minus to zoom, and zero to fit all. Right-click or press Shift+F10 to open the context menu."
                aria-describedby={hover && !menu ? tooltipId : undefined}
                onMouseLeave={() => setHover(null)} onKeyDown={onKeyDown} onContextMenu={event => event.preventDefault()}/>
            {minimapOpen && !sourceOpen && renderer && <Minimap id={minimapId} renderer={renderer}/>}
            {sourceOpen && <SourceView id={sourceId} source={source}
                onClose={() => { setSourceOpen(false); sourceToggle.current?.focus(); }}/>
            }
            {hover && !menu && !sourceOpen && <Tooltip id={tooltipId} hover={hover}/>}
            {menu && <NodeMenu menu={menu} href={destination.href} linkLabel={destination.label}
                canExpand={menuGroup?.members.some(id => !view.state.visible.has(id)
                    && snapshot.graph.nodes.get(id).reaction.status !== 'SUPERSEDED')}
                canCollapse={menuGroup?.members.some(id => view.state.visible.has(id) && !view.state.pins.has(id))}
                onChange={changeGroup} onClose={closeMenu}/>}
        </div>
        {notice && <p role="status">{notice}</p>}
        <Legend dark={colorMode === 'dark'} elements={view.elements}/>
    </section>
        {tableReady && <ReactionTable key={viewKey} rows={view.rows}/>}
    </>;
}
