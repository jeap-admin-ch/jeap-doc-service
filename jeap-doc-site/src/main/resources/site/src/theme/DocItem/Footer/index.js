import React from 'react';
import Footer from '@theme-original/DocItem/Footer';
import {useDoc} from '@docusaurus/plugin-content-docs/client';
import PageProvenance from '@site/src/components/PageProvenance';

/**
 * Puts the provenance of a page under it, above whatever the theme's own footer shows.
 *
 * Every page the doc service writes says in its front matter where it came from, so one component answers for
 * a generated page and for one a team uploaded - see `PageProvenance`.
 */
export default function FooterWrapper(props) {
    const {frontMatter} = useDoc();
    return (
        <>
            <PageProvenance frontMatter={frontMatter}/>
            <Footer {...props}/>
        </>
    );
}
