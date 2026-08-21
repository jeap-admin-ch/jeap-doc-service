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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadApiIT extends DocServiceIntegrationTestBase {

    private static final String SYSTEM = "wvs";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void upload_whenSystemDocumentationInMarkdown_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isOk());
    }

    @Test
    void upload_whenComponentDocumentationInMarkdown_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(componentDocs()).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isOk());
    }

    @Test
    void upload_whenLibraryDocumentationInMarkdown_thenAccepted() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "library-docs");
        parameters.put("library", "wvs-common-lib");
        parameters.put("version", "1.4.0");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isOk());
    }

    @Test
    void upload_whenComponentDocumentationInHtml_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(htmlComponentDocs()).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isOk());
    }

    @Test
    void upload_whenComponentIsMissing_thenBadRequestWithReason() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.remove("component");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("component")));
    }

    @Test
    void upload_whenVersionOfAComponentIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.remove("version");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("version")));
    }

    @Test
    void upload_whenLocationOfHtmlDocumentsIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = htmlComponentDocs();
        parameters.remove("location");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("location")));
    }

    @Test
    void upload_whenTemplateIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.remove("template");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("template")));
    }

    @Test
    void upload_whenTypeIsUnknown_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "service-docs");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("system-docs")));
    }

    @Test
    void upload_whenSourceTimestampIsMalformed_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("source-timestamp", "yesterday");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("source-timestamp")));
    }

    @Test
    void upload_whenAParameterIsUnknown_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("sourceFormat", "markdown");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("sourceFormat")));
    }

    @Test
    void upload_whenWriteRoleForAnotherSystem_thenForbidden() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(docsRole("other-system", "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenOnlyReadRole_thenForbidden() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(docsRole(SYSTEM, "read")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenUnauthenticated_thenUnauthorized() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_whenBodyIsNoZip_thenUnsupportedMediaType() throws Exception {
        mockMvc.perform(uploadOf(systemDocs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(tokenWithRoles(docsRole(SYSTEM, "write")))))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * The parameters of a system documentation upload, as the doc workflow of a repository sends them.
     */
    private static Map<String, String> systemDocs() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", "system-docs");
        parameters.put("system", SYSTEM);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/wvs/wvs-docs.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        parameters.put("build-url", "https://jenkins.example.ch/job/wvs-docs/42/");
        parameters.put("generated-at", "2026-08-21T09:15:00+02:00");
        return parameters;
    }

    private static Map<String, String> componentDocs() {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "component-docs");
        parameters.put("component", "foo-bar-scs");
        parameters.put("version", "1.4.0");
        return parameters;
    }

    private static Map<String, String> htmlComponentDocs() {
        Map<String, String> parameters = componentDocs();
        parameters.put("source-format", "html");
        parameters.put("location", "6-runtime-view");
        parameters.put("topic", "spring-rest-docs");
        parameters.put("label", "Spring REST Docs");
        return parameters;
    }

    private static MockHttpServletRequestBuilder uploadOf(Map<String, String> parameters) {
        MockHttpServletRequestBuilder request = put("/api/uploads/{uploadId}", UUID.randomUUID())
                .contentType("application/zip")
                .content(documentationSetBundle());
        parameters.forEach(request::param);
        return request;
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
