/**
 * The client of the architecture repository: what the import replicates the landscape from.
 * <p>
 * The adapter behind the three upstream ports -
 * {@link ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUpstream},
 * {@link ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactUpstream} and
 * {@link ch.admin.bit.jeap.doc.domain.port.MessageSchemaUpstream}. A build reads nothing from here: it reads
 * what was imported, through {@link ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource}, which the
 * domain implements over this service's own database.
 * <p>
 * It knows the routes and the payloads of the architecture repository, and nothing about pages, Markdown or
 * arc42 - it answers with the doc service's own domain records.
 * <p>
 * <b>How a request is made is not here.</b> The client, the bounded conditional read, the redirect rule, the
 * entity tag and the retry policy are {@code jeap-doc-upstream}'s, shared with
 * {@code jeap-doc-reactionobserver}, and neither adapter may keep a copy of them.
 */
package ch.admin.bit.jeap.doc.archrepo;
