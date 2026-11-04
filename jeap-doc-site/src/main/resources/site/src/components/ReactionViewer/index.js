import React, {useEffect, useId, useMemo, useRef, useState} from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useColorMode} from '@docusaurus/theme-common';
import {
    CloseIcon, FitIcon, MaximizeIcon, MinimapIcon, ResetZoomIcon, SourceIcon, ZoomInIcon, ZoomOutIcon,
} from '@matfsw/docusaurus-plantuml-plugin/theme/PlantUmlDiagram/icons.js';
import {useEnvironment, routePrefixOf} from '../../data/environments';
import {readGraph, entryView, expandGroups, setGroupExpanded, project} from './model.mjs';
import styles from './styles.module.css';
import Legend from './Legend';
import NodeMenu from './NodeMenu';
import Tooltip from './Tooltip';
import Minimap from './Minimap';
import SourceView from './SourceView';
import {viewSource} from './source.mjs';
import {componentHref, interfaceDestination} from './navigation.mjs';

// A page with many variants fetches and validates one snapshot, with independent view states.
const pending = new Map();
function load(url, site, environment) {
    if (!pending.has(url)) {
        const request = fetch(url, {credentials: 'same-origin'}).then(response => {
            if (!response.ok) throw new Error('Graph unavailable');
            return response.json();
        }).then(payload => readGraph(payload, site, environment));
        pending.set(url, request);
        request.catch(() => pending.delete(url));
    }
    return pending.get(url);
}

export default function ReactionViewer({entry}) {
    const {siteConfig: {baseUrl, customFields: {docSite}}} = useDocusaurusContext();
    const environment = useEnvironment();
    const {colorMode} = useColorMode();
    const url = baseUrl + routePrefixOf(environment).replace(/^\//, '') + (environment.main ? '' : '/') + 'reaction-graph.json';
    const [snapshot, setSnapshot] = useState(null), [failed, setFailed] = useState(false);
    const [notice, setNotice] = useState('');
    const [hover, setHover] = useState(null);
    const tooltipId = useId();
    const minimapId = useId(), sourceId = useId();
    const [minimapOpen, setMinimapOpen] = useState(false), [sourceOpen, setSourceOpen] = useState(false);
    const [renderer, setRenderer] = useState(null);
    const sourceToggle = useRef(null);
    const [collapsedGroups, setCollapsedGroups] = useState(() => new Set());
    const [menu, setMenu] = useState(null);
    useEffect(() => { setCollapsedGroups(new Set()); setMenu(null); setHover(null); setNotice('');
        setMinimapOpen(false); setSourceOpen(false); }, [entry, url]);
    const container = useRef(null), panel = useRef(null), controls = useRef(null);
    const viewport = useRef(null);
    const viewKey = JSON.stringify([url, entry]);
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
        } catch { setNotice('Vollbild ist in diesem Browser nicht verfügbar.'); }
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
        load(url, docSite, environment.id).then(graph => {
            if (!disposed) setSnapshot({url, graph});
        }).catch(() => { if (!disposed) setFailed(true); });
        return () => { disposed = true; };
    }, [url, docSite, environment.id]);
    const view = useMemo(() => {
        if (!snapshot || snapshot.url !== url) return null;
        try {
            let state = expandGroups(snapshot.graph, entryView(snapshot.graph, entry));
            for (const group of collapsedGroups) {
                if (state.selected.has(group)) state = setGroupExpanded(snapshot.graph, state, group, false);
            }
            return {...project(snapshot.graph, state), state};
        }
        catch { return {invalid: true}; }
    }, [snapshot, url, entry, collapsedGroups]);
    const source = useMemo(() => sourceOpen && view && !view.invalid
        ? viewSource(view.elements, entry, docSite, environment.id) : '', [sourceOpen, view, entry, docSite, environment.id]);
    useEffect(() => {
        setRenderer(null);
        if (!view || view.invalid || !view.elements.length) return;
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
                destroy = () => {
                    viewport.current = {key: viewKey, value: instance.viewport()};
                    instance.destroy(); controls.current = null;
                };
            }
        }).catch(() => { if (!disposed) setFailed(true); });
        return () => { disposed = true; destroy?.(); };
    }, [view, colorMode, viewKey]);
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
        ? {href: componentHref(entry, menuGroup?.owner, environmentPrefix), label: 'Zur Komponenten-Dokumentation'}
        : interfaceDestination(entry, snapshot?.graph.nodes.get(menu.id), environmentPrefix);
    if (failed || view?.invalid) return <p role="status">Reaktionsgraph derzeit nicht verfügbar</p>;
    if (!view) return <p role="status">Reaktionsgraph wird geladen …</p>;
    if (!view.elements.length && !view.superseded && snapshot.graph.ownershipComplete) return null;
    return <section ref={panel} className={styles.viewer} aria-label="Reaktionsketten">
        <div className={styles.toolbar}>
            <div><strong>Reaktionsketten</strong><span className={styles.caption}>Beobachtete Abläufe</span></div>
            <div className={styles.actions} role="group" aria-label="Graphansicht">
                <button type="button" disabled={sourceOpen} aria-label="Graph verkleinern" title="Graph verkleinern" onClick={() => controls.current?.zoom(0.8)}><ZoomOutIcon/></button>
                <output aria-label="Zoom">{zoom}%</output>
                <button type="button" disabled={sourceOpen} aria-label="Graph vergrössern" title="Graph vergrössern" onClick={() => controls.current?.zoom(1.25)}><ZoomInIcon/></button>
                <button type="button" disabled={sourceOpen} aria-label="Alles einpassen" title="Alles einpassen" onClick={() => controls.current?.fit()}><FitIcon/></button>
                <button type="button" disabled={sourceOpen} aria-label="Startansicht" title="Startansicht" onClick={() => controls.current?.reset()}><ResetZoomIcon/></button>
                <button type="button" disabled={sourceOpen} aria-expanded={minimapOpen} aria-controls={minimapId}
                    aria-label={minimapOpen ? 'Minimap ausblenden' : 'Minimap einblenden'} title={minimapOpen ? 'Minimap ausblenden' : 'Minimap einblenden'}
                    onClick={() => setMinimapOpen(open => !open)}><MinimapIcon/></button>
                <button ref={sourceToggle} type="button" aria-expanded={sourceOpen} aria-controls={sourceId}
                    aria-label={sourceOpen ? 'Quelltext ausblenden' : 'Quelltext anzeigen'} title={sourceOpen ? 'Quelltext ausblenden' : 'Quelltext anzeigen'}
                    onClick={() => { setSourceOpen(open => !open); setMenu(null); setHover(null); }}><SourceIcon/></button>
                <button type="button" aria-pressed={fullscreen} onClick={toggleFullscreen}
                    aria-label={fullscreen ? 'Vollbild verlassen' : 'Vollbild'} title={fullscreen ? 'Vollbild verlassen' : 'Vollbild'}>
                    {fullscreen ? <CloseIcon/> : <MaximizeIcon/>}
                </button>
            </div>
        </div>
        {!snapshot.graph.ownershipComplete && <p role="status">Unvollständige Zuordnung: Nicht alle Reaktionen können einem System zugeordnet werden.</p>}
        {view.superseded > 0 && <p>{view.superseded} abgelöste Reaktionen ausgeblendet.</p>}
        <div className={styles.graph}>
            <div ref={container} className={`${styles.canvas} ${sourceOpen ? styles.coveredCanvas : ''}`} tabIndex={sourceOpen ? -1 : 0} role="group"
                aria-hidden={sourceOpen || undefined}
                aria-label="Reaktionsgraph. Pfeiltasten verschieben, Plus und Minus zoomen, Null passt alles ein. Rechtsklick oder Umschalt-F10 öffnet das Kontextmenü."
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
    </section>;
}
