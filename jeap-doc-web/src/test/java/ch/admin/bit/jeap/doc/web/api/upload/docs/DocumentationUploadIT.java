package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.SubjectKind;
import ch.admin.bit.jeap.doc.domain.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.COMPONENT;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.SYSTEM;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.bundle;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.componentDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.systemDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an upload leaves behind: a row in the database, an object in the storage, and an answer naming both.
 */
class DocumentationUploadIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationUploadRepository uploads;

    @Test
    void upload_thenRecordedAsPendingAndStoredUnderItsId() throws Exception {
        UUID uploadId = UUID.randomUUID();
        byte[] bundle = bundle("# why we built this");

        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle).with(writeRole()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.sizeInBytes").value(bundle.length))
                .andExpect(jsonPath("$.id").isNumber());

        DocumentationUpload recorded = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(recorded.state()).isEqualTo(UploadState.PENDING);
        assertThat(recorded.objectKey())
                .isEqualTo("uploads/docs/%d/%d/bundle.zip".formatted(recorded.id(), recorded.attempt()));
        assertThat(recorded.descriptor().system()).isEqualTo(SYSTEM);
        assertThat(recorded.descriptor().component()).isEqualTo(COMPONENT);
        assertThat(recorded.subject().kind()).isEqualTo(SubjectKind.COMPONENT);
        assertThat(recorded.subject().site()).isEqualTo("default");
        assertThat(storedBundle(recorded.objectKey())).isEqualTo(bundle);
        // Recorded from the bytes that were stored, so it can be held against what the pipeline sent.
        assertThat(recorded.bundleSha256()).isEqualTo(sha256Of(bundle));
    }

    /**
     * A system, component or library the doc service does not know is created by the upload that names it - and
     * a second upload of the same component does not create it a second time.
     */
    @Test
    void upload_whenTheComponentIsUploadedTwice_thenOneSubjectAndTwoUploads() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<String, String> parameters = componentDocs();
        parameters.put("component", "a-fresh-component");

        mockMvc.perform(uploadOf(first, parameters, bundle("first")).with(writeRole())).andExpect(status().isCreated());
        mockMvc.perform(uploadOf(second, parameters, bundle("second")).with(writeRole())).andExpect(status().isCreated());

        DocumentationUpload one = uploads.findByUploadId(first).orElseThrow();
        DocumentationUpload other = uploads.findByUploadId(second).orElseThrow();
        assertThat(other.id()).isNotEqualTo(one.id());
        assertThat(other.subject().id()).isEqualTo(one.subject().id());
        assertThat(storedBundle(other.objectKey())).isEqualTo(bundle("second"));
    }

    @Test
    void upload_whenTheSystemIsDocumented_thenTheSubjectHasNoName() throws Exception {
        UUID uploadId = UUID.randomUUID();

        mockMvc.perform(uploadOf(uploadId, systemDocs(), bundle("# the system")).with(writeRole()))
                .andExpect(status().isCreated());

        DocumentationUpload recorded = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(recorded.subject().kind()).isEqualTo(SubjectKind.SYSTEM);
        assertThat(recorded.subject().name()).isNull();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor writeRole() {
        return authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")));
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] storedBundle(String objectKey) {
        ResponseBytes<GetObjectResponse> object = S3_CLIENT.getObject(
                GetObjectRequest.builder().bucket(TEST_BUCKET_NAME).key(objectKey).build(),
                ResponseTransformer.toBytes());
        return object.asByteArray();
    }
}
