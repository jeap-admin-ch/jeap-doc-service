import React from 'react';
import FooterLinkItem from '@theme-original/Footer/LinkItem';
import Link from '@docusaurus/Link';

export default function LinkItem({item}) {
    if (item.className === 'jeap-explorer-link') {
        return <Link to={item.to} target="_self" className="footer__link-item">{item.label}</Link>;
    }
    return <FooterLinkItem item={item}/>;
}
