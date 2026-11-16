import React from 'react';
import FooterLinks from '@theme-original/Footer/Links';
import {isMultiColumnFooterLinks} from '@docusaurus/theme-common';
import {useExplorerHref} from '../../../components/ReactionExplorer/NavLink';

/** Filter before the theme creates list items or separators around links. */
export default function Links({links}) {
    const explorerHref = useExplorerHref();
    const available = item => explorerHref || item.className !== 'jeap-explorer-link';
    const visibleItems = items => items.filter(available).map(item => item.className === 'jeap-explorer-link'
        ? {...item, to: explorerHref} : item);
    const filtered = isMultiColumnFooterLinks(links)
        ? links.map(column => ({...column, items: visibleItems(column.items)}))
        : visibleItems(links);
    return <FooterLinks links={filtered}/>;
}
