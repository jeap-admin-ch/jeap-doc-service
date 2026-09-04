package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationSubject;

import java.time.Instant;

/**
 * The systems, components and libraries the doc service holds documentation of.
 */
public interface DocumentationSubjectRepository {

    /**
     * The subject as it is recorded, creating it when the doc service does not know it yet - a system, component
     * or library does not have to exist before its pipeline publishes documentation for it.
     * <p>
     * Two uploads naming the same subject at the same time may not create it twice.
     */
    DocumentationSubject findOrCreate(DocumentationSubject subject, Instant now);
}
