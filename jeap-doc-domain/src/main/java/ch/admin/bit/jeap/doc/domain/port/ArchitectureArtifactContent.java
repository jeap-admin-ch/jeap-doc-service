package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;

import java.util.Optional;

/**
 * Reads the bytes of a replicated artifact into what a page shows.
 * <p>
 * A port because parsing needs a JSON mapper, which neither the domain nor a structure template may hold.
 * Both formats belong to the architecture repository, so the adapter is in {@code jeap-doc-archrepo}.
 * <p>
 * <b>Neither method throws.</b> An unreadable artifact answers empty and is logged. There is no {@code try}
 * around the generation of a site, so one that threw would cost every system of the environment its
 * documentation instead of costing one page.
 */
public interface ArchitectureArtifactContent {

    /** The database schema of an artifact, or empty when its bytes are not one. */
    Optional<DatabaseSchema> databaseSchema(ArchitectureArtifact artifact);

    /** The overview of an OpenAPI specification, or empty when its bytes are not one. */
    Optional<RestApiOverview> restApi(ArchitectureArtifact artifact);
}
