/**
 * A sidebar link, with the way out of a part kept in the tab the reader is in.
 *
 * The only such link the generator writes is "All systems", the escape point from a part that carries one
 * system - see `escapePointOf` in `docusaurus.config.js`. It is a `pathname://` href, because the systems
 * index belongs to the shell part and this build has no route for it, and Docusaurus therefore reads it as
 * an external link and opened it in a new tab.
 *
 * **Wrapped rather than copied.** The original does not pass a sidebar item's own extra keys to `Link`, so
 * `target` cannot be set on the item in `sidebars.js`; it does spread its *component* props, which is what
 * this hands the target through. Copying the component instead would mean owning its markup and its active
 * state across Docusaurus upgrades, for one attribute.
 *
 * The external-link icon the original adds beside such a link is still there. It is drawn inside the
 * component, so removing it is the copy this avoids - and an icon is not what a reader reported.
 */
import React from 'react';
import OriginalLink from '@theme-original/DocSidebarItem/Link';

const UNCHECKED = 'pathname://';

export default function DocSidebarItemLink(props) {
    const href = props.item && props.item.href;
    const staysOnThisSite = typeof href === 'string' && href.startsWith(UNCHECKED);
    return <OriginalLink {...props} {...(staysOnThisSite ? {target: '_self'} : {})} />;
}
