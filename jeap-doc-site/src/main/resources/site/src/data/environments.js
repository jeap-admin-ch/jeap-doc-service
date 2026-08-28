import {useLocation} from '@docusaurus/router';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import environments from '@site/content/environments.json';

/**
 * The environments of this site, in the order the switcher shows them.
 *
 * The file is written by the site generator before the build; nothing here decides what an environment is.
 */
export const ENVIRONMENTS = [...environments.environments].sort((a, b) => a.order - b.order);

/** The environment served at the site root - exactly one is marked, which the generator guarantees. */
export const MAIN_ENVIRONMENT = ENVIRONMENTS.find((environment) => environment.main);

/** `/dev`, and the empty string for the environment served at the site root. */
export function routePrefixOf(environment) {
    return environment.main ? '' : `/${environment.id}`;
}

/** Every prefix that is not the main environment's, longest first, so the check runs prefix-first. */
const PREFIXED = ENVIRONMENTS.filter((environment) => !environment.main)
    .map((environment) => ({environment, prefix: routePrefixOf(environment)}))
    .sort((a, b) => b.prefix.length - a.prefix.length);

/**
 * The environment a site-relative path belongs to. The main environment owns everything no prefix claims.
 */
export function environmentOfPath(sitePath) {
    const match = PREFIXED.find(({prefix}) => sitePath === prefix || sitePath.startsWith(`${prefix}/`));
    return match ? match.environment : MAIN_ENVIRONMENT;
}

/** Strips the environment prefix, leaving an environment-relative path. */
export function withoutEnvironment(sitePath) {
    const environment = environmentOfPath(sitePath);
    const prefix = routePrefixOf(environment);
    if (!prefix) {
        return sitePath;
    }
    const rest = sitePath.slice(prefix.length);
    return rest === '' ? '/' : rest;
}

/** The site-relative path of the current location, with the base url removed. */
export function useSitePath() {
    const {pathname} = useLocation();
    const {siteConfig: {baseUrl}} = useDocusaurusContext();
    return pathname.startsWith(baseUrl) ? `/${pathname.slice(baseUrl.length)}` : pathname;
}

/**
 * The environment the reader is in, derived from the URL rather than from a stored preference - so a shared
 * link always opens the environment it points at.
 */
export function useEnvironment() {
    return environmentOfPath(useSitePath());
}
