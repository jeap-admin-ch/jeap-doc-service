import React from 'react';
import Head from '@docusaurus/Head';
import {useEnvironment} from '@site/src/data/environments';
import styles from './styles.module.css';

/**
 * Standing marker on every environment that is not the main one.
 *
 * Documentation describing DEV is actively misleading to a reader who believes they are looking at what runs in
 * production, so every other tree says plainly what it is. Those trees are also kept out of search engines: they
 * are public, but they are not the canonical documentation.
 */
export default function EnvironmentBanner() {
    const environment = useEnvironment();

    if (environment.main) {
        return null;
    }

    return (
        <>
            <Head>
                <meta name="robots" content="noindex, nofollow"/>
            </Head>
            <div className={styles.banner} role="note">
                <span className={styles.code}>{environment.short}</span>
                <span>
                    This is the documentation of the <strong>{environment.label}</strong> environment.
                </span>
            </div>
        </>
    );
}
