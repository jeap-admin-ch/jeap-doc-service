import React from 'react';
import {useHistory, useLocation} from '@docusaurus/router';
import useIsBrowser from '@docusaurus/useIsBrowser';
import {ENVIRONMENTS} from '@site/src/data/environments';
import styles from './styles.module.css';

/**
 * Chooses which environment the search page searches.
 *
 * The search index is split one bucket per environment, and the navbar picks the bucket from the page the
 * reader is on. The search page cannot do that - it is one page at the site root, so it takes the bucket from
 * the `ctx` query parameter instead, and this is what writes it.
 *
 * The search plugin renders a selector of its own for this, and it is hidden (see styles.module.css) rather
 * than used: it lists only the buckets that have a path, so the main environment - which is the leftover
 * bucket and therefore has none - is missing from it. A reader who searched from the main environment would
 * find a control showing nothing, and one who moved away from it could not get back. This one lists every
 * environment, and it says *environment*, which is what the rest of the site calls them.
 */
export default function SearchEnvironmentSelector() {
    const history = useHistory();
    const location = useLocation();
    // The search page is prerendered without a query string, so the scope is read only once there is a browser
    // to read it from - the same guard the plugin puts on the same value.
    const isBrowser = useIsBrowser();
    const chosen = isBrowser ? new URLSearchParams(location.search).get('ctx') ?? '' : '';
    // An id nothing configures - a link kept after an environment was renamed - would leave this control blank,
    // which is the very thing it exists to prevent. The plugin resolves an unknown scope to the leftover bucket,
    // so this shows what is actually being searched.
    const selected = ENVIRONMENTS.some((environment) => !environment.main && environment.id === chosen)
        ? chosen
        : '';

    function choose(value) {
        const parameters = new URLSearchParams(location.search);
        if (value) {
            parameters.set('ctx', value);
        } else {
            parameters.delete('ctx');
        }
        // Replace rather than push: choosing an environment is refining the search that is on the screen, not
        // a step of its own to walk back through.
        history.replace({search: parameters.toString()});
    }

    // A site with one environment has nothing to choose between, and the search index is not split at all -
    // the same reason the environment switcher takes itself off such a site.
    if (ENVIRONMENTS.length < 2) {
        return null;
    }

    return (
        <div className={styles.selector}>
            <label className={styles.label} htmlFor="search-environment">Environment</label>
            <select
                id="search-environment"
                className={styles.input}
                value={selected}
                onChange={(event) => choose(event.target.value)}>
                {ENVIRONMENTS.map((environment) => (
                    <option key={environment.id} value={environment.main ? '' : environment.id}>
                        {environment.label}
                    </option>
                ))}
            </select>
        </div>
    );
}
