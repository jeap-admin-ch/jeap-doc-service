package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;

/**
 * What happened when an attempt tried to claim an upload id.
 * <p>
 * Claiming decides a race that the doc service must not lose: two attempts writing the same object at the same
 * time would leave a bundle nobody uploaded completely. The repository therefore reports the outcome instead of
 * a row, and the domain answers the caller accordingly.
 */
public sealed interface UploadClaim {

    /**
     * The upload belongs to this attempt: the bundle may be stored.
     */
    record Claimed(DocumentationUpload upload) implements UploadClaim {
    }

    /**
     * The upload is already stored - this attempt is a repetition of one that got through, and changes nothing.
     */
    record AlreadyCompleted(DocumentationUpload upload) implements UploadClaim {
    }

    /**
     * Another attempt holds the upload id and is still within the in-progress timeout.
     */
    record InProgress(DocumentationUpload upload) implements UploadClaim {
    }
}
