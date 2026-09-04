package ch.admin.bit.jeap.doc.domain.upload;

import org.junit.jupiter.api.Test;

import ch.admin.bit.jeap.doc.domain.port.StoredBundle;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentationUploadTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-24T09:12:00Z");
    private static final StoredBundle STORED = new StoredBundle("uploads/docs/42/1/bundle.zip",
            "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-24T09:12:03Z");

    @Test
    void received_thenUploadingAndNothingStoredYet() {
        DocumentationUpload upload = received();

        assertThat(upload.state()).isEqualTo(UploadState.UPLOADING);
        assertThat(upload.isPending()).isFalse();
        assertThat(upload.attempt()).isEqualTo(1);
        assertThat(upload.objectKey()).isNull();
        assertThat(upload.completedAt()).isNull();
    }

    @Test
    void completed_thenPendingWithTheStoredBundle() {
        DocumentationUpload upload = received().completed(STORED, 4711, COMPLETED_AT);

        assertThat(upload.isPending()).isTrue();
        assertThat(upload.objectKey()).isEqualTo("uploads/docs/42/1/bundle.zip");
        assertThat(upload.bundleSha256()).isEqualTo(STORED.sha256());
        assertThat(upload.sizeInBytes()).isEqualTo(4711);
        assertThat(upload.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(upload.failureReason()).isNull();
    }

    @Test
    void failed_thenFailedWithTheReason() {
        DocumentationUpload upload = received().failed("the object storage did not answer");

        assertThat(upload.state()).isEqualTo(UploadState.FAILED);
        assertThat(upload.failureReason()).isEqualTo("the object storage did not answer");
        assertThat(upload.completedAt()).isNull();
    }

    @Test
    void completed_whenAlreadyCompleted_thenRejected() {
        DocumentationUpload stored = received().completed(STORED, 4711, COMPLETED_AT);

        assertThatThrownBy(() -> stored.completed(STORED, 4711, COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void failed_whenTheUploadIsAlreadyStored_thenRejected() {
        DocumentationUpload stored = received().completed(STORED, 4711, COMPLETED_AT);

        assertThatThrownBy(() -> stored.failed("too late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void completed_whenThePreviousAttemptFailed_thenRejectedUntilItIsClaimedAgain() {
        DocumentationUpload failed = received().failed("the object storage did not answer");

        assertThatThrownBy(() -> failed.completed(STORED, 4711, COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    /**
     * An upload that names no site and one that names the default site are the same upload - otherwise a doc
     * workflow that starts naming its site would be told its retry is a different upload.
     */
    @Test
    void describesTheSameAs_whenTheRetryNamesTheDefaultSiteExplicitly_thenTrue() {
        DocumentationUpload upload = received();

        assertThat(upload.describesTheSameAs(descriptor().site("default").build())).isTrue();
        assertThat(upload.describesTheSameAs(descriptor().site("catalog").build())).isFalse();
    }

    @Test
    void describesTheSameAs_whenAnyParameterDiffers_thenFalse() {
        DocumentationUpload upload = received();

        assertThat(upload.describesTheSameAs(descriptor().build())).isTrue();
        assertThat(upload.describesTheSameAs(descriptor().component("another-scs").build())).isFalse();
        assertThat(upload.describesTheSameAs(descriptor().buildUrl("https://github.com/orders/foo-bar-scs/actions/runs/1234567891").build())).isFalse();
        assertThat(upload.describesTheSameAs(descriptor().version("1.4.1").build())).isFalse();
    }

    private static DocumentationUpload received() {
        DocumentationUploadDescriptor descriptor = descriptor().build();
        return DocumentationUpload.received(UUID.fromString("8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77"),
                DocumentationSubject.of(descriptor), descriptor, RECEIVED_AT);
    }

    private static DocumentationUploadDescriptor.DocumentationUploadDescriptorBuilder descriptor() {
        return DocumentationUploadDescriptor.builder()
                .type(DocumentationType.COMPONENT_DOCS)
                .system("orders")
                .component("foo-bar-scs")
                .version("1.4.0")
                .template("arc42")
                .sourceFormat(SourceFormat.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-08-21T07:12:00Z"))
                .buildUrl("https://github.com/orders/foo-bar-scs/actions/runs/1234567890");
    }
}
