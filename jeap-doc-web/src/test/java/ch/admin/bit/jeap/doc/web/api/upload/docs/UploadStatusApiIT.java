package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.COMPONENT;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.SYSTEM;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.bundle;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.componentDocs;
import static ch.admin.bit.jeap.doc.web.api.upload.docs.DocumentationUploads.uploadOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a pipeline is told when it asks what became of its upload.
 */
class UploadStatusApiIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void status_whenTheUploadIsStored_thenAnsweredWithWhatItDocuments() throws Exception {
        UUID uploadId = uploaded();

        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId).param("system", SYSTEM).with(writeRole()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.type").value("component-docs"))
                .andExpect(jsonPath("$.component").value(COMPONENT))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void status_whenTheUploadIsUnknown_thenNotFound() throws Exception {
        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID())
                        .param("system", SYSTEM).with(writeRole()))
                .andExpect(status().isNotFound());
    }

    /**
     * The role is granted per system, and an upload of another system is none of the caller's business - it is
     * answered as if it did not exist.
     */
    @Test
    void status_whenTheUploadBelongsToAnotherSystem_thenNotFound() throws Exception {
        UUID uploadId = uploaded();

        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId)
                        .param("system", "othersystem")
                        .with(authentication(tokenWithRoles(uploadsRole("othersystem", "write")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void status_whenTheRoleIsForAnotherSystem_thenForbidden() throws Exception {
        UUID uploadId = uploaded();

        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId)
                        .param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(uploadsRole("othersystem", "write")))))
                .andExpect(status().isForbidden());
    }

    private UUID uploaded() throws Exception {
        UUID uploadId = UUID.randomUUID();
        mockMvc.perform(uploadOf(uploadId, componentDocs(), bundle("# a component")).with(writeRole()))
                .andExpect(status().isCreated());
        return uploadId;
    }

    private static RequestPostProcessor writeRole() {
        return authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")));
    }
}
