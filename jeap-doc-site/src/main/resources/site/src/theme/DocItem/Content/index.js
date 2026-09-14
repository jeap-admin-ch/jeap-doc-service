import React, {useCallback, useEffect, useRef, useState} from 'react';
import Content from '@theme-original/DocItem/Content';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useDoc} from '@docusaurus/plugin-content-docs/client';
import {useColorMode} from '@docusaurus/theme-common';
import {useLocation} from '@docusaurus/router';
import styles from './styles.module.css';

/**
 * Shows an uploaded HTML microsite in a frame, in place of the page's body.
 *
 * A page the doc service generated for a microsite carries `doc_microsite_url` in its front matter and nothing
 * else: the documentation itself is served from its own prefix, with an opaque origin and a policy that
 * sandboxes it. Every other page renders as it always did.
 */
export default function ContentWrapper(props) {
    const {frontMatter} = useDoc();
    if (!frontMatter?.doc_microsite_url) {
        return <Content {...props}/>;
    }
    return <Microsite frontMatter={frontMatter} {...props}/>;
}

/** The largest a microsite may make its own frame: four screens, however tall it says it is. */
const MAX_HEIGHT_FACTOR = 4;

/** What a microsite may send its page, and the only message this listens for. */
const HEIGHT_MESSAGE = 'jeap-doc-microsite-height';

function Microsite({frontMatter, children}) {
    const {siteConfig: {baseUrl}} = useDocusaurusContext();
    const {colorMode} = useColorMode();
    const location = useLocation();
    const frame = useRef(null);
    const [height, setHeight] = useState(null);
    // Only in the browser. The page is pre-rendered without the query of the link that opened it, and React
    // keeps an attribute the server rendered instead of correcting it while it hydrates - so a frame rendered
    // on the server keeps pointing at the entry point, and `?path=` works only when something happens to
    // re-render the page afterwards, a dark colour mode for one. Rendered after mounting, it has one source.
    const [mounted, setMounted] = useState(false);
    useEffect(() => setMounted(true), []);

    const label = frontMatter.doc_microsite_label || frontMatter.title || 'this documentation';
    const source = sourceOf(frontMatter.doc_microsite_url, baseUrl, location.search, colorMode);

    /**
     * The height the microsite asked for.
     *
     * Only from this page's own frame - a message from any other window is ignored - and never more than a
     * few screens: a microsite could otherwise make itself arbitrarily tall. One that says nothing keeps the
     * viewport-high frame it started with.
     */
    const onMessage = useCallback((event) => {
        if (!frame.current || event.source !== frame.current.contentWindow) {
            return;
        }
        const message = event.data;
        if (message?.type !== HEIGHT_MESSAGE || typeof message.height !== 'number') {
            return;
        }
        const cap = window.innerHeight * MAX_HEIGHT_FACTOR;
        setHeight(Math.max(240, Math.min(message.height, cap)));
    }, []);

    useEffect(() => {
        window.addEventListener('message', onMessage);
        return () => window.removeEventListener('message', onMessage);
    }, [onMessage]);

    return (
        <div className={styles.microsite}>
            <div className={styles.controls}>
                {/* The page's own body, which the generator writes and which already says who published this. */}
                <span className={styles.note}>{children}</span>
                <span className={styles.actions}>
                    {/* After mounting, like the frame: a server-rendered href would keep the entry point. */}
                    {mounted && <a href={source} target="_blank" rel="noreferrer noopener">Open in a new tab</a>}
                    <button type="button" onClick={() => fullScreen(frame.current)}>Full screen</button>
                </span>
            </div>
            {!mounted && <div className={styles.frame} aria-hidden="true"/>}
            {mounted && <iframe
                /*
                 * A new element whenever the source changes, rather than the same one re-pointed: a toggle of
                 * the colour mode changes the source, and re-pointing an element leaves the load it started
                 * in flight, which can finish afterwards and replace the document.
                 */
                key={source}
                ref={frame}
                title={label}
                src={source}
                className={styles.frame}
                style={height ? {height: `${height}px`} : undefined}
                /* Never allow-same-origin: with it a framed page could remove this attribute and reload. */
                sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox allow-downloads"
                allow=""
                referrerPolicy="no-referrer"
                loading="lazy"
            />}
        </div>
    );
}

/**
 * Where the frame points: the microsite's entry point, or a page inside it named by `?path=`.
 *
 * **Resolved against this site's base URL**, which is the only thing that knows where the documentation is
 * served from. The front matter carries the path within the service - `/microsites/…` - because that is what
 * the service resolves a request by; the base adds whatever stands in front of it, the context path the
 * service is deployed under and the `/site/<id>/` of a site that is not the default one. Taking the front
 * matter as a URL from the host root pointed the frame at a path that exists only when the service is at the
 * root of its host and the site is the default one.
 *
 * The path is checked to be relative and free of `..` before it is used - it comes from a URL a reader can
 * edit - and anything else falls back to the entry point. `?theme=dark` is set once, when the frame is first
 * rendered: re-setting it on a toggle would reload the frame and lose the reader's place.
 */
function sourceOf(url, baseUrl, search, colorMode) {
    const inside = insidePathOf(search);
    const theme = colorMode === 'dark' ? '?theme=dark' : '';
    const served = (baseUrl ?? '/') + String(url ?? '').replace(/^\/+/, '');
    return served + inside + theme;
}

function insidePathOf(search) {
    const asked = new URLSearchParams(search).get('path');
    if (!asked) {
        return '';
    }
    const normalized = asked.replace(/^\/+/, '');
    const unsafe = normalized.split('/').some((segment) => segment === '..' || segment === '.')
        || normalized.includes('\\') || normalized.includes('//');
    return unsafe ? '' : normalized;
}

/** Full screen is performed by the page on the frame: the microsite is never granted `allow="fullscreen"`. */
function fullScreen(frame) {
    if (frame?.requestFullscreen) {
        frame.requestFullscreen();
    }
}
