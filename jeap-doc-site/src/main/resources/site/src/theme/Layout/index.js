import React from 'react';
import Layout from '@theme-original/Layout';
import {useLocation} from '@docusaurus/router';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import EnvironmentBanner from '@site/src/components/EnvironmentBanner';
import SearchEnvironmentSelector from '@site/src/components/SearchEnvironmentSelector';

/**
 * Puts the environment banner above everything, on every page - and the search page's environment selector
 * inside the layout, on the one page that has one.
 *
 * The selector is placed from here because the search page comes from the search plugin and renders its own
 * layout: wrapping that component can only add something above the navbar or below the footer, while this
 * receives the page itself as `children` and can put something in front of it. There is one search page, at
 * the site root, which is what the path test below is.
 */
export default function LayoutWrapper(props) {
    const {siteConfig: {baseUrl}} = useDocusaurusContext();
    const {pathname} = useLocation();
    const isSearchPage = pathname === `${baseUrl}search` || pathname === `${baseUrl}search/`;

    return (
        <>
            <EnvironmentBanner/>
            <Layout {...props}>
                {isSearchPage && (
                    // The search page centres itself in a container of its own, and the selector belongs with
                    // it rather than flush against the edge of the viewport.
                    <div className="container">
                        <SearchEnvironmentSelector/>
                    </div>
                )}
                {props.children}
            </Layout>
        </>
    );
}
