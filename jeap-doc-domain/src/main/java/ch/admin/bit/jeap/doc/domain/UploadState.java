package ch.admin.bit.jeap.doc.domain;

/**
 * Where an upload stands. The generator picks up what is {@link #PENDING}; everything else is either still on
 * its way or did not make it.
 */
public enum UploadState {

    /**
     * The upload is recorded and its bundle is on its way to the object storage. One attempt holds the upload id
     * while it is in this state, and a second attempt is refused until the attempt is considered abandoned.
     */
    UPLOADING,

    /**
     * The bundle is completely stored: the upload is waiting for the documentation generator.
     */
    PENDING,

    /**
     * Storing the bundle failed. Nothing picks the upload up, and a retry under the same upload id replaces it.
     */
    FAILED
}
