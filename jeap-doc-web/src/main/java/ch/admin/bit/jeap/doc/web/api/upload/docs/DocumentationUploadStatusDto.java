package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The state of an upload, as a pipeline asks for it after a retry or a lost answer.
 */
@Schema(description = "The state of an upload")
record DocumentationUploadStatusDto(
        UUID uploadId,
        Long id,
        UploadState state,
        String type,
        String system,
        String component,
        String library,
        String template,
        long sizeInBytes,
        int attempt,
        Instant receivedAt,
        Instant completedAt,
        String failureReason) {

    static DocumentationUploadStatusDto of(DocumentationUpload upload) {
        var descriptor = upload.descriptor();
        return new DocumentationUploadStatusDto(upload.uploadId(), upload.id(), upload.state(),
                DocumentationTypeDto.of(descriptor.type()).parameterValue(), descriptor.system(),
                descriptor.component(), descriptor.library(), descriptor.template(), upload.sizeInBytes(),
                upload.attempt(), upload.receivedAt(), upload.completedAt(), upload.failureReason());
    }
}
