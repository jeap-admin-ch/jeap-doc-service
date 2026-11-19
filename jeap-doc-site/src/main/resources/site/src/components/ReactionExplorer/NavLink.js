import React, {useEffect, useState} from 'react';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import useIsBrowser from '@docusaurus/useIsBrowser';
import {useLocation} from '@docusaurus/router';
import {environmentById, routePrefixOf, useEnvironment, useSitePath, withoutEnvironment} from '../../data/environments';
import styles from './styles.module.css';
import {publishedExplorerAvailable} from './availability.mjs';

/** Keep both navigation entries in the reader's current site and environment. */
export function useExplorerHref() {
    const fromPath = useEnvironment(), relative = withoutEnvironment(useSitePath());
    const {pathname, search} = useLocation();
    const fromSearch = useIsBrowser() && /^\/search\/?$/.test(relative);
    const environment = fromSearch ? environmentById(new URLSearchParams(search).get('env')) : fromPath;
    const {siteConfig: {baseUrl, customFields: {docSite, reactionEnvironments = []}}} = useDocusaurusContext();
    const prefix = environment && reactionEnvironments.includes(environment.id)
        ? `${baseUrl.replace(/\/$/, '')}${routePrefixOf(environment)}` : null;
    const environmentId = environment?.id;
    const [publication, setPublication] = useState(null);
    useEffect(() => {
        if (prefix === null) return;
        let active = true;
        publishedExplorerAvailable(`${prefix}/reaction-explorer.json`, `${prefix}/reactions/`, docSite, environmentId)
            .then(available => { if (active) setPublication({prefix, available}); });
        return () => { active = false; };
    }, [prefix, docSite, environmentId, pathname, search]);
    if (prefix === null || publication?.prefix !== prefix || !publication.available) return null;
    return `pathname://${prefix}/reactions/`;
}

export default function ExplorerNavLink({mobile, onClick}) {
    const href = useExplorerHref();
    if (!href) return null;
    const link = <Link to={href} target="_self" className={mobile ? 'menu__link' : `navbar__item navbar__link ${styles.iconLink}`}
        aria-label="Reaction Graph Explorer" onClick={onClick}>
        {mobile ? 'Reaction Graph Explorer' : <>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
                strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                <path d="M6 6l12 1M6 6l5 12M18 7l-7 11"/>
                <circle cx="6" cy="6" r="3" fill="var(--ifm-navbar-background-color)"/>
                <circle cx="18" cy="7" r="3" fill="var(--ifm-navbar-background-color)"/>
                <circle cx="11" cy="18" r="3" fill="var(--ifm-navbar-background-color)"/>
            </svg>
            <span className={styles.tooltip} aria-hidden="true">Reaction Graph Explorer</span>
        </>}
    </Link>;
    return mobile ? <li className="menu__list-item">{link}</li> : link;
}
