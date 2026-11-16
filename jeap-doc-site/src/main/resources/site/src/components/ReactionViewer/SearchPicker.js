import React, {useEffect, useId, useMemo, useRef, useState} from 'react';
import {matchingEntries} from './search.mjs';
import styles from './styles.module.css';

/** Shared keyboard and matching behaviour for entry search and path endpoints. */
export default function SearchPicker({label, placeholder, candidates, onSelect, compact = false, disabled = false}) {
    const id = useId(), input = useRef(null), list = useRef(null);
    const [query, setQuery] = useState(''), [active, setActive] = useState(0), [open, setOpen] = useState(false);
    const results = useMemo(() => matchingEntries(candidates, query), [candidates, query]);
    const current = Math.min(active, Math.max(0, results.length - 1));
    useEffect(() => {
        list.current?.children[current]?.scrollIntoView({block: 'nearest'});
    }, [current, results, open]);
    const choose = result => {
        onSelect(result); setQuery(''); setOpen(false); setActive(0); input.current?.focus({preventScroll: true});
    };
    const expanded = open && !disabled && Boolean(query.trim());
    return <div className={`${styles.entrySearch} ${compact ? styles.localSearch : ''}`} onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }}>
        <label htmlFor={id}>{label}</label>
        {compact && <svg className={styles.searchIcon} width="16" height="16" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
            aria-hidden="true" focusable="false">
            <circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>
        </svg>}
        <input ref={input} id={id} type={compact ? 'search' : 'text'} role="combobox" autoComplete="off" value={query}
            placeholder={placeholder} disabled={disabled}
            title={compact ? 'Search visible contents, including nodes outside the viewport' : undefined}
            aria-autocomplete="list" aria-controls={expanded && results.length ? `${id}-results` : undefined}
            aria-expanded={expanded && results.length > 0}
            aria-activedescendant={expanded && results.length ? `${id}-result-${current}` : undefined}
            onFocus={() => setOpen(true)} onChange={event => { setQuery(event.target.value); setActive(0); setOpen(true); }}
            onKeyDown={event => {
                if (event.key === 'Escape' && open) { event.preventDefault(); event.stopPropagation(); setOpen(false); }
                if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
                    event.preventDefault(); setOpen(true);
                    if (results.length) setActive(open
                        ? (current + (event.key === 'ArrowDown' ? 1 : -1) + results.length) % results.length
                        : event.key === 'ArrowDown' ? 0 : results.length - 1);
                }
                if (event.key === 'Enter' && open && results.length) { event.preventDefault(); choose(results[current]); }
            }}/>
        {expanded && <div className={styles.searchResults}>{results.length ? <ul ref={list} id={`${id}-results`} role="listbox" aria-label={label}>
            {results.map((result, index) => <li key={result.id} id={`${id}-result-${index}`} role="option"
                aria-selected={current === index} onMouseDown={event => event.preventDefault()}
                onClick={() => choose(result)}>{result.label}</li>)}
        </ul> : <p role="status">{compact ? 'No matches in this graph.' : 'No matches.'}</p>}</div>}
    </div>;
}
