/**
 * A Markdown link, with a link into another part of this site kept in the tab the reader is in.
 *
 * A site is published as several Docusaurus builds - a part per system and a shell for the site's own pages -
 * and a link that leaves a part cannot be checked against the routes of the build that writes it. The
 * generator rewrites those to Docusaurus' `pathname://`, which leaves `onBrokenLinks: 'throw'` alone and
 * renders a plain anchor.
 *
 * **`pathname://` is a protocol, and Docusaurus reads any protocol as "not this site".** `@docusaurus/Link`
 * computes `isInternal` from the href *before* stripping the protocol, and adds `target="_blank"` to
 * everything it finds external. So every link from the systems index to a system, from a system's context
 * view to its neighbour, and from a system back to the shell opened a new tab - which is what a reader
 * reported as the links being broken.
 *
 * `target="_self"` puts that right. `Link` spreads the props it is given *after* its own target, so ours
 * wins; the navigation is still a full page load, which is what crossing into another build has to be.
 */
import React from 'react';
import OriginalA from '@theme-original/MDXComponents/A';

/** What the generator rewrites a link that leaves its part to - see `CrossPartLinks` in the doc service. */
const UNCHECKED = 'pathname://';

export default function A(props) {
    const staysOnThisSite = typeof props.href === 'string' && props.href.startsWith(UNCHECKED);
    return <OriginalA {...props} {...(staysOnThisSite ? {target: '_self'} : {})} />;
}
