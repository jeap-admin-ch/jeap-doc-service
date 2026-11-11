import React, {useContext, useEffect, useId, useMemo, useRef, useState} from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useColorMode} from '@docusaurus/theme-common';
import {
    CloseIcon, FitIcon, MaximizeIcon, MinimapIcon, ResetZoomIcon, SourceIcon, ZoomInIcon, ZoomOutIcon,
} from '@matfsw/docusaurus-plantuml-plugin/theme/PlantUmlDiagram/icons.js';
import {useEnvironment, routePrefixOf} from '../../data/environments';
import {readGraph, entryView, expandGroups, availableReaction, project, labelOf, focusedReactionCount, visibleReactionRows} from './model.mjs';
import styles from './styles.module.css';
import {hiddenMarkers} from './visibility.mjs';
import {SnapshotRequests} from './SnapshotProvider';
import Legend from './Legend';
import HiddenContent from './HiddenContent';
import {changeView} from './exploration.mjs';
import {ExpansionDialog, PathDialog} from './ExplorationDialog';
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
    const [editedView, setEditedView] = useState(null);
    const [menu, setMenu] = useState(null), [dialog, setDialog] = useState(null);
    useEffect(() => { setEditedView(null); setDialog(null); setMenu(null); setHover(null); setNotice('');
        setMinimapOpen(false); setSourceOpen(false); setTableReady(false); }, [entry, url]);
    const container = useRef(null), panel = useRef(null), controls = useRef(null);
    const viewport = useRef(null), followedView = useRef(null);
    const viewKey = JSON.stringify([url, entry]);
    const presentationKey = viewKey + ':' + (editedView?.key === viewKey ? editedView.fitRevision : 0);
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
            const state = editedView?.key === viewKey ? editedView.state
                : entry.kind === 'COMPONENT' ? entryView(snapshot.graph, entry)
                    : expandGroups(snapshot.graph, entryView(snapshot.graph, entry));
            const projection = project(snapshot.graph, state);
            return {...projection, elements: [...projection.elements, ...hiddenMarkers(snapshot.graph, state)],
                state, reactionCount: focusedReactionCount(snapshot.graph, state),
                rows: visibleReactionRows(snapshot.graph, state)};
        }
        catch { return {invalid: true}; }
    }, [snapshot, url, entry, editedView, viewKey]);
    const source = useMemo(() => sourceOpen && view && !view.invalid
        ? viewSource(view.elements, view.state.entry, docSite, environment.id) : '', [sourceOpen, view, entry, docSite, environment.id]);
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
                const followAction = editedView?.key === viewKey && followedView.current !== editedView;
                const instance = render(container.current, view.elements, colorMode === 'dark', setZoom, {
                    onNodeMenu: setMenu, onRestore: (ids, anchor) => applyAction({type: 'show', ids}, anchor),
                    onExpand: action => applyAction({type: 'expand', ...action}), onDismissMenu: () => setMenu(null), onHover: setHover,
                    viewport: viewport.current?.key === presentationKey ? viewport.current.value : null,
                    actionFocus: followAction ? editedView.actionFocus : null,
                });
                followedView.current = editedView;
                controls.current = instance;
                if (followAction && editedView.actionFocus) container.current?.focus({preventScroll: true});
                setRenderer(instance);
                setTableReady(true);
                destroy = () => {
                    viewport.current = {key: presentationKey, value: instance.viewport()};
                    instance.destroy(); controls.current = null;
                };
            }
        }).catch(() => { if (!disposed) setFailed(true); });
        return () => { disposed = true; destroy?.(); };
    }, [view, colorMode, viewKey, activatedView, presentationKey]);
    const closeMenu = (restoreFocus = true) => { setMenu(null); if (restoreFocus) container.current?.focus({preventScroll: true}); };
    const applyAction = (action, anchor = action.id) => {
        const actionFocus = anchor ? controls.current?.actionFocus(anchor, ['expand', 'expand-group', 'show'].includes(action.type),
            ['hide', 'outgoing', 'connected'].includes(action.type)) : null;
        setEditedView(previous => {
            const current = previous?.key === viewKey ? previous.state : view.state;
            const next = changeView(snapshot.graph, current, action);
            if (next === current) return previous;
            return {key: viewKey, state: next, actionFocus, fitRevision: (previous?.key === viewKey ? previous.fitRevision : 0)
                + (['focus', 'paths'].includes(action.type) ? 1 : 0)};
        });
        setDialog(null); closeMenu();
    };
    const changeGroup = expanded => applyAction({type: expanded ? 'expand-group' : 'collapse', id: menu.group});
    const menuGroup = menu && snapshot?.graph.groups.get(menu.group);
    const environmentPrefix = url.slice(0, -'reaction-graph.json'.length);
    const destination = !menu ? null : menu.kind === 'group'
        ? {href: componentHref(entry, menuGroup?.owner, environmentPrefix), label: 'Go to component documentation'}
        : interfaceDestination(entry, snapshot?.graph.nodes.get(menu.id), environmentPrefix);
    if (failed || view?.invalid) return <p role="status">Reaction graph currently unavailable</p>;
    if (!view) return <p role="status">Loading reaction graph …</p>;
    if (!view.elements.length && !view.superseded && !view.state.hidden.size && snapshot.graph.ownershipComplete) return null;
    const currentEntry = view.state.entry;
    let focus = `${currentEntry.system} (System)`;
    if (currentEntry.kind === 'COMPONENT') focus = currentEntry.component;
    if (currentEntry.kind === 'MESSAGE') focus = currentEntry.message + (currentEntry.variant ? ` [${currentEntry.variant}]` : '');
    if (['INTERFACE', 'REACTION'].includes(currentEntry.kind)) focus = labelOf(snapshot.graph.nodes.get(currentEntry.id)).replaceAll('\n', ' · ');
    if (currentEntry.kind === 'GROUP') {
        const owner = snapshot.graph.groups.get(currentEntry.id).owner;
        focus = `${owner.system ?? '?'} / ${owner.component ?? '?'} (Group)`;
    }
    if (currentEntry.kind === 'PATH') focus = `Paths between ${currentEntry.interfaces.length} interfaces`;
    const count = view.reactionCount;
    const direct = ['MESSAGE', 'INTERFACE'].includes(currentEntry.kind) && !view.state.explored;
    const summary = `${count} ${direct ? 'directly connected ' : ''}${count === 1 ? 'reaction' : 'reactions'}`;
    const closeDialog = () => { setDialog(null); container.current?.focus({preventScroll: true}); };
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
        <HiddenContent title={`${view.superseded} superseded reactions hidden (presumed superseded)`}
            ids={view.supersededIds} graph={snapshot.graph} onShow={id => applyAction({type: 'show', id})}/>
        <div className={styles.graph} onContextMenuCapture={event => { if (!sourceOpen) event.preventDefault(); }}>
            <div ref={container} className={`${styles.canvas} ${sourceOpen ? styles.coveredCanvas : ''}`} tabIndex={sourceOpen ? -1 : 0} role="group"
                aria-hidden={sourceOpen || undefined}
                aria-label="Reaction graph. Use arrow keys to pan, plus and minus to zoom, and zero to fit all. Right-click or press Shift+F10 to open the context menu."
                aria-describedby={hover && !menu ? tooltipId : undefined}
                onMouseLeave={() => setHover(null)} onKeyDown={onKeyDown}/>
            {minimapOpen && !sourceOpen && renderer && <Minimap id={minimapId} renderer={renderer}/>}
            {sourceOpen && <SourceView id={sourceId} source={source}
                onClose={() => { setSourceOpen(false); sourceToggle.current?.focus(); }}/>
            }
            {hover && !menu && !sourceOpen && <Tooltip id={tooltipId} hover={hover}/>}
            {menu && <NodeMenu menu={menu} href={destination.href} linkLabel={destination.label}
                canExpand={menuGroup?.members.some(id => view.state.reactions.has(id)
                    && !view.state.visible.has(id) && availableReaction(snapshot.graph, view.state, id))}
                canCollapse={menuGroup?.members.some(id => view.state.visible.has(id) && !view.state.pins.has(id))}
                pinned={view.state.pins.has(menu.id)}
                groupPinned={menu.kind === 'group' && (view.state.pins.has(menu.id)
                    || menuGroup?.members.some(id => view.state.pins.has(id)))}
                onExplore={direction => {
                    setDialog(direction === 'paths' ? {kind: 'paths', ids: [menu.id]}
                        : {kind: 'expand', id: menu.id, direction});
                    closeMenu(false);
                }}
                onAction={type => applyAction({type, id: menu.id})}
                onChange={changeGroup} onClose={closeMenu}/>}
        </div>
        {dialog?.kind === 'expand' && <ExpansionDialog graph={snapshot.graph} state={view.state}
            id={dialog.id} direction={dialog.direction} onApply={applyAction} onClose={closeDialog}/>}
        {dialog?.kind === 'paths' && <PathDialog graph={snapshot.graph} state={view.state}
            initialIds={dialog.ids} onApply={applyAction} onClose={closeDialog}/>}
        {notice && <p role="status">{notice}</p>}
        <Legend dark={colorMode === 'dark'} elements={view.elements}/>
    </section>
        {tableReady && <ReactionTable key={viewKey} rows={view.rows}/>}
    </>;
}
