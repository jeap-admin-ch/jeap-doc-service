package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * That a component has published a database schema, and where it can be read.
 * <p>
 * The reference, not the schema. The entity relationship diagram is drawn from the replicated copy, in
 * {@link ComponentArtifacts}; this only says that a schema exists, which is what the component's page in the
 * system's tree shows before anything has been replicated.
 *
 * @param schemaVersion the version of the schema
 * @param contentUrl    where the schema is read from, relative to the architecture repository
 */
public record DatabaseSchemaReference(String schemaVersion, String contentUrl) {
}
