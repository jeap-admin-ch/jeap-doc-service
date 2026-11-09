import React from 'react';
import {labelOf} from './model.mjs';

function label(graph, id) {
    const group = graph.groups.get(id);
    if (!group) return labelOf(graph.nodes.get(id)).replaceAll('\n', ': ');
    const cause = group.grouping.groupKey ?? group.grouping.trigger?.label ?? group.grouping.reaction?.key;
    return `${group.owner.system ?? '?'} / ${group.owner.component ?? '?'}: ${cause}`;
}

export default function HiddenContent({title, ids, graph, onShow}) {
    if (!ids.length) return null;
    return <details><summary>{title}</summary><ul>
        {ids.map(id => <li key={id}><button type="button" onClick={() => onShow(id)}>
            Show {label(graph, id)}
        </button></li>)}
    </ul></details>;
}
