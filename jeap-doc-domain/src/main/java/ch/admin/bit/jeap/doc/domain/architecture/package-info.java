/**
 * The architecture model, as the documentation generator reads it.
 * <p>
 * These are the doc service's own types, shaped for what a page needs, not the payloads of the architecture
 * repository's API. The mapping lives in the adapter that fetches it. Nothing here knows about HTTP or about
 * Markdown.
 * <p>
 * <b>Nothing here knows that it was imported either.</b> The replication lives in
 * {@link ch.admin.bit.jeap.doc.domain.architecture.imports} and depends on this package; this package does not
 * depend on it, and that direction is what keeps a change to how the landscape is fetched out of the records a
 * page is written from. The views computed across the landscape are in
 * {@link ch.admin.bit.jeap.doc.domain.architecture.view}.
 */
package ch.admin.bit.jeap.doc.domain.architecture;
