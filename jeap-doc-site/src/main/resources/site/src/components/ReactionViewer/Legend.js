import React from 'react';
import {nodeAppearance, foreignAppearance} from './appearance.mjs';
import styles from './styles.module.css';

function Symbol({kind, appearance}) {
    const shapes = {
        polygon: appearance.shape === 'polygon' ? <polygon points={appearance['shape-polygon-points'].split(' ').map(Number)
            .reduce((points, value, index, values) => index % 2 ? points
                : [...points, `${18 + value * 16},${12 + values[index + 1] * 8}`], []).join(' ')}/> : null,
        ellipse: kind === 'marker' ? <circle cx="18" cy="12" r="10"/> : <ellipse cx="18" cy="12" rx="16" ry="8"/>,
        rectangle: <rect x="2" y="4" width="32" height="16"/>,
        'round-rectangle': <rect x="2" y="3" width="32" height="18" rx={kind === 'http' ? 5 : 4}/>,
    };
    return <svg viewBox="0 0 36 24" width="36" height="24" aria-hidden="true" focusable="false">
        <g fill={appearance['background-color']} fillOpacity={appearance['background-opacity'] ?? 1}
            stroke={appearance['border-color']} strokeWidth={appearance['border-width'] ?? 1.5}
            strokeDasharray={appearance['border-style'] === 'dashed' ? '4 3' : undefined}>
            {shapes[appearance.shape]}
        </g>
        {kind === 'marker' && <text x="18" y="16" textAnchor="middle" fill="currentColor" fontSize="12">2</text>}
    </svg>;
}

export default function Legend({dark, elements}) {
    const appearance = nodeAppearance(dark);
    const kinds = new Set(elements.map(element => element.data.kind));
    kinds.add('http');
    return <div className={styles.legend} aria-label="Legend">
        {Object.entries({message: 'Message', http: 'REST', interface: 'Interface', reaction: 'Reaction',
            group: 'Group', marker: 'Hidden reactions'}).filter(([kind]) => kinds.has(kind)).map(([kind, label]) =>
            <span key={kind}><Symbol kind={kind} appearance={appearance[kind]}/>{label}</span>)}
        {elements.some(element => element.data.foreign) &&
            <span><Symbol kind="message" appearance={{...appearance.message, ...foreignAppearance}}/>Other system</span>}
    </div>;
}
