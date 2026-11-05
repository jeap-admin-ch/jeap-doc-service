import React, {useEffect, useRef, useState} from 'react';
import styles from './styles.module.css';

export default function SourceView({id, source, onClose}) {
    const input = useRef(null), current = useRef(source);
    const [status, setStatus] = useState('');
    useEffect(() => {
        current.current = source; setStatus(''); input.current?.focus();
        return () => { current.current = null; };
    }, [source]);
    const copy = async () => {
        try {
            if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
            await navigator.clipboard.writeText(source);
            if (current.current === source) setStatus('Copied.');
        } catch {
            if (current.current !== source) return;
            input.current?.select();
            setStatus('Automatic copying is unavailable. Copy the selected text with Ctrl+C or ⌘C.');
        }
    };
    return <section id={id} className={styles.sourceView} aria-label="Graph source"
        onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); onClose(); } }}>
        <div className={styles.sourceBar}>
            <strong>Graph source (JSON)</strong>
            <button type="button" onClick={copy}>Copy source</button>
        </div>
        <p>The currently displayed graph, including groups, nodes and edges.</p>
        {status && <p role="status">{status}</p>}
        <textarea ref={input} readOnly spellCheck={false} aria-label="Graph source (JSON)" value={source}/>
    </section>;
}
