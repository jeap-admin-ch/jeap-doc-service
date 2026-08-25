package ch.admin.bit.jeap.doc.domain;

/**
 * What kind of thing an upload documents.
 */
public enum SubjectKind {

    SYSTEM,
    COMPONENT,
    LIBRARY;

    static SubjectKind of(DocumentationType type) {
        return switch (type) {
            case SYSTEM_DOCS -> SYSTEM;
            case COMPONENT_DOCS -> COMPONENT;
            case LIBRARY_DOCS -> LIBRARY;
        };
    }
}
