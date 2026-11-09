import React, {useEffect, useId, useMemo, useRef, useState} from 'react';
import {expansionLimit, expandView, interfaceLabel, pathCandidates, pathSelection} from './exploration.mjs';
import styles from './styles.module.css';

function Dialog({title, onClose, children}) {
    const ref = useRef(null);
    useEffect(() => {
        const dialog = ref.current;
        dialog.showModal();
        return () => dialog.close();
    }, []);
    return <dialog ref={ref} className={styles.explorationDialog} aria-label={title}
        onCancel={event => { event.preventDefault(); onClose(); }}>
        <h3>{title}</h3>{children}
        <button type="button" onClick={onClose}>Cancel</button>
    </dialog>;
}

export function ExpansionDialog({graph, state, id, direction, onApply, onClose}) {
    const [depth, setDepth] = useState('1');
    const helpId = useId();
    const maximum = useMemo(() => expansionLimit(graph, state, id, direction), [graph, state, id, direction]);
    const preview = useMemo(() => {
        try {
            const next = expandView(graph, state, id, direction, depth.trim() ? Number(depth) : NaN);
            return {changed: next !== state, message: next === state ? 'Already expanded at this depth.' : ''};
        } catch (error) { return {changed: false, message: error.message}; }
    }, [graph, state, id, direction, depth]);
    return <Dialog title={`Expand ${direction} paths`} onClose={onClose}>
        <form onSubmit={event => { event.preventDefault(); if (preview.changed) onApply({type: 'expand', id, direction, depth: Number(depth)}); }}>
            <label>Edge depth
                <input autoFocus type="number" min="1" max={maximum || 1} step="1" value={depth}
                    onChange={event => setDepth(event.target.value)} aria-describedby={helpId}/>
            </label>
            <p id={helpId}>{maximum ? `1–${maximum} edges. Existing contents and pins remain.` : 'No reachable neighbours.'}</p>
            {preview.message && <p role="status">{preview.message}</p>}
            <button type="submit" disabled={!preview.changed}>Expand</button>
        </form>
    </Dialog>;
}

export function PathDialog({graph, state, initialIds, onApply, onClose}) {
    const [ids, setIds] = useState(initialIds ?? []), [choice, setChoice] = useState('');
    const candidates = useMemo(() => pathCandidates(graph, state, ids), [graph, state, ids]);
    const selection = useMemo(() => pathSelection(graph, state, ids), [graph, state, ids]);
    return <Dialog title="Open path view" onClose={onClose}>
        <p>Select two or more interfaces. Both directions and cyclic detours are included.</p>
        <ul aria-label="Selected interfaces">{ids.map(id => <li key={id}>
            {interfaceLabel(graph, id)} <button type="button" aria-label={`Remove ${interfaceLabel(graph, id)}`}
                onClick={() => { setIds(previous => previous.filter(other => other !== id)); setChoice(''); }}>Remove</button>
        </li>)}</ul>
        <form onSubmit={event => {
            event.preventDefault();
            if (candidates.some(candidate => candidate.id === choice)) { setIds(previous => [...previous, choice]); setChoice(''); }
        }}>
            <label>Add interface
                <select autoFocus value={choice} onChange={event => setChoice(event.target.value)}>
                    <option value="">Choose an interface</option>
                    {candidates.map(candidate => <option key={candidate.id} value={candidate.id}>{candidate.label}</option>)}
                </select>
            </label>
            <button type="submit" disabled={!choice}>Add</button>
        </form>
        <p role="status">{ids.length < 2 ? 'Select at least two interfaces.' : !selection.connected
            ? 'No directed connection between the selected interfaces.' : 'These are graph relationships, not an individual execution trace.'}</p>
        <button type="button" disabled={!selection.connected} onClick={() => onApply({type: 'paths', ids})}>Show paths</button>
    </Dialog>;
}
