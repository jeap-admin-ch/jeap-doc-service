package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * One published version of a message type, and what is known about it.
 * <p>
 * The architecture model carries the version and nothing else - it is a string in the message export. The
 * schemas and the compatibility come from the <b>replicated</b> message schemas, which are stored apart from
 * the model and joined to it by name when a page is written, so a version reads as
 * {@link #of(String) just a version} until a generation run fills the rest in.
 * <p>
 * A version whose schemas were never replicated - new, or missed by a run that hit its deadline - therefore
 * still appears on its page. It is a version with no schema block rather than a page that is missing.
 *
 * @param version           the version, exactly as the registry spells it
 * @param compatibilityMode what it declares against {@link #compatibleVersion}, e.g. {@code BACKWARD}, or null
 * @param compatibleVersion the version it is compatible with, or null where there is no predecessor
 * @param key               the key schema, or null where there is none or none was replicated
 * @param value             the value schema, or null where none was replicated
 */
public record DocumentedMessageVersion(String version, String compatibilityMode, String compatibleVersion,
                                       MessageSchema key, MessageSchema value) {

    /** A version as the architecture model knows it: the string, and nothing else yet. */
    public static DocumentedMessageVersion of(String version) {
        return new DocumentedMessageVersion(version, null, null, null, null);
    }

    /** The same version with what was replicated about it, as a generation run joins the two halves. */
    public DocumentedMessageVersion with(MessageVersionSchemas schemas) {
        return new DocumentedMessageVersion(version, schemas.compatibilityMode(), schemas.compatibleVersion(),
                schemas.key(), schemas.value());
    }

    /** Whether there is a schema to show on either side. */
    public boolean hasSchemas() {
        return (key != null && key.hasSource()) || (value != null && value.hasSource());
    }

    /** Whether this version says what it is compatible with - the sentence the page prints where it does. */
    public boolean hasCompatibility() {
        return compatibilityMode != null && !compatibilityMode.isBlank();
    }
}
