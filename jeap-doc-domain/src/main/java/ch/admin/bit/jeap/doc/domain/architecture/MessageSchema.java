package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * One side of one message type version: its key schema or its value schema, as the architecture repository
 * renders it.
 * <p>
 * <b>{@code resolvedSchema} is a rendering, not the file.</b> The architecture repository produces it while it
 * imports a message type: every {@code import idl} is inlined and marked with a comment, the messaging base
 * types are dropped, and the namespaces, the {@code record} keyword and the enclosing braces are removed. It is
 * meant to be read by a person and is <b>deliberately not valid Avro IDL</b> - {@link #schemaUrl} is where the
 * file itself is. Nothing here parses it, and nothing should.
 *
 * @param schemaName     the schema file's name in the message type registry, or null where none was stored
 * @param schemaUrl      where the file can be browsed in the registry, or null where none was stored
 * @param resolvedSchema the rendering above, or null where none was stored
 */
public record MessageSchema(String schemaName, String schemaUrl, String resolvedSchema) {

    /** Whether there is anything to show: a side with no rendering is one the page leaves out. */
    public boolean hasSource() {
        return resolvedSchema != null && !resolvedSchema.isBlank();
    }

    /** Whether this side says anything at all - a version may carry no key schema. */
    public boolean isEmpty() {
        return schemaName == null && schemaUrl == null && !hasSource();
    }
}
