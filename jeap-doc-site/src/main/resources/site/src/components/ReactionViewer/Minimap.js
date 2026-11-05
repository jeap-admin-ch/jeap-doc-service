import React, {useEffect, useMemo, useRef, useState} from 'react';
import {overviewFrame, overviewPoint, overviewSize} from './overview.mjs';
import styles from './styles.module.css';

export default function Minimap({id, renderer}) {
    const overview = useMemo(() => renderer.overview(), [renderer]);
    const [extent, setExtent] = useState(null);
    const pointer = useRef(null);
    useEffect(() => renderer.observeViewport(setExtent), [renderer]);
    const size = overviewSize(overview.bounds);
    const frame = extent ? overviewFrame(overview.bounds, extent) : null;
    const moveTo = event => {
        const box = event.currentTarget.getBoundingClientRect();
        renderer.centerAt(overviewPoint(overview.bounds, event.clientX - box.left, event.clientY - box.top, box.width, box.height));
    };
    const onKeyDown = event => {
        const moves = {ArrowLeft: [60, 0], ArrowRight: [-60, 0], ArrowUp: [0, 60], ArrowDown: [0, -60]};
        if (moves[event.key]) { event.preventDefault(); renderer.pan(...moves[event.key]); }
        if (event.key === 'Home') { event.preventDefault(); renderer.fit(); }
    };
    return <div id={id} className={styles.minimap} role="group" aria-label="Minimap">
        <div className={styles.minimapMap} style={size} tabIndex={0} role="group"
            aria-label="Select the graph viewport. Click or drag; use arrow keys to pan and Home to fit all."
            onKeyDown={onKeyDown}
            onPointerDown={event => {
                if (event.button !== 0 || !event.isPrimary) return;
                event.preventDefault(); event.currentTarget.focus();
                pointer.current = event.pointerId;
                event.currentTarget.setPointerCapture(event.pointerId); moveTo(event);
            }}
            onPointerMove={event => { if (pointer.current === event.pointerId) moveTo(event); }}
            onPointerUp={event => {
                if (pointer.current !== event.pointerId) return;
                pointer.current = null; event.currentTarget.releasePointerCapture(event.pointerId);
            }}
            onPointerCancel={() => { pointer.current = null; }}
            onLostPointerCapture={() => { pointer.current = null; }}>
            <img src={overview.image} alt="" draggable={false}/>
            {frame && <div className={styles.minimapFrame} data-minimap-viewport="true" aria-hidden="true"
                style={{left: frame.left + '%', top: frame.top + '%', width: frame.width + '%', height: frame.height + '%'}}/>}
        </div>
    </div>;
}
