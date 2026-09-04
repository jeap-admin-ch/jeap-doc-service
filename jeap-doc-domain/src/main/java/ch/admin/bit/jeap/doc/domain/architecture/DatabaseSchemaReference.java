package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * That a component has published a database schema, and where it can be read. Rendered as an entity
 * relationship diagram later, and carried here for the same reason as {@link OpenApiReference}.
 *
 * @param schemaVersion the version of the schema
 * @param contentUrl    where the schema is read from, relative to the architecture repository
 */
public record DatabaseSchemaReference(String schemaVersion, String contentUrl) {
}
