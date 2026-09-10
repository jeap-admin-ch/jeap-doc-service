package ch.admin.bit.jeap.doc.domain.architecture.imports;

/**
 * What one step of an import reads from an upstream.
 * <p>
 * Most of it is the architecture repository's, and there the names are the architecture repository's own: two
 * services that replicate one thing between them should call it the same thing. The three reaction kinds are
 * the exception - they are read from the <b>reaction observer</b>, which is a different service on a schedule
 * of its own, and they are steps of this import because everything one needs is already here: a lock per step,
 * a deadline, a state row and a staleness gauge.
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
    MESSAGE_SCHEMA,

    /**
     * The reaction graph of a system, as the reaction observer of the environment cut it - which messages make
     * the system's components react, and what they do in answer.
     */
    SYSTEM_REACTIONS,

    /** The reaction graph of one component. */
    COMPONENT_REACTIONS,

    /**
     * The reaction graphs of one message type, one per variant.
     * <p>
     * Three kinds rather than one, although one upstream serves all three: they have three indexes, three
     * entity tags and three state rows, and one kind would hide two thirds of a failure.
     */
    MESSAGE_REACTIONS
}
