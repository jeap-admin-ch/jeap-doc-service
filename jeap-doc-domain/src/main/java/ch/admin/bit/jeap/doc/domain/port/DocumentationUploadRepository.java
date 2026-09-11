package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The uploads the doc service has received.
 */
public interface DocumentationUploadRepository {

    /**
     * The upload recorded under the given upload id, if there is one.
     */
    Optional<DocumentationUpload> findByUploadId(UUID uploadId);

    /**
     * Claims the upload id for one attempt: it records a new upload, or takes over one whose previous attempt
     * failed or was abandoned. The upload is committed before its bundle is read, so a bundle on its way is
     * visible - and recoverable - rather than invisible.
     * <p>
     * Two attempts arriving at the same time have to see different outcomes; the implementation decides the race
     * in the database rather than in the application.
     *
     * @param uploadId    the upload id the attempt uses
     * @param subject     what is documented
     * @param descriptor  what the attempt says it uploads
     * @param now         when the attempt arrived
     * @param staleBefore an upload in progress since before this instant counts as abandoned
     */
    UploadClaim claim(UUID uploadId, DocumentationSubject subject, DocumentationUploadDescriptor descriptor,
                      Instant now, Instant staleBefore);

    /**
     * Records the outcome of an attempt - the stored bundle, or the failure.
     */
    DocumentationUpload save(DocumentationUpload upload);

    /**
     * Whether this attempt still holds the upload, rather than having been taken over by a later one.
     * <p>
     * <b>Asked before an attempt makes its set current.</b> An attempt slower than the in-progress timeout is
     * taken over and keeps running - nothing can stop it - and the attempt that took over may have published
     * its own bundle in the meantime. Its own question rather than a read of the upload, so that the answer
     * cannot be mistaken for the lookup an attempt makes before it claims anything.
     *
     * @param uploadId the upload id the attempt is writing under
     * @param attempt  the attempt asking
     */
    boolean isHeldBy(UUID uploadId, int attempt);

    /**
     * Removes the uploads that were last received before the given instant, whatever state they are in, and
     * reports how many there were. What they document is kept: a system, component or library stays in the
     * catalogue of the documentation once it has been documented.
     */
    int deleteReceivedBefore(Instant receivedBefore);
}
