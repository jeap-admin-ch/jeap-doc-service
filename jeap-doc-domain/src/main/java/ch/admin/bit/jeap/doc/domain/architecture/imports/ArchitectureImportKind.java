package ch.admin.bit.jeap.doc.domain.architecture.imports;

/**
 * What one step of an import reads from the architecture repository.
 * <p>
 * The names are the architecture repository's own. Two services that replicate one thing between them should
 * call it the same thing.
 */
public enum ArchitectureImportKind {

    /** The systems, their components, relations and messages. Fetched whole and replaced whole. */
    MODEL,

    /** The OpenAPI specification a component publishes. Fetched only when its entity tag moved. */
    OPENAPI_SPEC,

    /** The database schema a component publishes. Fetched only when its entity tag moved. */
    DATABASE_SCHEMA,

    /**
     * The Avro schemas of the message type versions, as the architecture repository renders them.
     * <p>
     * Like the two above it, a version is <b>revalidated</b> rather than fetched once and trusted: it rarely
     * moves, but the compatibility it declares is derived upstream from the version list, so an unchanged one
     * costs a 304 and no payload while a version that moved is fetched again.
     */
    MESSAGE_SCHEMA
}
