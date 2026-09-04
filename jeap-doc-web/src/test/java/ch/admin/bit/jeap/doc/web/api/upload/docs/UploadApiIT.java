package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadApiIT extends DocServiceIntegrationTestBase {

    private static final String SYSTEM = "orders";

    @Autowired
    private MockMvc mockMvc;


    @Test
    void upload_whenSystemDocumentationInMarkdown_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    @Test
    void upload_whenComponentDocumentationInMarkdown_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(componentDocs()).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    @Test
    void upload_whenLibraryDocumentationInMarkdown_thenAccepted() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "library-docs");
        parameters.put("library", "orders-common-lib");
        parameters.put("version", "1.4.0");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    @Test
    void upload_whenComponentDocumentationInHtml_thenAccepted() throws Exception {
        mockMvc.perform(uploadOf(htmlComponentDocs()).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    @Test
    void upload_whenSiteIsGiven_thenAccepted() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("site", "governance");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    /**
     * Which sites exist is configuration. A typo in a doc workflow is refused here rather than answered with a
     * 201 and published nowhere - the failure nobody notices, because there is nothing to see.
     */
    @Test
    void upload_whenSiteIsNotConfigured_thenBadRequestNamingTheSitesThatAre() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("site", "catalog");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("catalog")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("governance")));
    }

    @Test
    void upload_whenSiteIsNoSlug_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("site", "Catalog");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("site")));
    }

    @Test
    void upload_whenMarkdownCarriesTheParametersOfHtmlDocuments_thenBadRequest() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.put("location", "6-runtime-view");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("location")));
    }

    /**
     * The reason the check for unknown parameters runs before the parameters are bound: a misspelled parameter
     * has to be reported as the typo it is, not as the well-spelled one it hides.
     */
    @Test
    void upload_whenAParameterIsMisspelled_thenTheTypoIsReported() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.remove("source-format");
        parameters.put("sourceFormat", "markdown");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("sourceFormat")));
    }

    @Test
    void upload_whenBundleIsLargerThanAccepted_thenPayloadTooLarge() throws Exception {
        mockMvc.perform(uploadOf(systemDocs())
                        .content(bundleOfAtLeast(128 * 1024))
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
    }

    @Test
    void upload_whenComponentIsMissing_thenBadRequestWithReason() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.remove("component");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("component")));
    }

    @Test
    void upload_whenVersionOfAComponentIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = componentDocs();
        parameters.remove("version");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("version")));
    }

    @Test
    void upload_whenLocationOfHtmlDocumentsIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = htmlComponentDocs();
        parameters.remove("location");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("location")));
    }

    @Test
    void upload_whenTemplateIsMissing_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.remove("template");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("template")));
    }

    @Test
    void upload_whenTypeIsUnknown_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "service-docs");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("system-docs")));
    }

    @Test
    void upload_whenSourceTimestampIsMalformed_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("source-timestamp", "yesterday");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"))
                .andExpect(jsonPath("$.detail").value(containsString("source-timestamp")));
    }

    @Test
    void upload_whenAParameterIsUnknown_thenBadRequest() throws Exception {
        Map<String, String> parameters = systemDocs();
        parameters.put("sourceFormat", "markdown");

        mockMvc.perform(uploadOf(parameters).with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("sourceFormat")));
    }

    @Test
    void upload_whenWriteRoleForAnotherSystem_thenForbidden() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(uploadsRole("other-system", "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenOnlyDocsReadRole_thenForbidden() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()).with(authentication(tokenWithRoles(docsRole("read")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenUnauthenticated_thenUnauthorized() throws Exception {
        mockMvc.perform(uploadOf(systemDocs()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Content-Length is mandatory: it lets an oversized bundle be rejected before it is transferred, it is what
     * the object storage is told to expect, and it is what a body cut short is recognised by.
     */
    @Test
    void upload_whenTheSizeIsNotAnnounced_thenLengthRequired() throws Exception {
        MockHttpServletRequestBuilder request = put(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID())
                .contentType("application/zip");
        systemDocs().forEach(request::param);

        mockMvc.perform(request.with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.code").value("LENGTH_REQUIRED"));
    }

    @Test
    void upload_whenBodyIsNoZip_thenUnsupportedMediaType() throws Exception {
        mockMvc.perform(uploadOf(systemDocs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
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
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/orders/orders-docs.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        parameters.put("build-url", "https://github.com/orders/orders-docs/actions/runs/1234567890");
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
        MockHttpServletRequestBuilder request = put(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID())
                .contentType("application/zip")
                .content(documentationSetBundle());
        parameters.forEach(request::param);
        return request;
    }

    /**
     * A bundle of at least the given size - the documents do not compress away, so the limit is really exceeded.
     */
    private static byte[] bundleOfAtLeast(int size) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SecureRandom random = new SecureRandom();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            byte[] content = new byte[size];
            random.nextBytes(content);
            zip.putNextEntry(new ZipEntry("6-runtime-view/diagram.png"));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
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
