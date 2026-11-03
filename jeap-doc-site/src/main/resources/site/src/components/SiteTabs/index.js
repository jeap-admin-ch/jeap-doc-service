import React from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

/**
 * One tab per site of this instance, the current one marked, in the header bar - and on a narrow screen in the
 * navigation menu, where Docusaurus moves every item placed on the left.
 *
 * The tabs come from `site.json` through the navbar item that renders them. Every site is a Docusaurus build of
 * its own, so a tab is a plain link to the other site's root and never a route of this build: the browser loads
 * that site, and the build's link check, which knows only its own routes, never sees it.
 */
export default function SiteTabs({tabs, mobile}) {
    if (!Array.isArray(tabs) || tabs.length < 2) {
        return null;
    }
    if (mobile) {
        return (
            <>
                {tabs.map((tab) => (
                    <li className="menu__list-item" key={tab.url}>
                        <a
                            className={clsx('menu__link', tab.current && 'menu__link--active')}
                            href={tab.url}
                            aria-current={tab.current ? 'page' : undefined}>
                            {tab.label}
                        </a>
                    </li>
                ))}
            </>
        );
    }
    return (
        <nav className={styles.tabs} aria-label="Sites">
            {tabs.map((tab) => (
                <a
                    key={tab.url}
                    className={clsx('navbar__item', 'navbar__link', styles.tab,
                        tab.current && clsx('navbar__link--active', styles.current))}
                    href={tab.url}
                    aria-current={tab.current ? 'page' : undefined}>
                    {tab.label}
                </a>
            ))}
        </nav>
    );
}
