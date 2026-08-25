package ch.admin.bit.jeap.doc.web.api.upload;

/**
 * The paths the upload endpoints are served at.
 * <p>
 * Everything below {@value #UPLOADS} is an upload, and the segment after it says what kind of thing is uploaded:
 * documentation today, and whatever a documentation site needs next to it later. A kind brings its own parameters,
 * its own validation and possibly its own body, so it gets its own path instead of a parameter distinguishing it.
 */
public final class UploadPaths {

    /**
     * The family: everything below it is an upload of some kind.
     */
    public static final String UPLOADS = "/api/uploads";

    /**
     * Uploads of documentation.
     */
    public static final String DOCS = UPLOADS + "/docs";

    private UploadPaths() {
    }
}
