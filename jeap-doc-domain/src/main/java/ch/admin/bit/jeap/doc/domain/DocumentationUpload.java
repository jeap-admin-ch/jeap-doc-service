package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.StoredBundle;

import java.time.Instant;
import java.util.UUID;

/**
 * One upload of documentation: what was uploaded, where its bundle lies, and how far it got.
 * <p>
 * The identifier {@code id} is assigned by the doc service and is what the bundle is stored under, while
 * {@code uploadId} is chosen by the client and is the idempotency key of the API: every attempt under one upload
 * id describes the same upload and writes the same object, so a pipeline may retry without producing a second
 * documentation set. The descriptor is therefore recorded once and never rewritten - an attempt that describes
 * something else is rejected rather than applied.
 *
 * @param id            the identifier of the upload, assigned by the doc service, and the path its bundle is stored under
 * @param uploadId      the identifier chosen by the client, unique, and the same across the attempts of one upload
 * @param subject       what is documented
 * @param descriptor    what was uploaded and where it came from
 * @param state         where the upload stands
 * @param objectKey     where the bundle lies, as the object storage reported it, null until it is stored
 * @param bundleSha256  the SHA-256 of the stored bundle, lower case hexadecimal, null until it is stored
 * @param sizeInBytes   the size of the stored bundle, 0 until it is stored
 * @param attempt       how often this upload was claimed - 1 for the first attempt
 * @param receivedAt    when the current attempt claimed the upload
 * @param completedAt   when the bundle was completely stored, null until then
 * @param failureReason why the last attempt failed, null unless it did
 */
public record DocumentationUpload(
        Long id,
        UUID uploadId,
        DocumentationSubject subject,
        DocumentationUploadDescriptor descriptor,
        UploadState state,
        String objectKey,
        String bundleSha256,
        long sizeInBytes,
        int attempt,
        Instant receivedAt,
        Instant completedAt,
        String failureReason) {

    /**
     * A newly received upload, before its bundle is read.
     */
    public static DocumentationUpload received(UUID uploadId, DocumentationSubject subject,
                                               DocumentationUploadDescriptor descriptor, Instant now) {
        return new DocumentationUpload(null, uploadId, subject, descriptor, UploadState.UPLOADING,
                null, null, 0, 1, now, null, null);
    }

    /**
     * The same upload with its bundle stored: it is now waiting for the generator.
     */
    public DocumentationUpload completed(StoredBundle bundle, long sizeInBytes, Instant now) {
        requireState(UploadState.UPLOADING, "completed");
        return new DocumentationUpload(id, uploadId, subject, descriptor, UploadState.PENDING,
                bundle.objectKey(), bundle.sha256(), sizeInBytes, attempt, receivedAt, now, null);
    }

    /**
     * The same upload with its bundle not stored. A retry under the same upload id claims it again.
     */
    public DocumentationUpload failed(String reason) {
        requireState(UploadState.UPLOADING, "failed");
        return new DocumentationUpload(id, uploadId, subject, descriptor, UploadState.FAILED,
                objectKey, bundleSha256, sizeInBytes, attempt, receivedAt, null, reason);
    }

    /**
     * Whether the bundle is completely stored, which is what the generator waits for.
     */
    public boolean isPending() {
        return state == UploadState.PENDING;
    }

    /**
     * Whether this upload describes the same thing as the given attempt. Everything has to match: a retry
     * re-sends the request it sent before, so anything that differs is a reused upload id rather than a retry.
     */
    public boolean describesTheSameAs(DocumentationUploadDescriptor other) {
        return descriptor.equals(other);
    }

    private void requireState(UploadState expected, String transition) {
        if (state != expected) {
            throw new IllegalStateException(
                    "An upload in the state %s cannot be %s.".formatted(state, transition));
        }
    }
}
