import React, {useMemo} from 'react';
import CodeBlock from '@theme-original/CodeBlock';
import ReactionViewer from '../../components/ReactionViewer';

export default function CodeBlockWrapper(props) {
    const entry = useMemo(() => {
        if (props.className !== 'language-reaction-view') return null;
        const params = new URLSearchParams(String(props.children).trim());
        const componentLinks = params.getAll('componentLink').map(value => Object.fromEntries(new URLSearchParams(value)));
        const interfaceLinks = params.getAll('interfaceLink').map(value => Object.fromEntries(new URLSearchParams(value)));
        params.delete('componentLink');
        params.delete('interfaceLink');
        return {...Object.fromEntries(params), componentLinks, interfaceLinks};
    }, [props.className, props.children]);
    if (entry && ['SYSTEM', 'COMPONENT', 'MESSAGE'].includes(entry.kind)) {
        return <ReactionViewer entry={entry}/>;
    }
    return <CodeBlock {...props}/>;
}
