import React, {useCallback, useEffect, useRef, useState} from 'react';
import clsx from 'clsx';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useEnvironment} from '@site/src/data/environments';
import {Excerpt, Kind, titleOf, Where} from '@site/src/components/SearchHit';
import SearchFacets from '@site/src/components/SearchFacets';
import {SOURCE, everything, filtersOf, toggled, writeInto}
    from '@site/src/data/searchFacets';
import styles from './styles.module.css';

/**
 * The search box in the navbar: results appear in a dropdown as the reader types.
 *
 * <b>It searches the environment the reader is in</b>, and nothing asks them to choose. The environment comes
 * from the URL - the same derivation the environment switcher and the link plugin use, in
 * `src/data/environments` - and goes to the index as a filter. One index over the whole site, filtered, rather
 * than one index per environment: the ranking is then comparable across systems, and a filter is applied
 * before it.
 *
 * The index is **loaded at run time, not bundled**. It is published by the doc service beside the site and has
 * a different lifetime from this application, so `pagefind.js` is imported by URL with `webpackIgnore` - the
 * bundler must not try to resolve a file that only exists in the published site.
 *
 * Everything about it degrades: a site that has never been indexed, an index that fails to load, or a reader
 * with no scripts gets no search box rather than a broken one.
 *
 * <b>A hit is followed with a full page load</b>, never through the router - see {@link go}.
 */
export default function SearchBar() {
    return <BrowserOnly>{() => <SearchBarInBrowser/>}</BrowserOnly>;
}

/** The groups the box shows: the source alone - see the note on the selection below. */
const BOX_FACETS = [SOURCE];

/** How many pages the dropdown shows before it offers the full results page. */
const SHOWN = 8;

/** How long the box waits after a keystroke before it searches, so that typing does not queue a search a letter. */
const DEBOUNCE_MS = 150;

function SearchBarInBrowser() {
    const {siteConfig: {baseUrl}} = useDocusaurusContext();
    const environment = useEnvironment();

    const [query, setQuery] = useState('');
    const [results, setResults] = useState(null);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);
    const [open, setOpen] = useState(false);
    const [active, setActive] = useState(-1);
    const [unavailable, setUnavailable] = useState(false);

    /**
     * The source group, and only it.
     *
     * Six chips wrap onto two rows in a dropdown, which costs a result where vertical space is scarcest -
     * and this is the group a reader wants while typing: "not the generated pages", "only the uploaded
     * HTML". What a hit documents is a question for the results page, where there is room for it.
     *
     * There is no reset here: three chips, all on to begin with, and closing the dropdown is the reset.
     */
    const [selection, setSelection] = useState(() => everything(BOX_FACETS));

    const input = useRef(null);
    const container = useRef(null);
    const pagefind = useRef(null);
    const latest = useRef(0);

    /**
     * The index, loaded once and on first use rather than on every page load - it is a WASM engine and a
     * manifest, and a reader who never searches should not pay for them.
     */
    const load = useCallback(async () => {
        if (pagefind.current) {
            return pagefind.current;
        }
        try {
            const module = await import(/* webpackIgnore: true */ `${baseUrl}pagefind/pagefind.js`);
            await module.options({baseUrl});
            pagefind.current = module;
            return module;
        } catch {
            // A site that has never been indexed answers 404 here. There is nothing to search, and a box that
            // never finds anything is worse than none.
            setUnavailable(true);
            return null;
        }
    }, [baseUrl]);

    useEffect(() => {
        if (!query) {
            setResults(null);
            setTotal(0);
            return undefined;
        }
        const run = ++latest.current;
        setLoading(true);
        const timer = setTimeout(async () => {
            const module = await load();
            if (!module || run !== latest.current) {
                setLoading(false);
                return;
            }
            const found = await module.search(query,
                    {filters: filtersOf(selection, environment.id, BOX_FACETS)});
            if (run !== latest.current) {
                // A slower search of an earlier query came back after a faster one of a later query. Dropping
                // it is what stops the dropdown flickering back to what the reader typed two letters ago.
                return;
            }
            const shown = await Promise.all(found.results.slice(0, SHOWN).map((result) => result.data()));
            setResults(shown);
            setTotal(found.results.length);
            setActive(-1);
            setLoading(false);
        }, DEBOUNCE_MS);
        return () => clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [query, environment.id, load, JSON.stringify(selection)]);

    // Closing on a click outside, which is what a dropdown has to do and what nothing else here would notice.
    useEffect(() => {
        const closeOnOutsideClick = (event) => {
            if (container.current && !container.current.contains(event.target)) {
                setOpen(false);
            }
        };
        document.addEventListener('mousedown', closeOnOutsideClick);
        return () => document.removeEventListener('mousedown', closeOnOutsideClick);
    }, []);

    // Ctrl-K, or Command-K on a Mac, focuses the box from anywhere on the page.
    useEffect(() => {
        const shortcut = (event) => {
            if (event.key === 'k' && (event.ctrlKey || event.metaKey)) {
                event.preventDefault();
                input.current?.focus();
            }
        };
        document.addEventListener('keydown', shortcut);
        return () => document.removeEventListener('keydown', shortcut);
    }, []);

    /**
     * Follows a hit, <b>with a full page load rather than through the router</b>.
     *
     * The index is one over the whole site and a site is published as one Docusaurus build per part, so a hit
     * is routinely a page this build has no route for - and `history.push` would answer it with this build's
     * own "Page Not Found" rather than with the page. It is the same reason the generator rewrites every link
     * that leaves a part to `pathname://`, which renders a plain anchor: crossing into another build has to be
     * a page load. The search cannot tell which side of the boundary a hit is on, so it always loads.
     *
     * The urls in the index are the site's own paths, so they carry the base url already.
     */
    const go = useCallback((url) => {
        setOpen(false);
        setQuery('');
        input.current?.blur();
        globalThis.location.assign(url);
    }, []);

    const onKeyDown = (event) => {
        if (event.key === 'Escape') {
            setOpen(false);
            input.current?.blur();
            return;
        }
        const count = results?.length ?? 0;
        if (event.key === 'ArrowDown' && count) {
            event.preventDefault();
            setActive((current) => (current + 1) % count);
        } else if (event.key === 'ArrowUp' && count) {
            event.preventDefault();
            setActive((current) => (current <= 0 ? count - 1 : current - 1));
        } else if (event.key === 'Enter') {
            event.preventDefault();
            if (active >= 0 && results?.[active]) {
                go(results[active].url);
            } else if (query) {
                go(resultsPage());
            }
        }
    };

    /**
     * Where <i>See all</i> goes, carrying what the box has narrowed: the results page opening unfiltered
     * would silently undo what the reader had just done.
     */
    const resultsPage = () => {
        const parameters = new URLSearchParams();
        parameters.set('q', query);
        parameters.set('env', environment.id);
        writeInto(parameters, selection, BOX_FACETS);
        return `${baseUrl}search/?${parameters.toString()}`;
    };

    if (unavailable) {
        return null;
    }

    const showDropdown = open && Boolean(query);
    return (
        <div className={clsx('navbar__search', styles.container)} ref={container}>
            <input
                ref={input}
                type="search"
                role="combobox"
                aria-expanded={showDropdown}
                aria-controls="search-results-list"
                aria-autocomplete="list"
                aria-label="Search the documentation"
                placeholder={`Search ${environment.label}`}
                className={clsx('navbar__search-input', styles.input)}
                value={query}
                onChange={(event) => {
                    setQuery(event.target.value);
                    setOpen(true);
                }}
                onFocus={() => {
                    setOpen(true);
                    load();
                }}
                onKeyDown={onKeyDown}
            />
            {showDropdown && (
                <div className={styles.dropdown} id="search-results">
                    <SearchFacets
                        groups={BOX_FACETS}
                        selection={selection}
                        onToggle={(group, value) => setSelection(toggled(selection, group, value))}
                        showReset={false}/>
                    <div id="search-results-list" role="listbox">{/* NOSONAR the combobox pattern: a select cannot hold these hits */}
                    {loading && !results && <div className={styles.message}>Searching…</div>}
                    {results?.length === 0 && (
                        <div className={styles.message}>
                            No page of {environment.label} matches <strong>{query}</strong>.
                        </div>
                    )}
                    {results?.map((result, index) => (
                        <button // NOSONAR a hit is a button in the combobox pattern, which an option element cannot be
                            type="button"
                            role="option"
                            aria-selected={index === active}
                            key={result.url}
                            className={clsx(styles.hit, index === active && styles.hitActive)}
                            onMouseEnter={() => setActive(index)}
                            onClick={() => go(result.url)}>
                            <span className={styles.hitTitle}>{titleOf(result)}</span>
                            <Kind source={result.filters?.source?.[0]}/>
                            <Where meta={result.meta}/>
                            <Excerpt excerpt={result.excerpt} className={styles.hitExcerpt}/>
                        </button>
                    ))}
                    {results && total > results.length && (
                        <button
                            type="button"
                            className={styles.more}
                            onClick={() => go(resultsPage())}>
                            See all {total} results
                        </button>
                    )}
                    </div>
                </div>
            )}
        </div>
    );
}
