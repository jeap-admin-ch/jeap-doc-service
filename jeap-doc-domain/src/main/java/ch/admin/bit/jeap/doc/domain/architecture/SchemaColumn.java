package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * One column of a {@link SchemaTable}.
 *
 * @param name     the column name
 * @param type     the database type, as the schema publisher wrote it
 * @param nullable whether it may be null
 */
public record SchemaColumn(String name, String type, boolean nullable) {
}
