package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * What was replicated of one component's artifacts, parsed.
 * <p>
 * Not part of the landscape the model read returns: a generation run reads and joins these per system, so
 * that a whole landscape of specifications is never held at once.
 *
 * @param schema the database schema, or null when none is replicated or it could not be read
 * @param api    the overview of the OpenAPI specification, or null for the same two reasons
 */
public record ComponentArtifacts(DatabaseSchema schema, RestApiOverview api) {
}
