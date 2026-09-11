import React from 'react';
import styles from './styles.module.css';

/**
 * Where a page came from, under the page.
 *
 * Read from the page's own front matter, which every page the doc service writes carries: `doc_status` says
 * whether the page was generated or written by a team, `doc_source` says what it was generated from, and the
 * rest says which model or which commit. A page with neither - the site's own pages - renders nothing.
 *
 * It is one component for both kinds on purpose. That the two are told apart at all is the point of
 * `doc_status`, and a reader sees the same block in the same place whichever one they are on.
 */
export default function PageProvenance({frontMatter}) {
    if (!frontMatter?.doc_status) {
        return null;
    }
    const custom = frontMatter.doc_status === 'custom';
    return (
        <aside className={styles.provenance} aria-label="Where this page came from">
            <span className={custom ? styles.custom : styles.generated}>
                {custom ? 'Written by the team' : 'Generated page'}
            </span>
            {custom ? <CustomSource frontMatter={frontMatter}/> : <GeneratedSource frontMatter={frontMatter}/>}
        </aside>
    );
}

/** A page a team uploaded: the repository it was written in, and the commit it was built from. */
function CustomSource({frontMatter}) {
    const repository = frontMatter.doc_source_repository;
    const ref = frontMatter.doc_source_ref;
    const revision = frontMatter.doc_source_revision;
    const version = frontMatter.doc_version;
    const uploaded = frontMatter.doc_uploaded_at_display || frontMatter.doc_uploaded_at;
    return (
        <span className={styles.detail}>
            {' from '}<code>{repository}</code>
            {ref ? <>{' on '}<code>{ref}</code></> : null}
            {revision ? <>{' at '}<code>{revision}</code></> : null}
            {version ? <>{', version '}<code>{version}</code></> : null}
            {uploaded ? <>{', uploaded '}{uploaded}</> : null}
            {'.'}
        </span>
    );
}

/**
 * A page this service wrote: from the architecture model of one environment, or - where `doc_source` says
 * `doc-service` - from the service's own configuration and records.
 *
 * The two are told apart, because saying <em>from the architecture model</em> on a page the model had no part
 * in is simply false: the page describing the documentation and the systems index are both written from what
 * this service holds about itself.
 *
 * The date falls back to when the page was generated. A page that names no import has none to name.
 */
function GeneratedSource({frontMatter}) {
    const environment = frontMatter.doc_environment;
    const imported = frontMatter.doc_model_imported_at;
    const generated = frontMatter.doc_generated_at_display || frontMatter.doc_generated_at;
    const fromTheModel = frontMatter.doc_source !== 'doc-service';
    // The one date this page can name: when the model it was written from was imported, or failing that when
    // the page itself was generated. A page that names neither shows none.
    let when = null;
    if (imported) {
        when = {what: 'imported', at: imported};
    } else if (generated) {
        when = {what: 'generated', at: generated};
    }
    return (
        <span className={styles.detail}>
            {fromTheModel
                ? ' by the jEAP Doc Service from the architecture model'
                : ' by the jEAP Doc Service from its own configuration and records'}
            {environment ? <>{' of the '}<strong>{environment}</strong>{' environment'}</> : null}
            {when ? <>{', '}{when.what}{' '}{when.at}</> : null}
            {'.'}
        </span>
    );
}
