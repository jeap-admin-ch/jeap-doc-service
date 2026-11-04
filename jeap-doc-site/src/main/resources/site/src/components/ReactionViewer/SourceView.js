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
            if (current.current === source) setStatus('Kopiert.');
        } catch {
            if (current.current !== source) return;
            input.current?.select();
            setStatus('Automatisches Kopieren nicht verfügbar. Den markierten Text mit Strg+C oder ⌘C kopieren.');
        }
    };
    return <section id={id} className={styles.sourceView} aria-label="Graph-Quelltext"
        onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); onClose(); } }}>
        <div className={styles.sourceBar}>
            <strong>Graph-Quelltext (JSON)</strong>
            <button type="button" onClick={copy}>Quelltext kopieren</button>
        </div>
        <p>Aktuell dargestellter Graph mit Gruppen, Knoten und Kanten.</p>
        {status && <p role="status">{status}</p>}
        <textarea ref={input} readOnly spellCheck={false} aria-label="Graph-Quelltext (JSON)" value={source}/>
    </section>;
}
