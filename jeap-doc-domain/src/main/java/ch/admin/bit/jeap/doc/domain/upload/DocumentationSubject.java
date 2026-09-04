package ch.admin.bit.jeap.doc.domain.upload;

import java.time.Instant;

/**
 * The thing an upload documents: a system, one of its components or one of its libraries, within one site.
 * <p>
 * A subject is created by the first upload that names it - the doc service does not have to know about a system
 * before its pipeline publishes documentation - and it is what the documentation of a site is listed by.
 *
 * @param id        the identifier of the subject, assigned when it is created
 * @param site      the site the subject belongs to
 * @param kind      whether the subject is a system, a component or a library
 * @param system    the system, also for a component and for a library
 * @param name      the name of the component or of the library, null for a system
 * @param createdAt when the subject was created
 */
public record DocumentationSubject(
        Long id,
        String site,
        SubjectKind kind,
        String system,
        String name,
        Instant createdAt) {

    /**
     * The subject the given upload documents, as it has to exist before the upload can be recorded.
     */
    public static DocumentationSubject of(DocumentationUploadDescriptor descriptor) {
        return new DocumentationSubject(null, descriptor.site(), SubjectKind.of(descriptor.type()),
                descriptor.system(), descriptor.subjectName(), null);
    }
}
