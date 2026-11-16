import React, {useEffect, useId, useMemo, useRef, useState} from 'react';
import {expansionLimit, expandView} from './exploration.mjs';
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
