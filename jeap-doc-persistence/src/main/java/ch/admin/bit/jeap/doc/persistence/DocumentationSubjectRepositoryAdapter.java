package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.port.DocumentationSubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The subjects of the documentation, on PostgreSQL.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class DocumentationSubjectRepositoryAdapter implements DocumentationSubjectRepository {

    private final DocumentationSubjectJpaRepository subjects;

    @Override
    @Transactional
    public DocumentationSubject findOrCreate(DocumentationSubject subject, Instant now) {
        return subjects.find(subject.site(), subject.kind(), subject.system(), subject.name())
                .map(DocumentationUploadMapper::toDomain)
                .orElseGet(() -> create(subject, now));
    }

    private DocumentationSubject create(DocumentationSubject subject, Instant now) {
        subjects.insertIfAbsent(subject.site(), subject.kind().name(), subject.system(), subject.name(), now);
        DocumentationSubject created = subjects
                .find(subject.site(), subject.kind(), subject.system(), subject.name())
                .map(DocumentationUploadMapper::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "The subject %s of the system %s was neither found nor created."
                                .formatted(subject.name(), subject.system())));
        log.info("The {} {} of the system {} is documented for the first time on the site {}.",
                subject.kind(), subject.name() == null ? subject.system() : subject.name(), subject.system(),
                subject.site());
        return created;
    }
}
