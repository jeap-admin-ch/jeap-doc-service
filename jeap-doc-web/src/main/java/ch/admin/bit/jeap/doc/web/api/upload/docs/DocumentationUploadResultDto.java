package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.UploadState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * What became of an upload.
 *
 * @param uploadId    the upload id the client chose
 * @param id          the identifier the doc service gave the upload, and the path its bundle is stored under
 * @param state       where the upload stands
 * @param sizeInBytes the size of the stored bundle
 * @param receivedAt  when the upload was received
 */
@Schema(description = "The result of an upload")
record DocumentationUploadResultDto(
        UUID uploadId,
        Long id,
        UploadState state,
        long sizeInBytes,
        Instant receivedAt) {

    static DocumentationUploadResultDto of(DocumentationUpload upload) {
        return new DocumentationUploadResultDto(upload.uploadId(), upload.id(), upload.state(),
                upload.sizeInBytes(), upload.receivedAt());
    }
}
