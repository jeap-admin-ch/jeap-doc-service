package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The story end to end: a team uploads documentation, a build runs, and a reader gets the pages.
 * <p>
 * <b>A site of its own, with no architecture repository at all.</b> Everything served here came out of an
 * upload, so nothing that passes can be coming from the architecture model - which is what makes this the
 * test of the seam rather than of the generator. It drives the real endpoint, the real runner and the real
 * Docusaurus build, and reads the HTML the service serves.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomDocsGenerationIT extends DocServiceIntegrationTestBase {

    private static final String SITE = "uploaded";
    private static final String SYSTEM = "catalog";
    private static final String COMPONENT = "catalog-search";
    private static final String BASE = "/site/" + SITE;

    /** A tick builds one part, and this site has a shell and one system. */
    private static final int ROUNDS_UNTIL_SERVED = 8;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRunner runner;

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "Uploaded Documentation");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].id", () -> "abn");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].short-name", () -> "ABN");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].label", () -> "Acceptance");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].main", () -> "true");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].latest", () -> "true");
    }

    private static byte[] bundleOf(Map<String, String> pagesAndTitles) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> page : pagesAndTitles.entrySet()) {
                ZipEntry entry = new ZipEntry(page.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(("""
                        ---
                        title: %s
                        description: written by the team
                        ---

                        # %s

                        %s
                        """).formatted(page.getValue(), page.getValue(), bodyOf(page.getValue()))
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** A sentence a test can search the served page for. */
    private static String bodyOf(String title) {
        return "The team wrote this about " + title + ".";
    }

    private void upload(Map<String, String> parameters, byte[] bundle) throws Exception {
        MockHttpServletRequestBuilder request =
                put(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID()).contentType("application/zip")
                        .content(bundle);
        parameters.forEach(request::param);
        mockMvc.perform(request.with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    private static Map<String, String> systemDocs() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("site", SITE);
        parameters.put("type", "system-docs");
        parameters.put("system", SYSTEM);
        parameters.put("template", "arc42");
        parameters.put("source-format", "markdown");
        parameters.put("source-repository", "ssh://git@example.ch/catalog/catalog-docs.git");
        parameters.put("source-revision", "beef1234");
        parameters.put("source-ref", "main");
        parameters.put("source-timestamp", "2026-09-01T09:12:00+02:00");
        return parameters;
    }

    private static Map<String, String> componentDocs() {
        Map<String, String> parameters = systemDocs();
        parameters.put("type", "component-docs");
        parameters.put("component", COMPONENT);
        parameters.put("version", "2.1.0");
        return parameters;
    }

    /**
     * Builds until the probe answers 200.
     * <p>
     * <b>Probe the trailing-slash URL.</b> A Docusaurus page is served at {@code …/goals/} and the service
     * answers {@code 301} for the path without it, so a probe without the slash never reaches 200 however
     * many rounds it is given.
     */
    private void buildUntilServed(String probe) throws Exception {
        int status = 0;
        for (int round = 0; round < ROUNDS_UNTIL_SERVED; round++) {
            trigger.requestEveryPart(SITE);
            runner.runOnce();
            status = mockMvc.perform(get(probe)).andReturn().getResponse().getStatus();
            if (status == 200) {
                return;
            }
        }
        throw new AssertionError("%s was not served after %d rounds; it answers %d."
                .formatted(probe, ROUNDS_UNTIL_SERVED, status));
    }

    @Test
    @Order(1)
    void anUploadedSet_isServedInTheChapterItsFolderNames() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("1-intro/goals.md", "What we are building");
        pages.put("2-constraints/given.md", "What was given");
        pages.put("12-glossary/terms.md", "Terms");
        upload(systemDocs(), bundleOf(pages));
        upload(componentDocs(), bundleOf(Map.of("1-intro/why.md", "Why the search exists")));

        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/goals/");

        // The page is where its chapter folder says, with the number prefix stripped from the URL.
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/goals/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(bodyOf("What we are building"))));
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/constraints/given/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(bodyOf("What was given"))));
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/terms/"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void aChapterNothingGenerates_getsItsFolderAndALandingPage() throws Exception {
        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/");

        // Chapter 12 is one of the eight the generator never writes, so it exists only because a page was
        // uploaded into it - and a reader who opens it lands on something.
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Glossary")));
    }

    @Test
    @Order(3)
    void aServedPage_saysWhereItCameFrom() throws Exception {
        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/goals/");

        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/goals/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Written by the team")))
                .andExpect(content().string(containsString("catalog-docs.git")))
                .andExpect(content().string(containsString("beef1234")));
    }

    @Test
    @Order(4)
    void aComponentNoArchitectureModelKnows_isServedAllTheSame() throws Exception {
        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/building-block-view/components/"
                         + COMPONENT + "/component-architecture/intro/why/");

        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/building-block-view/"
                            + "components/" + COMPONENT + "/component-architecture/intro/why/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(bodyOf("Why the search exists"))));

        // And the page saying why the rest is missing, which is what a subject with no model entry gets.
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/"
                            + "not-in-the-architecture-model/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("does not hold the system")));
    }

    /**
     * A set replaces its predecessor whole, so a page a team deleted stops being served. It is the one thing
     * a merge would not do, and it costs a rebuild to see.
     */
    @Test
    @Order(5)
    void aSmallerSet_takesThePageThatIsGoneOffTheSite() throws Exception {
        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/terms/");

        Map<String, String> smaller = new LinkedHashMap<>();
        smaller.put("1-intro/goals.md", "What we are building");
        smaller.put("2-constraints/given.md", "What was given");
        upload(systemDocs(), bundleOf(smaller));

        for (int round = 0; round < ROUNDS_UNTIL_SERVED; round++) {
            trigger.requestEveryPart(SITE);
            runner.runOnce();
            if (mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/terms/"))
                    .andReturn().getResponse().getStatus() == 404) {
                break;
            }
        }

        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/glossary/terms/"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/intro/goals/"))
                .andExpect(status().isOk());
    }
}
