package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationSubject;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state of an upload as it leaves the service: the domain types are answered as the values the API speaks,
 * which is what a pipeline compares against what it sent.
 */
class DocumentationUploadStatusDtoTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T09:12:00Z");

    @Test
    void of_whenAComponentWasUploaded_thenTheTypeIsTheParameterValue() {
        DocumentationUploadStatusDto status = DocumentationUploadStatusDto.of(upload(componentDocs()));

        assertThat(status.type()).isEqualTo("component-docs");
        assertThat(status.component()).isEqualTo("foo-bar-scs");
        assertThat(status.library()).isNull();
        assertThat(status.system()).isEqualTo("orders");
        assertThat(status.template()).isEqualTo("arc42");
        assertThat(status.state()).isEqualTo(UploadState.UPLOADING);
        assertThat(status.attempt()).isEqualTo(1);
        assertThat(status.completedAt()).isNull();
    }

    @Test
    void of_whenALibraryWasUploaded_thenItIsNamedAsALibrary() {
        DocumentationUploadStatusDto status = DocumentationUploadStatusDto.of(upload(libraryDocs()));

        assertThat(status.type()).isEqualTo("library-docs");
        assertThat(status.library()).isEqualTo("orders-common-lib");
        assertThat(status.component()).isNull();
    }

    @Test
    void of_whenTheUploadIsStored_thenTheResultSaysWhatWasStored() {
        DocumentationUpload stored = upload(componentDocs())
                .completed(new StoredBundle("uploads/docs/42/1/bundle.zip", "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b"), 4711,
                        RECEIVED_AT.plusSeconds(3));

        DocumentationUploadResultDto result = DocumentationUploadResultDto.of(stored);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.state()).isEqualTo(UploadState.PENDING);
        assertThat(result.sizeInBytes()).isEqualTo(4711);
        assertThat(result.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    private static DocumentationUpload upload(DocumentationUploadDescriptor descriptor) {
        return new DocumentationUpload(42L, UUID.fromString("8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77"),
                DocumentationSubject.of(descriptor), descriptor, UploadState.UPLOADING, null, null, 0, 1,
                RECEIVED_AT, null, null);
    }

    private static DocumentationUploadDescriptor componentDocs() {
        return provenance().type(DocumentationType.COMPONENT_DOCS).component("foo-bar-scs").version("1.4.0").build();
    }

    private static DocumentationUploadDescriptor libraryDocs() {
        return provenance().type(DocumentationType.LIBRARY_DOCS).library("orders-common-lib").version("1.4.0").build();
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder provenance() {
        return DocumentationUploadDescriptor.builder()
                .system("orders")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"));
    }
}
