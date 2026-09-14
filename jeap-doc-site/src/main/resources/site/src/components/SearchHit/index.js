import React from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

/**
 * The two things a search result shows besides its title, shared by the navbar box and the results page.
 *
 * They are shared because they have to agree: a reader who recognises a hit in the dropdown and then opens the
 * results page is looking at the same corpus, and a location or a highlight that read differently in the two
 * places would be read as two different hits.
 */

/**
 * What a result is called: its title as the reader should see it, and its URL for a page that has no title.
 *
 * The title goes through the same unescaping as the excerpt - a generated page about a message payload really
 * is called {@code List&lt;Order&gt;} in its front matter, and a result showing that is a result showing its
 * own escaping.
 */
export function titleOf(result) {
    return result.meta?.title ? decoded(result.meta.title) : result.url;
}

/**
 * <b>What kind of documentation a result is</b>, as a badge beside its title.
 *
 * It is what connects the filters to the list: without it a reader cannot see why a result disappeared when
 * they turned a chip off. The words are the chips' own, so the two can only ever say the same thing.
 */
export function Kind({source}) {
    const label = KINDS[source];
    if (!label) {
        return null;
    }
    return <span className={clsx(styles.kind, styles[source])}>{label}</span>;
}

const KINDS = {generated: 'Generated', markdown: 'Uploaded', html: 'HTML'};

/**
 * <b>Where the hit is.</b>
 *
 * A generated page's title is its chapter - "Component Architecture", "3. Context and Scope" - which on a site
 * of fifty components says nothing at all about which of them the reader has found. The system, the component
 * or library the page documents, and the uploaded microsite it was found inside are what make a result
 * identifiable, and the index carries all three as meta.
 *
 * A page of the site itself belongs to none of them and gets nothing rather than an empty line.
 *
 * `component` is read as well as `name`, because an index published by an older version of the service is
 * served by this template until the site is next built.
 */
export function Where({meta}) {
    const trail = [meta?.system, meta?.name ?? meta?.component, meta?.microsite].filter(Boolean);
    if (trail.length === 0) {
        return null;
    }
    return (
        <span className={styles.where}>
            {trail.map((step, index) => (
                <React.Fragment key={step}>
                    {index > 0 && <span className={styles.separator} aria-hidden="true">›</span>}
                    <span className={index === trail.length - 1 ? styles.innermost : undefined}>{step}</span>
                </React.Fragment>
            ))}
        </span>
    );
}

/**
 * The excerpt with the matched words marked, <b>built as elements rather than injected as HTML</b>.
 *
 * The excerpt is the text of a page, and half the pages on this site were uploaded by whoever wrote the
 * documentation rather than generated - which is the same reason the site is built with MDX switched off.
 * Splitting on the one tag the index emits keeps that content text, whatever it contains.
 *
 * The marks are what a reader sees of why a page matched, so they are coloured rather than left to the
 * browser's default - see `styles.module.css`, which keeps them legible in both colour modes.
 */
export function Excerpt({excerpt, className}) {
    if (!excerpt) {
        return null;
    }
    return (
        <span className={clsx(styles.excerpt, className)}>
            {excerpt.split(/<mark>|<\/mark>/).map((piece, index) =>
                (index % 2 === 1
                    // The pieces of one excerpt never move, so where a piece is is what it is.
                    ? <mark key={index}>{decoded(piece)}</mark> // NOSONAR
                    : <React.Fragment key={index}>{decoded(piece)}</React.Fragment> // NOSONAR
                ))}
        </span>
    );
}

/**
 * Character references back as themselves.
 *
 * Two things escape on the way here and both have to come off, or a reader sees the escaping instead of the
 * text. The generator writes `&lt;`, `&#123;` and their kind into a page, because a title is read as MDX
 * where a brace is code; and the index escapes what it hands back, because an excerpt is HTML to everything
 * but this component. This documentation is full of `<T>`, `&` and `{...}`, so both are common.
 *
 * The named references are the five HTML defines without a document; a numeric one is whatever it says.
 */
const NAMED = {amp: '&', lt: '<', gt: '>', quot: '"', apos: "'"};

function decoded(text) {
    return text.replaceAll(/&(#\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);/g, (reference, name) => {
        if (!name.startsWith('#')) {
            return NAMED[name] ?? reference;
        }
        const hexadecimal = name[1] === 'x' || name[1] === 'X';
        const code = Number.parseInt(hexadecimal ? name.slice(2) : name.slice(1), hexadecimal ? 16 : 10);
        // A reference outside the code points there are is not a character - and it would throw rather than
        // render. Uploaded documentation is what this reads, so it may say anything.
        return Number.isInteger(code) && code >= 0 && code <= 0x10ffff
            ? String.fromCodePoint(code) : reference;
    });
}
