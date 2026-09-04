package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.SYSTEM;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.bundle;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.componentDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload is recorded <b>before</b> its bundle is read, and no transaction is open while it streams.
 * <p>
 * Both are invisible from the outside and easy to lose in a refactoring, so they are pinned here: the storage
 * looks into the database while it is being called, from a connection of its own. That it sees the upload at all
 * proves the row was committed; that it sees it as {@code UPLOADING} proves it was committed before the bundle
 * was stored.
 */
class UploadTransactionBoundaryIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationUploadRepository uploads;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DocumentationBundleStorage bundleStorage;

    @Test
    void upload_whileTheBundleIsStored_thenTheUploadIsAlreadyCommittedAsUploading() throws Exception {
        UUID uploadId = UUID.randomUUID();
        AtomicReference<String> seenWhileStoring = new AtomicReference<>();
        when(bundleStorage.store(anyLong(), anyInt(), any(), anyLong())).thenAnswer(call -> {
            seenWhileStoring.set(stateOf(uploadId));
            return new StoredBundle("uploads/docs/%d/%d/bundle.zip".formatted(
                    call.<Long>getArgument(0), call.<Integer>getArgument(1)),
                    "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b");
        });

        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("# a component"))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());

        assertThat(seenWhileStoring.get()).isEqualTo(UploadState.UPLOADING.name());
        DocumentationUpload recorded = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(recorded.state()).isEqualTo(UploadState.PENDING);
    }

    private String stateOf(UUID uploadId) {
        return jdbcTemplate.queryForObject("select state from documentation_upload where upload_id = ?",
                String.class, uploadId);
    }
}
