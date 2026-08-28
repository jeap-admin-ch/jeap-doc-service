import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import {routePrefixOf, useEnvironment, useSitePath, withoutEnvironment} from '@site/src/data/environments';

/**
 * A navbar link that stays inside the environment the reader is in, and highlights itself when the reader is
 * somewhere below it. Docusaurus' own `to` would always resolve against the site root, which is the main
 * environment - a reader on DEV would be dropped back into PROD by the navbar.
 */
export default function EnvNavLink({to, label, mobile}) {
    const environment = useEnvironment();
    const relative = withoutEnvironment(useSitePath());
    const active = relative === to || relative.startsWith(to);

    return (
        <Link
            className={clsx(mobile ? 'menu__link' : 'navbar__item navbar__link', active && 'navbar__link--active')}
            to={`${routePrefixOf(environment)}${to}`}>
            {label}
        </Link>
    );
}
