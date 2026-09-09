import React, {useCallback, useEffect, useRef, useState} from 'react';
import Layout from '@theme/Layout';
import BrowserOnly from '@docusaurus/BrowserOnly';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useHistory, useLocation} from '@docusaurus/router';
import {environmentById} from '@site/src/data/environments';
import {Excerpt, titleOf, Where} from '@site/src/components/SearchHit';
import styles from './styles.module.css';

/**
 * The full results of a search, at {@code /search}.
 *
 * <b>The query is the URL</b>, both halves of it: {@code q} is what is searched and {@code env} is the tree it
 * is searched in. Typing in the box on this page rewrites {@code q}, so a result set is always a link somebody
 * can share.
 *
 * The environment is <b>not</b> chosen here. It comes from {@code env}, which the navbar's box puts into the
 * link it offers and the navbar's environment switcher rewrites - one switcher for the whole site, this page
 * included, rather than a second control saying the same thing twice.
 *
 * It belongs to the shell part of the site - the only build that owns the site root - so every other part
 * links here with an unchecked absolute link, the way it links the front page.
 *
 * <b>Its results are plain anchors</b>, and that is not an oversight. The index is one over the whole site
 * while a site is published as one build per part, so a result is routinely a page this build has no route
 * for; a {@code <Link>} would hand it to the router, which would answer with this build's own "Page Not
 * Found". It is the same rule the generator follows when it rewrites a link that leaves a part to
 * {@code pathname://}: crossing into another build is a page load.
 */
export default function SearchPage() {
    return (
        <Layout title="Search">
            <BrowserOnly>{() => <Results/>}</BrowserOnly>
        </Layout>
    );
}

/** How long the box waits after a keystroke before it rewrites the URL, as the navbar's box waits to search. */
const DEBOUNCE_MS = 150;

function Results() {
    const {siteConfig: {baseUrl}} = useDocusaurusContext();
    const location = useLocation();
    const history = useHistory();

    const parameters = new URLSearchParams(location.search);
    const query = parameters.get('q') ?? '';
    const environment = environmentById(parameters.get('env'));

    // What is in the box, which is not yet what is in the URL: the URL follows a moment later, so that typing
    // does not rewrite it a letter at a time.
    const [typed, setTyped] = useState(query);
    const [results, setResults] = useState(null);
    const [state, setState] = useState('idle');
    const latest = useRef(0);

    // A query that arrives in the URL from somewhere else - a link, the back button, the navbar's box - is
    // what the box shows.
    useEffect(() => setTyped(query), [query]);

    const search = useCallback(async () => {
        if (!query) {
            setResults(null);
            setState('idle');
            return;
        }
        // Which run this is, so that a slower search of an earlier query cannot overwrite a faster one of a
        // later query - the guard the navbar's box has, and one this page needs as much now that it has a box.
        const run = ++latest.current;
        setResults(null);
        setState('searching');
        try {
            const module = await import(/* webpackIgnore: true */ `${baseUrl}pagefind/pagefind.js`);
            await module.options({baseUrl});
            const found = await module.search(query, {filters: {environment: [environment.id]}});
            if (run !== latest.current) {
                return;
            }
            const shown = await Promise.all(found.results.map((result) => result.data()));
            if (run !== latest.current) {
                return;
            }
            setResults(shown);
            setState('done');
        } catch (e) {
            if (run === latest.current) {
                setState('unavailable');
            }
        }
    }, [baseUrl, query, environment.id]);

    useEffect(() => {
        search();
    }, [search]);

    // The URL follows the box, replacing rather than pushing: a reader refining a query wants their back
    // button to leave the search, not to walk back through every letter of it.
    useEffect(() => {
        if (typed === query) {
            return undefined;
        }
        const timer = setTimeout(() => {
            const next = new URLSearchParams(location.search);
            if (typed) {
                next.set('q', typed);
            } else {
                next.delete('q');
            }
            history.replace(`${location.pathname}?${next.toString()}`);
        }, DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [typed, query, history, location.pathname, location.search]);

    return (
        <div className="container margin-vert--lg">
            <h1>Search</h1>

            <div className={styles.controls}>
                <input
                    className={styles.input}
                    type="search"
                    autoFocus
                    aria-label={`Search the documentation of ${environment.label}`}
                    placeholder={`Search ${environment.label}`}
                    value={typed}
                    onChange={(event) => setTyped(event.target.value)}/>
            </div>

            {!query && <p>Type in the box to find a page.</p>}
            {state === 'unavailable' && (
                <p>This documentation has not been indexed yet, so there is nothing to search.</p>
            )}
            {state === 'searching' && <p>Searching…</p>}
            {state === 'done' && results?.length === 0 && (
                <p>No page of {environment.label} matches <strong>{query}</strong>.</p>
            )}
            {state === 'done' && results?.length > 0 && (
                <>
                    <p className={styles.count}>
                        {results.length} page(s) of {environment.label} match <strong>{query}</strong>.
                    </p>
                    <ul className={styles.results}>
                        {results.map((result) => (
                            <li key={result.url} className={styles.result}>
                                <a href={result.url} className={styles.resultTitle}>
                                    {titleOf(result)}
                                </a>
                                <Where meta={result.meta}/>
                                <Excerpt excerpt={result.excerpt} className={styles.resultExcerpt}/>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </div>
    );
}
