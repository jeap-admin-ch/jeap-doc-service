package ch.admin.bit.jeap.doc.domain.upload;

/**
 * Where an upload stands. {@link #PENDING} is an upload that arrived and was taken over; everything else is
 * either still on its way or did not make it.
 */
public enum UploadState {

    /**
     * The upload is recorded and its bundle is on its way to the object storage. One attempt holds the upload id
     * while it is in this state, and a second attempt is refused until the attempt is considered abandoned.
     */
    UPLOADING,

    /**
     * The bundle is completely stored and its set is the current documentation of its subject, waiting to be
     * published by the next build. <b>Nothing reads uploads to find it</b>: the set was taken over when the
     * upload arrived, and a build reads the sets.
     */
    PENDING,

    /**
     * Storing the bundle failed, or the set it carried would not be published as it is. Nothing picks the
     * upload up, and a retry under the same upload id replaces it.
     */
    FAILED
}
