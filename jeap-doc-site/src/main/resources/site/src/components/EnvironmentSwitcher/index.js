import React, {useCallback, useRef, useState} from 'react';
import Link from '@docusaurus/Link';
import clsx from 'clsx';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {ENVIRONMENTS, routePrefixOf, useEnvironment, useSitePath, withoutEnvironment} from '@site/src/data/environments';
import styles from './styles.module.css';

/**
 * The environment switcher of the navbar.
 *
 * It keeps the reader on the page they are reading: a reader who switches from PROD to DEV almost always wants
 * the same page on DEV rather than its start page.
 *
 * Whether that page exists over there is a question this component must not answer. The environments hold
 * different documentation by design - a component documented on DEV need not exist in production - so the
 * switcher offers the same path and lets the target's own 404 answer when it is not there. The links are
 * therefore written with Docusaurus' `pathname://` protocol, which renders a plain anchor and takes the target
 * out of the build's broken-link check: without it a site whose environments differ at all could not be built,
 * and `onBrokenLinks: 'throw'` is worth far more on the generated pages than it would cost here.
 */
export default function EnvironmentSwitcher({mobile, onClick}) {
    const current = useEnvironment();
    const [open, setOpen] = useState(false);
    const wrapper = useRef(null);

    // Hover alone leaves the switcher unusable with a keyboard and on a tablet, which has no hover at all - and
    // it is the only navigation control in the navbar. Opening it is therefore state, as it is in the theme's
    // own dropdown, and `aria-expanded` says what is actually true.
    //
    // The handlers sit on the trigger and on the links rather than on the wrapper around them: a div carrying
    // them is an interactive element that is not one. Closing on focus leaving still works from either, because
    // it asks whether focus went anywhere inside the wrapper.
    const closeOnEscape = useCallback((event) => {
        if (event.key === 'Escape') {
            setOpen(false);
        }
    }, []);
    const closeWhenFocusLeaves = useCallback((event) => {
        if (!wrapper.current?.contains(event.relatedTarget)) {
            setOpen(false);
        }
    }, []);
    const relative = withoutEnvironment(useSitePath());
    const {siteConfig: {baseUrl}} = useDocusaurusContext();

    /** The same page in another environment, as an unchecked absolute path including the base url. */
    const hrefFor = (environment) => `pathname://${baseUrl.replace(/\/$/, '')}${routePrefixOf(environment)}${relative}`;

    if (ENVIRONMENTS.length < 2) {
        return null; // a site with one environment has nothing to switch between
    }

    if (mobile) {
        // In the sidebar this is a menu entry, not a hover dropdown: an absolutely positioned menu inside a
        // scrolling list has nowhere to open, and taking a link has to close the sidebar behind it.
        return (
            <li className="menu__list-item">
                <span className={clsx('menu__link', 'menu__link--sublist', styles.mobileHeading)}>
                    Environment
                </span>
                <ul className="menu__list">
                    {ENVIRONMENTS.map((environment) => (
                        <li className="menu__list-item" key={environment.id}>
                            <Link
                                className={clsx('menu__link', environment.id === current.id && 'menu__link--active')}
                                to={hrefFor(environment)}
                                target="_self"
                                onClick={onClick}
                                aria-current={environment.id === current.id ? 'true' : undefined}>
                                <span className={clsx(styles.code, environment.main && styles.codeMain)}>
                                    {environment.short}
                                </span>
                                {' '}
                                {environment.label}
                            </Link>
                        </li>
                    ))}
                </ul>
            </li>
        );
    }

    return (
        <div
            ref={wrapper}
            className={clsx('navbar__item', 'dropdown', 'dropdown--hoverable', 'dropdown--right',
                open && 'dropdown--show', styles.switcher)}>
            <button
                className={clsx('navbar__link', styles.trigger)}
                aria-haspopup="true"
                aria-expanded={open}
                aria-label={`Environment: ${current.label}. Switch environment`}
                onClick={() => setOpen((wasOpen) => !wasOpen)}
                onKeyDown={closeOnEscape}
                onBlur={closeWhenFocusLeaves}
                type="button">
                <span className={clsx(styles.code, current.main && styles.codeMain)}>{current.short}</span>
                <span className={styles.triggerLabel}>{current.label}</span>
            </button>
            <ul className="dropdown__menu">
                {ENVIRONMENTS.map((environment) => (
                    <li key={environment.id}>
                        <Link
                            className={clsx('dropdown__link', styles.option)}
                            to={hrefFor(environment)}
                            // `pathname://` makes Docusaurus treat the link as external, which would open the
                            // other environment in a new tab. Switching environment is navigation, not a
                            // detour: same tab, same page, other tree.
                            target="_self"
                            onClick={() => setOpen(false)}
                            onKeyDown={closeOnEscape}
                            onBlur={closeWhenFocusLeaves}
                            aria-current={environment.id === current.id ? 'true' : undefined}>
                            <span className={clsx(styles.code, environment.main && styles.codeMain)}>
                                {environment.short}
                            </span>
                            <span className={styles.optionLabel}>{environment.label}</span>
                        </Link>
                    </li>
                ))}
            </ul>
        </div>
    );
}
