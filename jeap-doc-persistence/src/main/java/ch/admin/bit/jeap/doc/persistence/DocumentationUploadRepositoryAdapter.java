package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.UploadClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The uploads, on PostgreSQL.
 * <p>
 * Every method is its own transaction: the upload is committed before its bundle is read, and the outcome is
 * written afterwards, so nothing holds a transaction open while a bundle streams.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class DocumentationUploadRepositoryAdapter implements DocumentationUploadRepository {

    private final DocumentationUploadJpaRepository uploads;
    private final DocumentationSubjectJpaRepository subjects;

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentationUpload> findByUploadId(UUID uploadId) {
        return uploads.findByUploadId(uploadId).map(DocumentationUploadMapper::toDomain);
    }

    /**
     * Deliberately not one transaction: two attempts arriving at the same moment make one of the inserts fail on
     * the unique index, and a PostgreSQL transaction that saw an error cannot be read from any more. Every step
     * is therefore its own transaction, and the attempt that lost reads what the winner wrote in a fresh one.
     */
    @Override
    public UploadClaim claim(UUID uploadId, DocumentationSubject subject, DocumentationUploadDescriptor descriptor,
                             Instant now, Instant staleBefore) {
        if (uploads.claim(uploadId, now, staleBefore) == 1) {
            return new UploadClaim.Claimed(reload(uploadId));
        }
        Optional<DocumentationUploadEntity> recorded = uploads.findByUploadId(uploadId);
        return recorded.map(this::outcomeOf).orElseGet(() ->
                recordUpload(uploadId, subject, descriptor, now));
    }

    /**
     * Writes what an attempt made of its upload - but only while that attempt still owns it.
     * <p>
     * An attempt that is slower than the in-progress timeout is taken over, and the attempt that took over may
     * have stored its bundle and told its caller so in the meantime. The straggler must not turn that upload into
     * a failed one, so the write is bound to its own attempt and simply does not happen when it lost the upload.
     */
    @Override
    @Transactional
    public DocumentationUpload save(DocumentationUpload upload) {
        int written = uploads.recordOutcome(upload.id(), upload.attempt(), upload.state(), upload.objectKey(),
                upload.bundleSha256(), upload.sizeInBytes(), upload.completedAt(), upload.failureReason());
        DocumentationUpload recorded = reloadById(upload.id());
        if (written == 0) {
            log.warn("The attempt {} of the upload {} was taken over while it was running; its outcome {} is not "
                     + "recorded, the upload stands as {}.",
                    upload.attempt(), upload.uploadId(), upload.state(), recorded.state());
        }
        return recorded;
    }

    @Override
    @Transactional
    public int deleteReceivedBefore(Instant receivedBefore) {
        return uploads.deleteReceivedBefore(receivedBefore);
    }

    /**
     * Records an upload that is not there yet. Two attempts can arrive at the same moment; the unique index on
     * the upload id decides which of them records it, and the other one reads what the winner wrote.
     */
    private UploadClaim recordUpload(UUID uploadId, DocumentationSubject subject,
                                     DocumentationUploadDescriptor descriptor, Instant now) {
        DocumentationSubjectEntity subjectEntity = subjects.findById(subject.id())
                .orElseThrow(() -> new IllegalStateException(
                        "The subject %d of the upload %s is not recorded.".formatted(subject.id(), uploadId)));
        DocumentationUploadEntity entity = DocumentationUploadMapper.toEntity(
                DocumentationUpload.received(uploadId, subject, descriptor, now), subjectEntity);
        try {
            uploads.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            return outcomeOf(uploads.findByUploadId(uploadId).orElseThrow(() -> e));
        }
        return new UploadClaim.Claimed(DocumentationUploadMapper.toDomain(entity));
    }

    private UploadClaim outcomeOf(DocumentationUploadEntity entity) {
        DocumentationUpload upload = DocumentationUploadMapper.toDomain(entity);
        return upload.isPending() ? new UploadClaim.AlreadyCompleted(upload) : new UploadClaim.InProgress(upload);
    }

    private DocumentationUpload reloadById(Long id) {
        return uploads.findById(id)
                .map(DocumentationUploadMapper::toDomain)
                .orElseThrow(() -> new IllegalStateException("The upload %d is not recorded.".formatted(id)));
    }

    private DocumentationUpload reload(UUID uploadId) {
        return uploads.findByUploadId(uploadId)
                .map(DocumentationUploadMapper::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "The upload %s was claimed but is not recorded.".formatted(uploadId)));
    }
}
