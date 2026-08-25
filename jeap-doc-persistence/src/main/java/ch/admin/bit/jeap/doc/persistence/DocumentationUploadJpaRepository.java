package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ch.admin.bit.jeap.doc.domain.UploadState;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface DocumentationUploadJpaRepository extends JpaRepository<DocumentationUploadEntity, Long> {

    Optional<DocumentationUploadEntity> findByUploadId(UUID uploadId);

    /**
     * Removes what was last received before the given instant, in one statement - so two instances running the
     * clean-up at the same time cannot get in each other's way.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentationUploadEntity u where u.receivedAt < :receivedBefore")
    int deleteReceivedBefore(@Param("receivedBefore") Instant receivedBefore);

    /**
     * Writes the outcome of one attempt, and only while that attempt still owns the upload: an attempt that was
     * taken over as abandoned may not overwrite what the attempt that replaced it recorded.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DocumentationUploadEntity u
               set u.state = :state,
                   u.objectKey = :objectKey,
                   u.bundleSha256 = :bundleSha256,
                   u.sizeInBytes = :sizeInBytes,
                   u.completedAt = :completedAt,
                   u.failureReason = :failureReason
             where u.id = :id and u.attempt = :attempt
            """)
    int recordOutcome(@Param("id") Long id, @Param("attempt") int attempt, @Param("state") UploadState state,
                      @Param("objectKey") String objectKey, @Param("bundleSha256") String bundleSha256,
                      @Param("sizeInBytes") long sizeInBytes, @Param("completedAt") Instant completedAt,
                      @Param("failureReason") String failureReason);

    /**
     * Claims an upload that is free to be claimed: one whose attempt failed, or one that has been in progress
     * for longer than the doc service waits for it. One statement, so of two attempts arriving at the same
     * moment exactly one updates a row and the other one learns that it lost.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DocumentationUploadEntity u
               set u.state = ch.admin.bit.jeap.doc.domain.UploadState.UPLOADING,
                   u.attempt = u.attempt + 1,
                   u.receivedAt = :now,
                   u.completedAt = null,
                   u.failureReason = null
             where u.uploadId = :uploadId
               and (u.state = ch.admin.bit.jeap.doc.domain.UploadState.FAILED
                    or (u.state = ch.admin.bit.jeap.doc.domain.UploadState.UPLOADING and u.receivedAt < :staleBefore))
            """)
    int claim(@Param("uploadId") UUID uploadId, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
