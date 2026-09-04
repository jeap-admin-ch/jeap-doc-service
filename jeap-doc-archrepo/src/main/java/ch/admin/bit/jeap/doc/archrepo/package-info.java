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
 * It knows HTTP, OAuth2 and the payloads of the architecture repository. It knows nothing about pages, Markdown
 * or arc42, and answers with the doc service's own domain records.
 */
package ch.admin.bit.jeap.doc.archrepo;
