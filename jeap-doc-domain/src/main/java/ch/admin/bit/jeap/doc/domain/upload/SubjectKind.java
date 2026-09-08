package ch.admin.bit.jeap.doc.domain.upload;

/**
 * What kind of thing an upload documents.
 */
public enum SubjectKind {

    SYSTEM,
    COMPONENT,
    LIBRARY;

    /**
     * What an upload of this type documents. <b>Public</b> because a structure template is asked what it
     * generates for a kind of subject, and the validation that asks lives in a sub-package.
     */
    public static SubjectKind of(DocumentationType type) {
        return switch (type) {
            case SYSTEM_DOCS -> SYSTEM;
            case COMPONENT_DOCS -> COMPONENT;
            case LIBRARY_DOCS -> LIBRARY;
        };
    }
}
