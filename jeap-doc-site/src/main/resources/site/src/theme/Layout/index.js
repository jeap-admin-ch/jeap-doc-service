import React from 'react';
import Layout from '@theme-original/Layout';
import SnapshotProvider from '@site/src/components/ReactionViewer/SnapshotProvider';
import EnvironmentBanner from '@site/src/components/EnvironmentBanner';

/**
 * Puts the environment banner above everything, on every page.
 */
export default function LayoutWrapper(props) {
    return (
        <>
            <EnvironmentBanner/>
            <SnapshotProvider><Layout {...props}/></SnapshotProvider>
        </>
    );
}
