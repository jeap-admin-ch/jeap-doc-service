import React from 'react';
import Layout from '@theme-original/Layout';
import EnvironmentBanner from '@site/src/components/EnvironmentBanner';

/** Puts the environment banner above everything, on every page. */
export default function LayoutWrapper(props) {
    return (
        <>
            <EnvironmentBanner/>
            <Layout {...props} />
        </>
    );
}
