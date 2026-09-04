package ch.admin.bit.jeap.doc.domain.upload;

/**
 * What became of one upload request: the upload as it is recorded, and whether this request is the one that
 * stored its bundle.
 * <p>
 * The difference is what a caller is told - a request that stored a bundle created something, a repetition of an
 * upload that was already stored did not - and only the doc service can tell them apart.
 *
 * @param upload the upload as it is recorded
 * @param stored whether this request stored the bundle, as opposed to repeating an upload that was already stored
 */
public record UploadReceipt(DocumentationUpload upload, boolean stored) {

    static UploadReceipt stored(DocumentationUpload upload) {
        return new UploadReceipt(upload, true);
    }

    static UploadReceipt repeated(DocumentationUpload upload) {
        return new UploadReceipt(upload, false);
    }
}
