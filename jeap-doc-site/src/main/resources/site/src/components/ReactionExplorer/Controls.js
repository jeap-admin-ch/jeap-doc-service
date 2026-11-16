import React, {forwardRef, useEffect, useId, useImperativeHandle, useMemo, useRef, useState} from 'react';
import SearchPicker from '../ReactionViewer/SearchPicker';
import {entryCandidates} from '../ReactionViewer/search.mjs';
import {interfaceLabel, pathCandidates, pathSelection} from '../ReactionViewer/exploration.mjs';
import styles from './styles.module.css';

const tabs = [{id: 'entry', label: 'Find an entry'}, {id: 'paths', label: 'Find paths'}];

/** Search drafts stay independent of the displayed graph and survive switching tabs. */
export default forwardRef(function ExplorerControls({graph, state, environment, onApply}, ref) {
    const id = useId(), container = useRef(null), tabButtons = useRef([]), pathPanel = useRef(null);
    const [tab, setTab] = useState(state.entry.kind === 'PATH' ? 'paths' : 'entry');
    const [ids, setIds] = useState(state.entry.kind === 'PATH' ? state.entry.interfaces : []);
    const entries = useMemo(() => entryCandidates(graph, {scope: 'graph'}), [graph]);
    const candidates = useMemo(() => pathCandidates(graph, state, ids), [graph, state, ids]);
    const selection = useMemo(() => pathSelection(graph, state, ids), [graph, state, ids]);

    // A restored path link or Back to a path also restores its editable endpoint selection.
    useEffect(() => {
        if (state.entry.kind === 'PATH') { setIds(state.entry.interfaces); setTab('paths'); }
    }, [state.entry]);
    useImperativeHandle(ref, () => ({
        openPaths(interfaceId) {
            setIds([interfaceId]); setTab('paths');
            requestAnimationFrame(() => {
                container.current?.scrollIntoView({block: 'start', behavior: 'smooth'});
                pathPanel.current?.querySelector('input')?.focus({preventScroll: true});
            });
        },
    }), []);
    const activate = index => { setTab(tabs[index].id); tabButtons.current[index]?.focus(); };
    const onTabKeyDown = (event, index) => {
        const next = {ArrowRight: (index + 1) % tabs.length, ArrowLeft: (index + tabs.length - 1) % tabs.length,
            Home: 0, End: tabs.length - 1}[event.key];
        if (next !== undefined) { event.preventDefault(); activate(next); }
    };

    return <div ref={container} className={styles.searchControls}>
        <div role="tablist" aria-label="Graph search" className={styles.tabs}>
            {tabs.map((item, index) => <button key={item.id} type="button" role="tab" id={`${id}-${item.id}-tab`}
                ref={element => { tabButtons.current[index] = element; }}
                aria-selected={tab === item.id} aria-controls={`${id}-${item.id}-panel`}
                tabIndex={tab === item.id ? 0 : -1} onClick={() => setTab(item.id)}
                onKeyDown={event => onTabKeyDown(event, index)}>{item.label}</button>)}
        </div>
        <div className={styles.searchBody}>
            <p className={styles.scope}>Complete graph · {environment.label}</p>
            <div role="tabpanel" id={`${id}-entry-panel`} aria-labelledby={`${id}-entry-tab`} hidden={tab !== 'entry'}>
                <p>Search for an event, component or interface to open its graph.</p>
                <SearchPicker label="Find an entry" placeholder="Event type, variant, component or interface…" candidates={entries}
                    onSelect={candidate => onApply({type: 'entry', entry: candidate.entry})}/>
            </div>
            <div ref={pathPanel} role="tabpanel" id={`${id}-paths-panel`} aria-labelledby={`${id}-paths-tab`} hidden={tab !== 'paths'}>
                <p>Choose two or more events or interfaces to see how they connect.</p>
                <ul aria-label="Selected interfaces" className={styles.endpoints}>
                    {ids.map((endpoint, index) => <li key={endpoint}>
                        <span className={styles.endpointNumber} aria-hidden="true">{index + 1}</span>
                        <span>{interfaceLabel(graph, endpoint)}</span>
                        <button type="button" aria-label={`Remove ${interfaceLabel(graph, endpoint)}`}
                            onClick={() => setIds(previous => previous.filter(other => other !== endpoint))}>×</button>
                    </li>)}
                </ul>
                <SearchPicker label="Add interface" candidates={candidates}
                    placeholder={ids.length ? 'Search for a connected event or interface…' : 'Search for your first event or interface…'}
                    onSelect={candidate => setIds(previous => [...previous, candidate.id])}/>
                <p role="status" className={styles.pathHint}>{ids.length === 0 ? 'Choose your first endpoint.'
                    : ids.length === 1 ? 'Choose at least one more endpoint. Only connected interfaces are offered.'
                        : !selection.connected ? 'No directed connection between the selected interfaces.'
                            : `${ids.length} endpoints selected. Paths are explored in both directions.`}</p>
                <div className={styles.pathActions}>
                    <button type="button" className="button button--primary" disabled={!selection.connected}
                        onClick={() => onApply({type: 'paths', ids})}>Show paths</button>
                    {ids.length > 0 && <button type="button" className="button button--secondary button--outline"
                        onClick={() => setIds([])}>Clear selection</button>}
                </div>
            </div>
        </div>
    </div>;
});
