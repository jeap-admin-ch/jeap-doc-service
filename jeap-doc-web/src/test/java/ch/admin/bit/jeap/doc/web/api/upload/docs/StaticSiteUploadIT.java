package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.docs.DocsPaths;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tree of a static site, uploaded for the site itself: it names no system, it replaces the site's previous
 * tree whole, it is checked like every other upload, and only a static site takes it - with a role granted for
 * that site.
 * <p>
 * Static sites of its own, because the classes of this module share one database.
 */
class StaticSiteUploadIT extends DocServiceIntegrationTestBase {

    private static final String HANDBOOK = "static-upload-handbook";
    private static final String OTHER = "static-upload-other";
    private static final String REMOVED = "static-upload-removed";
    private static final String KEPT = "static-upload-kept";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomDocumentationRepository documentation;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        for (String site : new String[]{HANDBOOK, OTHER, REMOVED, KEPT}) {
            registry.add("jeap.doc.sites." + site + ".per-environment", () -> false);
            registry.add("jeap.doc.sites." + site + ".content", () -> "static-site");
        }
    }

    @Test
    void upload_thenTheTreeIsTheSitesDocumentationAsItIs() throws Exception {
        upload(HANDBOOK, siteDocs(HANDBOOK), siteUploadsRole(HANDBOOK), bundleOf(Map.of(
                "index.md", "# Handbook\n",
                "intro/_category_.json", "{\"label\": \"Introduction\"}",
                "intro/why.md", "# Why\n")))
                .andExpect(status().isCreated());

        CustomSet set = documentation.find(CustomSetKey.ofSite(HANDBOOK)).orElseThrow();
        assertThat(set.pages()).extracting(CustomPage::path)
                .containsExactlyInAnyOrder("index.md", "intro/_category_.json", "intro/why.md");
        assertThat(set.subject().system()).isNull();
        assertThat(requests.pending()).extracting(BuildRequest::part)
                .describedAs("the upload asks for the site's one part").contains(PartKey.shellOf(HANDBOOK));
    }

    /** A tree is the whole site: a second one replaces the first, and nothing of the first is left. */
    @Test
    void upload_whenTheSiteHasATreeAlready_thenTheNewOneReplacesItWhole() throws Exception {
        upload(OTHER, siteDocs(OTHER), siteUploadsRole(OTHER), bundleOf(Map.of(
                "old/page.md", "# Old\n", "kept.md", "# Kept\n")))
                .andExpect(status().isCreated());

        upload(OTHER, siteDocs(OTHER), siteUploadsRole(OTHER), bundleOf(Map.of(
                "new/page.md", "# New\n", "kept.md", "# Kept again\n")))
                .andExpect(status().isCreated());

        assertThat(documentation.find(CustomSetKey.ofSite(OTHER)).orElseThrow().pages())
                .extracting(CustomPage::path).containsExactlyInAnyOrder("new/page.md", "kept.md");
    }

    @Test
    void upload_whenItNamesASystem_thenItIsRefused() throws Exception {
        Map<String, String> parameters = siteDocs(HANDBOOK);
        parameters.put("system", "orders");

        upload(HANDBOOK, parameters, siteUploadsRole(HANDBOOK), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    @Test
    void upload_whenItNamesATemplate_thenItIsRefused() throws Exception {
        Map<String, String> parameters = siteDocs(HANDBOOK);
        parameters.put("template", "arc42");

        upload(HANDBOOK, parameters, siteUploadsRole(HANDBOOK), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** A site whose content is the documentation of the systems has no tree of its own to take. */
    @Test
    void upload_whenTheSiteIsNotAStaticSite_thenItIsRefused() throws Exception {
        upload("default", siteDocs("default"), siteUploadsRole("default"), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** And a static site documents no system, so a system's documentation is refused there. */
    @Test
    void upload_whenASystemsDocumentationNamesAStaticSite_thenItIsRefused() throws Exception {
        Map<String, String> parameters = DocumentationUploads.systemDocs();
        parameters.put("site", HANDBOOK);

        upload(HANDBOOK, parameters, uploadsRole(DocumentationUploads.SYSTEM, "write"),
                bundleOf(Map.of("1-introduction-and-goals/goals.md", "# Goals\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** The existing rules apply: a page written in MDX is not published, so the tree is refused. */
    @Test
    void upload_whenTheTreeBreaksARule_thenItIsRefusedWithTheFindings() throws Exception {
        upload(HANDBOOK, siteDocs(HANDBOOK), siteUploadsRole(HANDBOOK), bundleOf(Map.of(
                "intro/why.mdx", "# Why\n")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.findings[0].code").value("FORBIDDEN_EXTENSION"));
    }

    @Test
    void upload_whenTheRoleIsForAnotherSite_thenForbidden() throws Exception {
        upload(HANDBOOK, siteDocs(HANDBOOK), siteUploadsRole(OTHER), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_whenTheRoleIsASystemsUploadRole_thenForbidden() throws Exception {
        upload(HANDBOOK, siteDocs(HANDBOOK), uploadsRole(HANDBOOK, "write"), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isForbidden());
    }

    /** Granted without a tenant, the site-level role covers every static site. */
    @Test
    void upload_whenTheRoleHasNoTenant_thenEveryStaticSiteMayBeUploadedTo() throws Exception {
        upload(HANDBOOK, siteDocs(HANDBOOK), siteUploadsRole(null), bundleOf(Map.of("index.md", "# Handbook\n")))
                .andExpect(status().isCreated());
    }

    @Test
    void status_whenTheUploadIsASitesTree_thenItIsReadForTheSite() throws Exception {
        UUID uploadId = UUID.randomUUID();
        mockMvc.perform(DocumentationUploads.uploadOf(uploadId, siteDocs(HANDBOOK), bundleOf(Map.of("a.md", "# A\n")))
                        .with(authentication(tokenWithRoles(siteUploadsRole(HANDBOOK)))))
                .andExpect(status().isCreated());

        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId).param("site", HANDBOOK)
                        .with(authentication(tokenWithRoles(siteUploadsRole(HANDBOOK)))))
                .andExpect(status().isOk());
        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId).param("site", HANDBOOK)
                        .with(authentication(tokenWithRoles(siteUploadsRole(OTHER)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(UploadPaths.DOCS + "/{uploadId}", uploadId).param("system", "orders")
                        .with(authentication(tokenWithRoles(uploadsRole("orders", "write")))))
                .andExpect(status().isNotFound());
    }

    /** The dry run answers what the upload would: a site's tree is checked for the site. */
    @Test
    void validation_whenATreeIsCheckedForAStaticSite_thenItIsAnsweredWithTheSameRules() throws Exception {
        mockMvc.perform(post(UploadPaths.DOCS + DocumentationValidationController.VALIDATION_PATH)
                        .param("site", HANDBOOK).param("type", "site-docs").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\": [\"index.md\", \"intro/_category_.json\", \"intro/why.md\"]}")
                        .with(authentication(tokenWithRoles(siteUploadsRole(HANDBOOK)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("static-site"))
                .andExpect(jsonPath("$.findings").isEmpty());
        mockMvc.perform(post(UploadPaths.DOCS + DocumentationValidationController.VALIDATION_PATH)
                        .param("site", HANDBOOK).param("type", "site-docs").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\": [\"intro/why.mdx\"]}")
                        .with(authentication(tokenWithRoles(siteUploadsRole(HANDBOOK)))))
                .andExpect(status().isUnprocessableContent());
    }

    /** The dry run refuses what the upload refuses: a tree for a site that is not a static site. */
    @Test
    void validation_whenTheSiteIsNotAStaticSite_thenItIsRefusedAsTheUploadWouldBe() throws Exception {
        mockMvc.perform(post(UploadPaths.DOCS + DocumentationValidationController.VALIDATION_PATH)
                        .param("site", "default").param("type", "site-docs").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\": [\"index.md\"]}")
                        .with(authentication(tokenWithRoles(siteUploadsRole("default")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /** The dry run refuses what the upload refuses: a system's documentation for a static site. */
    @Test
    void validation_whenASystemsDocumentationIsCheckedForAStaticSite_thenItIsRefusedAsTheUploadWouldBe()
            throws Exception {
        mockMvc.perform(post(UploadPaths.DOCS + DocumentationValidationController.VALIDATION_PATH)
                        .param("site", HANDBOOK).param("type", "system-docs").param("system", "orders")
                        .param("template", "arc42").param("source-format", "markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\": [\"1-introduction-and-goals/goals.md\"]}")
                        .with(authentication(tokenWithRoles(uploadsRole("orders", "write")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    /**
     * A site's tree is removed by its own endpoint only. The removal of a set or a subject is authorized by a
     * system's role, so it refuses a site's tree - even for the administrator - and the tree stays.
     */
    @Test
    void remove_whenASetOrSubjectRemovalNamesASitesTree_thenItIsRefusedAndTheTreeStays() throws Exception {
        upload(KEPT, siteDocs(KEPT), siteUploadsRole(KEPT), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isCreated());

        mockMvc.perform(delete(DocsPaths.SETS).param("site", KEPT).param("type", "site-docs")
                        .param("system", "").param("template", "").param("source-format", "markdown")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
        mockMvc.perform(delete(DocsPaths.SUBJECTS).param("site", KEPT).param("type", "site-docs")
                        .param("system", "")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));

        assertThat(documentation.find(CustomSetKey.ofSite(KEPT))).isPresent();
    }

    /**
     * A site's tree can be taken away again, by the role that uploads it: the site is then published with its
     * generated root page until a tree is uploaded again.
     */
    @Test
    void remove_whenTheSiteHasATree_thenItIsGoneAndASecondRemovalFindsNothing() throws Exception {
        upload(REMOVED, siteDocs(REMOVED), siteUploadsRole(REMOVED), bundleOf(Map.of("a.md", "# A\n")))
                .andExpect(status().isCreated());

        mockMvc.perform(delete(DocsPaths.SITES).param("site", REMOVED)
                        .with(authentication(tokenWithRoles(siteUploadsRole(REMOVED)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setsRemoved").value(1));

        assertThat(documentation.find(CustomSetKey.ofSite(REMOVED))).isEmpty();
        mockMvc.perform(delete(DocsPaths.SITES).param("site", REMOVED)
                        .with(authentication(tokenWithRoles(siteUploadsRole(REMOVED)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void remove_whenTheRoleIsForAnotherSiteOrASystem_thenForbidden() throws Exception {
        mockMvc.perform(delete(DocsPaths.SITES).param("site", HANDBOOK)
                        .with(authentication(tokenWithRoles(siteUploadsRole(OTHER)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(DocsPaths.SITES).param("site", HANDBOOK)
                        .with(authentication(tokenWithRoles(uploadsRole(HANDBOOK, "write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void remove_whenTheSiteIsNotAStaticSite_thenItIsRefused() throws Exception {
        mockMvc.perform(delete(DocsPaths.SITES).param("site", "default")
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_VALUE"));
    }

    private ResultActions upload(String site, Map<String, String> parameters, SemanticApplicationRole role,
                                 byte[] bundle) throws Exception {
        parameters.putIfAbsent("site", site);
        return mockMvc.perform(DocumentationUploads.uploadOf(UUID.randomUUID(), parameters, bundle)
                .with(authentication(tokenWithRoles(role))));
    }

    /** The parameters of a static site's tree, as its doc workflow sends them: no system and no template. */
    private static Map<String, String> siteDocs(String site) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("site", site);
        parameters.put("type", "site-docs");
        parameters.put("source-format", "markdown");
        parameters.put("source-repository", "ssh://git@bitbucket.example.ch/handbook/handbook.git");
        parameters.put("source-revision", "9a1c2f8");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-08-21T09:12:00+02:00");
        return parameters;
    }

    private static byte[] bundleOf(Map<String, String> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
