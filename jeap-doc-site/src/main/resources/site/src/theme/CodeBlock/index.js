import React, {useMemo} from 'react';
import CodeBlock from '@theme-original/CodeBlock';
import ReactionViewer from '../../components/ReactionViewer';
import Excerpts from '../../components/Excerpts';
import {readEntry} from '../../components/Excerpts/model.mjs';

export default function CodeBlockWrapper(props) {
    const excerpts = useMemo(() => (props.className === 'language-jeap-excerpts' ? readEntry(props.children) : undefined),
        [props.className, props.children]);
    const entry = useMemo(() => {
        if (props.className !== 'language-reaction-view') return null;
        const params = new URLSearchParams(String(props.children).trim());
        const componentLinks = params.getAll('componentLink').map(value => Object.fromEntries(new URLSearchParams(value)));
        const interfaceLinks = params.getAll('interfaceLink').map(value => Object.fromEntries(new URLSearchParams(value)));
        params.delete('componentLink');
        params.delete('interfaceLink');
        return {...Object.fromEntries(params), componentLinks, interfaceLinks};
    }, [props.className, props.children]);
    // The placeholder of the live excerpts: never shown as code, and nothing at all where it cannot be read.
    if (excerpts !== undefined) {
        return excerpts ? <Excerpts entry={excerpts}/> : null;
    }
    if (entry && ['SYSTEM', 'COMPONENT', 'MESSAGE'].includes(entry.kind)) {
        return <ReactionViewer entry={entry}/>;
    }
    return <CodeBlock {...props}/>;
}
