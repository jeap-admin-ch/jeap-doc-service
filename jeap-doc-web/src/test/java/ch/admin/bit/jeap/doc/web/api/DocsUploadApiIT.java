package ch.admin.bit.jeap.doc.web.api;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocsUploadApiIT extends DocServiceIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void upload_whenWriteRoleForTheSystem_thenAccepted() throws Exception {
        mockMvc.perform(uploadRequest(UUID.randomUUID(), "wvs")
                        .with(authentication(tokenWithRoles(docsRole("wvs", "write")))))
                .andExpect(status().isOk());
    }

    @Test
    void upload_whenWriteRoleForAnotherSystem_thenForbidden() throws Exception {
        mockMvc.perform(uploadRequest(UUID.randomUUID(), "wvs")
                        .with(authentication(tokenWithRoles(docsRole("other-system", "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenOnlyReadRole_thenForbidden() throws Exception {
        mockMvc.perform(uploadRequest(UUID.randomUUID(), "wvs")
                        .with(authentication(tokenWithRoles(docsRole("wvs", "read")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenUnauthenticated_thenUnauthorized() throws Exception {
        mockMvc.perform(uploadRequest(UUID.randomUUID(), "wvs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_whenBodyIsNoZip_thenUnsupportedMediaType() throws Exception {
        mockMvc.perform(put("/api/docs/uploads/{uploadId}", UUID.randomUUID())
                        .param("system", "wvs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(tokenWithRoles(docsRole("wvs", "write")))))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void upload_whenSystemIsMissing_thenBadRequest() throws Exception {
        mockMvc.perform(put("/api/docs/uploads/{uploadId}", UUID.randomUUID())
                        .contentType("application/zip")
                        .content(documentationSetBundle())
                        .with(authentication(tokenWithRoles(docsRole("wvs", "write")))))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder uploadRequest(UUID uploadId, String system) {
        return put("/api/docs/uploads/{uploadId}", uploadId)
                .param("system", system)
                .contentType("application/zip")
                .content(documentationSetBundle());
    }

    private static byte[] documentationSetBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("1-intro/why-we-built-this.md"));
            zip.write("# Why we built this".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
