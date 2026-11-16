import React from 'react';
import Layout from '@theme/Layout';
import ReactionViewer from '../ReactionViewer';

const entry = {kind: 'EXPLORER'};

export default function ReactionExplorer() {
    return <Layout title="Reaction Graph Explorer">
        <main className="container margin-vert--lg">
            <h1>Reaction Graph Explorer</h1>
            <p>Explore events, components and interfaces, or find the paths that connect them.</p>
            <ReactionViewer entry={entry} explorer/>
        </main>
    </Layout>;
}
