import React, {useContext, useEffect, useId, useMemo, useRef, useState} from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useLocation} from '@docusaurus/router';
import {useColorMode} from '@docusaurus/theme-common';
import {
    CloseIcon, FitIcon, MaximizeIcon, MinimapIcon, ResetZoomIcon, SourceIcon, ZoomInIcon, ZoomOutIcon,
} from '@matfsw/docusaurus-plantuml-plugin/theme/PlantUmlDiagram/icons.js';
import {useEnvironment, routePrefixOf} from '../../data/environments';
import {readGraph, availableReaction, project, labelOf, focusedReactionCount, visibleReactionRows} from './model.mjs';
import styles from './styles.module.css';
import {hiddenMarkers} from './visibility.mjs';
import {AddressUpdates, SnapshotRequests, RestoreNotices} from './SnapshotProvider';
import Legend from './Legend';
import HiddenContent from './HiddenContent';
import {advanceHistory, backHistory, startHistory} from './history.mjs';
import {LINK_PREFIX, restoreLink, shareLink} from './sharing.mjs';
import {ExpansionDialog} from './ExplorationDialog';
import NodeMenu from './NodeMenu';
import Tooltip from './Tooltip';
import Minimap from './Minimap';
import SourceView from './SourceView';
import ReactionTable from './ReactionTable';
import ExplorerControls from '../ReactionExplorer/Controls';
import SearchPicker from './SearchPicker';
import {entryCandidates} from './search.mjs';
import {viewSource} from './source.mjs';
import {componentHref, interfaceDestination, documentationDestinations} from './navigation.mjs';

function ActionIcon({path}) {
    return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d={path}/></svg>;
}

class MissingSnapshot extends Error {}

// A page with many variants fetches and validates one snapshot, with independent view states.
function load(pending, url, site, environment) {
    if (!pending.has(url)) {
        const request = fetch(url, {credentials: 'same-origin', cache: 'no-cache'}).then(response => {
            if (!response.ok) throw new Error('Graph unavailable');
            return response.json();
        }).then(payload => {
            if (payload.formatVersion === 1 && payload.site === site && payload.environment === environment
                && payload.available === false) throw new MissingSnapshot();
            return readGraph(payload, site, environment);
        });
        pending.set(url, request);
        request.catch(() => pending.delete(url));
    }
    return pending.get(url);
}

export default function ReactionViewer({entry, explorer = false}) {
    const environment = useEnvironment();
    if (!environment) return <p role="status">Reaction graph currently unavailable</p>;
    return <EnvironmentReactionViewer entry={entry} environment={environment} explorer={explorer}/>;
}

function EnvironmentReactionViewer({entry, environment, explorer}) {
    const {siteConfig: {baseUrl, customFields: {docSite}}} = useDocusaurusContext();
    const {hash, pathname} = useLocation();
    const sharedHash = hash.startsWith(LINK_PREFIX) ? hash : '';
    const {colorMode} = useColorMode();
    const requests = useContext(SnapshotRequests);
    const restoreNotices = useContext(RestoreNotices);
    const addressUpdates = useContext(AddressUpdates);
    const addressOwner = useRef({}).current;
    const addressPath = useRef(pathname), addressState = useRef(null);
    const url = baseUrl + routePrefixOf(environment).replace(/^\//, '') + (environment.main ? '' : '/') + 'reaction-graph.json';
    const [snapshot, setSnapshot] = useState(null), [failed, setFailed] = useState(false);
    const [notice, setNotice] = useState('');
    const [addressError, setAddressError] = useState('');
    const [sharedLink, setSharedLink] = useState('');
    const [sharing, setSharing] = useState(false);
    const [copyFeedback, setCopyFeedback] = useState(null);
    const shareRequest = useRef(0);
    useEffect(() => {
        if (!copyFeedback) return;
        const timer = setTimeout(() => setCopyFeedback(null), 3000);
        return () => clearTimeout(timer);
    }, [copyFeedback]);
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
        setMinimapOpen(false); setSourceOpen(false); setTableReady(false); setSharedLink('');
        setAddressError(''); addressPath.current = pathname;
        setSharing(false); setCopyFeedback(null); shareRequest.current++;
        return () => { shareRequest.current++; addressUpdates.cancel(addressOwner); }; }, [entry, url, sharedHash, addressUpdates]);
    const container = useRef(null), panel = useRef(null), controls = useRef(null);
    const explorerControls = useRef(null);
    const viewport = useRef(null), followedView = useRef(null), revealedLink = useRef(null);
    const viewKey = JSON.stringify([url, entry, sharedHash]);
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
        } catch { setCopyFeedback({message: 'Fullscreen is unavailable in this browser.'}); }
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
        load(requests, url, docSite, environment.id).then(async graph => {
            if (disposed) return;
            let session = startHistory(graph, entry), reveal = false;
            try {
                const restored = await restoreLink(graph, sharedHash,
                    {site: docSite, environment: environment.id, baseUrl, path: window.location.pathname}, entry);
                if (restored) { session = restored; reveal = true; }
            } catch (error) {
                if (!disposed) {
                    if (!error.unaddressed || !restoreNotices.has(sharedHash) || restoreNotices.get(sharedHash) === addressOwner) {
                        if (error.unaddressed) restoreNotices.set(sharedHash, addressOwner);
                        setNotice(error.message);
                        reveal = true;
                    }
                }
            }
            if (disposed) return;
            setSnapshot({key: viewKey, graph, session, reveal});
        }).catch(error => { if (!disposed) setFailed(error instanceof MissingSnapshot ? 'empty' : true); });
        return () => { disposed = true; };
    }, [requests, url, docSite, environment.id, baseUrl, entry, sharedHash, viewKey, restoreNotices]);
    const session = editedView?.key === viewKey ? editedView.session : snapshot?.session;
    addressState.current = {snapshot, session, viewKey, entry, docSite, environment: environment.id, baseUrl};
    const generateAddress = () => {
        const current = addressState.current;
        if (!current.snapshot || current.snapshot.key !== current.viewKey) throw new Error('The reaction view is still loading.');
        return shareLink(current.snapshot.graph, current.session,
            {site: current.docSite, environment: current.environment, baseUrl: current.baseUrl, path: addressPath.current},
            current.entry, window.location.href, controls.current?.viewport());
    };
    const publishAddress = link => {
        if (window.location.pathname !== addressPath.current) return;
        window.history.replaceState(window.history.state, '', link);
        setAddressError('');
    };
    const scheduleAddress = () => {
        if (window.location.pathname !== addressPath.current) return;
        addressUpdates.schedule(addressOwner, {
            generate: generateAddress,
            publish: link => { publishAddress(link); setSharedLink(previous => previous ? link : ''); },
            fail: error => {
                if (window.location.pathname !== addressPath.current) return;
                const current = new URL(window.location.href);
                if (current.hash.startsWith(LINK_PREFIX)) { current.hash = ''; window.history.replaceState(window.history.state, '', current); }
                setSharedLink(''); setAddressError(error.message);
            },
        });
    };
    useEffect(() => renderer?.observeChanges(() => scheduleAddress()), [renderer, viewKey, addressUpdates]);
    const view = useMemo(() => {
        if (!snapshot || snapshot.key !== viewKey) return null;
        try {
            const state = session.state;
            const projection = project(snapshot.graph, state);
            return {...projection, elements: [...projection.elements, ...hiddenMarkers(snapshot.graph, state)],
                state, reactionCount: focusedReactionCount(snapshot.graph, state),
                rows: visibleReactionRows(snapshot.graph, state)};
        }
        catch { return {invalid: true}; }
    }, [snapshot, viewKey, session]);
    // Search the semantic projection, including collapsed groups and offscreen nodes, never the canvas viewport.
    const localCandidates = useMemo(() => view && !view.invalid ? entryCandidates(snapshot.graph, {
        scope: 'selection', visibleIds: new Set(view.elements.filter(element => !element.data.source).map(element => element.data.id)),
    }) : [], [snapshot, view]);
    const source = useMemo(() => sourceOpen && view && !view.invalid
        ? viewSource(view.elements, view.state.entry, docSite, environment.id, snapshot.graph) : '', [sourceOpen, view, entry, docSite, environment.id]);
    useEffect(() => {
        if (!sharedHash) { revealedLink.current = null; return; }
        if (!view || view.invalid || snapshot?.key !== viewKey || !snapshot.reveal
            || !panel.current || revealedLink.current === viewKey) return;
        setActivatedView(viewKey);
        // Wait for the live graph and table to replace their placeholders before positioning the page.
        if (view.elements.length && !renderer) return;
        const frame = requestAnimationFrame(() => {
            revealedLink.current = viewKey;
            const element = panel.current;
            if (!element) return;
            const margin = Number.parseFloat(getComputedStyle(element).scrollMarginTop) || 0;
            window.scrollTo({top: window.scrollY + element.getBoundingClientRect().top - margin, behavior: 'instant'});
        });
        return () => cancelAnimationFrame(frame);
    }, [view, snapshot, sharedHash, viewKey, renderer]);
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
        if (activatedView !== viewKey || !view || view.invalid) return;
        if (!view.elements.length) { setTableReady(true); return; }
        setMenu(null); setHover(null);
        let disposed = false, destroy;
        import('./renderer').then(({render}) => {
            if (!disposed) {
                const followAction = editedView?.key === viewKey && followedView.current !== editedView;
                const instance = render(container.current, view.elements, colorMode === 'dark', setZoom, {
                    onNodeMenu: setMenu, onRestore: (ids, anchor) => applyAction({type: 'show', ids}, anchor),
                    onExpand: action => applyAction({type: 'expand', ...action}), onDismissMenu: () => setMenu(null), onHover: setHover,
                    viewport: followAction && editedView.restoreViewport ? editedView.restoreViewport
                        : viewport.current?.key === presentationKey ? {...viewport.current.value, restorePositions: !followAction}
                            : !editedView ? session.presentation : null,
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
        shareRequest.current++; setSharing(false); setCopyFeedback(null);
        const beforePresentation = controls.current?.viewport();
        const actionFocus = anchor ? controls.current?.actionFocus(anchor, ['expand', 'expand-group', 'show'].includes(action.type),
            ['hide', 'outgoing', 'connected'].includes(action.type)) : null;
        setEditedView(previous => {
            const current = previous?.key === viewKey ? previous.session : session;
            const next = action.type === 'back' ? backHistory(current)
                : advanceHistory(snapshot.graph, current, action, beforePresentation);
            if (next === current) return previous;
            return {key: viewKey, session: next, actionFocus,
                restoreViewport: action.type === 'back' && next.presentation ? {...next.presentation, restorePositions: true} : null,
                fitRevision: (previous?.key === viewKey ? previous.fitRevision : 0)
                    + (['back', 'reset', 'entry', 'focus', 'paths'].includes(action.type) ? 1 : 0)};
        });
        if (action.type === 'reset') controls.current?.reset();
        setSharedLink(''); setNotice(''); setDialog(null); closeMenu();
        scheduleAddress();
    };
    const share = async () => {
        if (window.location.pathname !== addressPath.current) return;
        const request = ++shareRequest.current;
        const ticket = addressUpdates.begin(addressOwner);
        setSharing(true); setCopyFeedback(null);
        try {
            const link = await generateAddress();
            if (request !== shareRequest.current || !addressUpdates.current(ticket)) return;
            // Keep the live viewer and its undo history instead of replaying through the router.
            publishAddress(link);
            setSharedLink(link);
            try {
                await navigator.clipboard.writeText(link);
                if (request === shareRequest.current) { setCopyFeedback({message: 'Link copied'}); setNotice(''); }
            }
            catch { if (request === shareRequest.current) { setCopyFeedback({message: 'Copy the shared view link below.'}); setNotice(''); } }
        } catch (error) { if (request === shareRequest.current) { setSharedLink(''); setNotice(error.message); } }
        finally { if (request === shareRequest.current) setSharing(false); }
    };
    const changeGroup = expanded => applyAction({type: expanded ? 'expand-group' : 'collapse', id: menu.group});
    const menuGroup = menu && snapshot?.graph.groups.get(menu.group);
    const environmentPrefix = url.slice(0, -'reaction-graph.json'.length);
    const documentation = explorer ? snapshot?.graph.documentation ?? {} : entry;
    const destination = !menu ? null : menu.kind === 'group'
        ? {href: componentHref(documentation, menuGroup?.owner, environmentPrefix), label: 'Go to component documentation'}
        : interfaceDestination(documentation, snapshot?.graph.nodes.get(menu.id), environmentPrefix);
    const destinations = !menu ? [] : explorer
        ? documentationDestinations(documentation, menu.kind === 'group' ? menuGroup?.owner : null,
            snapshot.graph.nodes.get(menu.id), environmentPrefix)
        : destination?.href ? [destination] : [];
    if (explorer && failed === 'empty') return <p role="status">No reaction data has been published for this environment yet.</p>;
    if (failed || view?.invalid) return <p role="status">Reaction graph currently unavailable</p>;
    if (!view) return <p role="status">Loading reaction graph …</p>;
    if (!explorer && !view.elements.length && !view.superseded && !view.state.hidden.size && snapshot.graph.ownershipComplete && !notice && !addressError) return null;
    const entrySearch = explorer && <ExplorerControls ref={explorerControls} graph={snapshot.graph}
        state={view.state} environment={environment} onApply={applyAction}/>;
    if (explorer && view.state.entry.kind === 'EXPLORER') return <>
        {entrySearch}
        {!snapshot.graph.ownershipComplete && <p role="status">Incomplete ownership: Not all reactions can be assigned to a system.</p>}
        {notice && <div role="status" className={styles.explorerError}>{notice}</div>}
    </>;
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
    const feedback = addressError || notice || copyFeedback?.message;
    const feedbackError = Boolean(addressError || notice);
    const closeDialog = () => { setDialog(null); container.current?.focus({preventScroll: true}); };
    return <>{entrySearch}<section ref={panel} className={styles.viewer} aria-label="Reaction chains">
        <div className={styles.toolbar}>
            <div><strong>{focus}</strong><span className={styles.caption}>{summary}</span></div>
            <div className={styles.actions} role="group" aria-label="Graph view">
                <SearchPicker compact label="Search this graph" placeholder="Search this graph…" candidates={localCandidates}
                    disabled={sourceOpen || !renderer} onSelect={candidate => controls.current?.locate(candidate.members)}/>
                <span className={styles.actionGroup} role="group" aria-label="View history">
                    <button type="button" disabled={sourceOpen || !session.history.length} onClick={() => applyAction({type: 'back'})}
                        aria-label="Back" title="Back"><ActionIcon path="M9 5l-6 6 6 6 M3 11h11a6 6 0 0 1 6 6v2"/></button>
                    <button type="button" disabled={sourceOpen} aria-label="Reset view" title="Reset view" onClick={() => applyAction({type: 'reset'})}><ResetZoomIcon/></button>
                </span>
                <span className={styles.actionGroup} role="group" aria-label="Share and source">
                    <button type="button" disabled={sharing} aria-busy={sharing} onClick={share} aria-label="Share link" title="Share link">
                        <ActionIcon path="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2 M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2"/>
                    </button>
                    <button ref={sourceToggle} type="button" aria-expanded={sourceOpen} aria-controls={sourceId}
                        aria-label={sourceOpen ? 'Hide source' : 'Show source'} title={sourceOpen ? 'Hide source' : 'Show source'}
                        onClick={() => { setSourceOpen(open => !open); setMenu(null); setHover(null); }}><SourceIcon/></button>
                </span>
                <span className={styles.actionGroup} role="group" aria-label="View controls">
                    <button type="button" disabled={sourceOpen} aria-label="Zoom out" title="Zoom out" onClick={() => controls.current?.zoom(0.8)}><ZoomOutIcon/></button>
                    <output aria-label="Zoom">{zoom}%</output>
                    <button type="button" disabled={sourceOpen} aria-label="Zoom in" title="Zoom in" onClick={() => controls.current?.zoom(1.25)}><ZoomInIcon/></button>
                    <button type="button" disabled={sourceOpen} aria-label="Fit all" title="Fit all" onClick={() => controls.current?.fit()}><FitIcon/></button>
                    <button type="button" disabled={sourceOpen} aria-expanded={minimapOpen} aria-controls={minimapId}
                        aria-label={minimapOpen ? 'Hide minimap' : 'Show minimap'} title={minimapOpen ? 'Hide minimap' : 'Show minimap'}
                        onClick={() => setMinimapOpen(open => !open)}><MinimapIcon/></button>
                    <button type="button" aria-pressed={fullscreen} onClick={toggleFullscreen}
                        aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
                        {fullscreen ? <CloseIcon/> : <MaximizeIcon/>}
                    </button>
                </span>
            </div>
            {feedback && <div role="status" aria-live="polite" aria-atomic="true"
                className={`${styles.copyFeedback} ${feedbackError ? styles.errorFeedback : ''}`}>{feedback}</div>}
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
                onCopyStart={() => setCopyFeedback(null)} onCopied={() => setCopyFeedback({message: 'Code copied'})}
                onClose={() => { setSourceOpen(false); sourceToggle.current?.focus(); }}/>
            }
            {hover && !menu && !sourceOpen && <Tooltip id={tooltipId} hover={hover}/>}
            {menu && <NodeMenu allowPaths={explorer} menu={menu} destinations={destinations}
                canExpand={menuGroup?.members.some(id => view.state.reactions.has(id)
                    && !view.state.visible.has(id) && availableReaction(snapshot.graph, view.state, id))}
                canCollapse={menuGroup?.members.some(id => view.state.visible.has(id) && !view.state.pins.has(id))}
                pinned={view.state.pins.has(menu.id)}
                groupPinned={menu.kind === 'group' && (view.state.pins.has(menu.id)
                    || menuGroup?.members.some(id => view.state.pins.has(id)))}
                onExplore={async direction => {
                    const id = menu.id;
                    closeMenu(false);
                    if (direction === 'paths') {
                        try {
                            if (document.fullscreenElement === panel.current) await document.exitFullscreen();
                        } catch {
                            // A rejected fullscreen exit must not discard the selected path start.
                        }
                        explorerControls.current?.openPaths(id);
                    } else setDialog({kind: 'expand', id, direction});
                }}
                onAction={type => applyAction({type, id: menu.id})}
                onChange={changeGroup} onClose={closeMenu}/>}
        </div>
        {dialog?.kind === 'expand' && <ExpansionDialog graph={snapshot.graph} state={view.state}
            id={dialog.id} direction={dialog.direction} onApply={applyAction} onClose={closeDialog}/>}
        {sharedLink && <label>Shared view link <input className={styles.sharedLink} readOnly value={sharedLink}
            onFocus={event => event.target.select()} aria-label="Shared view link"/></label>}
        <Legend dark={colorMode === 'dark'} elements={view.elements}/>
    </section>
        {tableReady && <ReactionTable key={viewKey} rows={view.rows}/>}
    </>;
}
