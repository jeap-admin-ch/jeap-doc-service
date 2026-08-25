package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.SYSTEM;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.bundle;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.componentDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload id is the idempotency key of the API: what a repetition under it does, case by case.
 * <p>
 * The race between two attempts arriving at the same moment is decided in the database and is tested there,
 * against a real PostgreSQL; here the states an attempt can find are set up directly and driven through the
 * endpoint.
 */
class UploadIdempotencyIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationUploadRepository uploads;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The pipeline repeated an upload that got through - the answer is the one of the attempt that stored it,
     * and what lies in the storage stays what that attempt uploaded, even though this request carries other bytes.
     */
    @Test
    void upload_whenTheUploadIsAlreadyStored_thenReplayedWithoutWritingAnything() throws Exception {
        UUID uploadId = UUID.randomUUID();
        byte[] stored = bundle("the bundle that got through");
        mockMvc.perform(uploadOf(uploadId, componentDocs(), stored).with(writeRole()))
                .andExpect(status().isCreated());
        DocumentationUpload first = uploads.findByUploadId(uploadId).orElseThrow();

        // Nothing was created by this request - it repeats an upload that is already stored.
        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("other bytes")).with(writeRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.id()))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.sizeInBytes").value(stored.length));

        DocumentationUpload afterwards = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(afterwards.attempt()).isEqualTo(first.attempt());
        assertThat(afterwards.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(storedBundle(first.objectKey())).isEqualTo(stored);
    }

    /**
     * A pipeline that timestamps to the nanosecond has to be able to retry too: what the database gives back is
     * what a retry is compared with, so the timestamps are cut to the precision it keeps before anything is
     * recorded.
     */
    @Test
    void upload_whenTheTimestampsAreMorePreciseThanTheDatabase_thenARetryIsStillARetry() throws Exception {
        UUID uploadId = UUID.randomUUID();
        Map<String, String> preciseToTheNanosecond = componentDocs();
        preciseToTheNanosecond.put("source-timestamp", "2026-08-21T09:12:00.123456789+02:00");
        preciseToTheNanosecond.put("generated-at", "2026-08-21T09:15:00.987654321+02:00");
        byte[] bundle = bundle("# a component");

        mockMvc.perform(uploadOf(uploadId, preciseToTheNanosecond, bundle).with(writeRole()))
                .andExpect(status().isCreated());
        mockMvc.perform(uploadOf(uploadId, preciseToTheNanosecond, bundle).with(writeRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void upload_whenTheUploadIdDescribesSomethingElse_thenRejectedAndNothingChanges() throws Exception {
        UUID uploadId = UUID.randomUUID();
        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("first")).with(writeRole()))
                .andExpect(status().isCreated());
        DocumentationUpload first = uploads.findByUploadId(uploadId).orElseThrow();

        Map<String, String> anotherBuild = componentDocs();
        anotherBuild.put("build-url", "https://github.com/wvs/wvs-docs/actions/runs/1234567891");

        mockMvc.perform(uploadOf(uploadId, anotherBuild, bundle("second")).with(writeRole()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_ID_CONFLICT"));

        assertThat(uploads.findByUploadId(uploadId).orElseThrow()).isEqualTo(first);
    }

    @Test
    void upload_whenAnotherAttemptIsInFlight_thenRefusedWithHowLongToWait() throws Exception {
        UUID uploadId = uploadInState(UploadState.UPLOADING, Instant.now());

        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("a retry")).with(writeRole()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_IN_PROGRESS"))
                .andExpect(header().string("Retry-After", notNullValue()));
    }

    /**
     * The service died while the bundle was streaming: after the in-progress timeout the upload id is free again,
     * and the next attempt takes it over instead of being blocked forever.
     */
    @Test
    void upload_whenTheAttemptInFlightIsAbandoned_thenTakenOver() throws Exception {
        UUID uploadId = uploadInState(UploadState.UPLOADING, Instant.now().minus(10, ChronoUnit.MINUTES));

        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("a retry")).with(writeRole()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"));

        assertThat(uploads.findByUploadId(uploadId).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    void upload_whenThePreviousAttemptFailed_thenRetriedUnderTheSameId() throws Exception {
        UUID uploadId = uploadInState(UploadState.FAILED, Instant.now());
        DocumentationUpload failed = uploads.findByUploadId(uploadId).orElseThrow();
        byte[] retried = bundle("the retry that worked");

        mockMvc.perform(uploadOf(uploadId, componentDocs(), retried).with(writeRole()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(failed.id()));

        DocumentationUpload afterwards = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(afterwards.state()).isEqualTo(UploadState.PENDING);
        assertThat(afterwards.attempt()).isEqualTo(2);
        assertThat(afterwards.failureReason()).isNull();
        assertThat(storedBundle(afterwards.objectKey())).isEqualTo(retried);
    }

    /**
     * An upload as an attempt left it behind - what a retry finds when the attempt before it did not finish.
     */
    private UUID uploadInState(UploadState state, Instant receivedAt) throws Exception {
        UUID uploadId = UUID.randomUUID();
        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("an attempt")).with(writeRole()))
                .andExpect(status().isCreated());
        jdbcTemplate.update("""
                update documentation_upload
                   set state = ?, received_at = ?, completed_at = null, object_key = null, size_in_bytes = 0
                 where upload_id = ?
                """, state.name(), java.sql.Timestamp.from(receivedAt), uploadId);
        return uploadId;
    }

    private static RequestPostProcessor writeRole() {
        return authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")));
    }

    private static byte[] storedBundle(String objectKey) {
        return S3_CLIENT.getObject(GetObjectRequest.builder().bucket(TEST_BUCKET_NAME).key(objectKey).build(),
                ResponseTransformer.toBytes()).asByteArray();
    }
}
