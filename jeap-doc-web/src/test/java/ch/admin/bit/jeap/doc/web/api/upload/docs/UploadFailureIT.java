package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationUpload;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBundleStorage;
import ch.admin.bit.jeap.doc.domain.port.DocumentationUploadRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What is left behind when the object storage does not play along: a recorded failure a retry can pick up, not a
 * row that says an upload is still on its way.
 */
class UploadFailureIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationUploadRepository uploads;

    @MockitoBean
    private DocumentationBundleStorage bundleStorage;

    @Test
    void upload_whenTheBundleCannotBeStored_thenRecordedAsFailedAndAnswered() throws Exception {
        UUID uploadId = UUID.randomUUID();
        when(bundleStorage.store(anyLong(), anyInt(), any(), anyLong()))
                .thenThrow(new IllegalStateException("the object storage did not answer"));

        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("# a component")).with(writeRole()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_FAILED"));

        DocumentationUpload recorded = uploads.findByUploadId(uploadId).orElseThrow();
        assertThat(recorded.state()).isEqualTo(UploadState.FAILED);
        assertThat(recorded.failureReason()).isEqualTo("The bundle could not be stored.")
                .doesNotContain("the object storage did not answer");
        assertThat(recorded.objectKey()).isNull();
    }

    private static RequestPostProcessor writeRole() {
        return authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")));
    }
}
