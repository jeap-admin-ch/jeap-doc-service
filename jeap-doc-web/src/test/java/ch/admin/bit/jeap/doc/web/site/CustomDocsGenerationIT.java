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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.assertj.core.api.Assertions.assertThat;
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
    private static final String ASSET_COMPONENT = "catalog-media";
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

    /** A microsite as a build publishes one: an entry point, and a page below it. */
    private static byte[] micrositeBundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, "index.html", "<!doctype html><title>Configuration</title><h1>Configuration</h1>");
            write(zip, "pages/timeouts.html",
                    "<!doctype html><title>Timeouts</title><h1>Timeouts</h1><p>Every request is idempotent.</p>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Map<String, String> micrositeDocs() {
        Map<String, String> parameters = systemDocs();
        parameters.put("source-format", "html");
        parameters.put("location", "8-crosscutting-concepts");
        parameters.put("topic", "configuration-reference");
        parameters.put("label", "Configuration Reference");
        return parameters;
    }

    private void upload(Map<String, String> parameters, byte[] bundle) throws Exception {
        MockHttpServletRequestBuilder request =
                put(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID()).contentType("application/zip")
                        .content(bundle);
        parameters.forEach(request::param);
        mockMvc.perform(request.with(authentication(tokenWithRoles(
                        uploadsRole(parameters.getOrDefault("system", SYSTEM), "write")))))
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
                .andExpect(content().string(containsString("Uploaded")))
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

    /**
     * <b>What a page shows or links to arrives with it, from a folder inside the chapter.</b> The upload takes
     * the files, the writer puts them in their folders, and Docusaurus has to resolve every reference relative
     * to the page and emit each file under a URL of its own - which only a real build says, and only the served
     * files prove.
     * <p>
     * <b>The picture is over 10 KB on purpose.</b> Docusaurus inlines a smaller image into the page as a data
     * URL, and an inlined picture would pass without any file having been published.
     */
    @Test
    @Order(50)
    void theAssetsOfAPage_areServedFromTheFoldersTheyWereUploadedIn() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("1-intro/pictures.md", ("""
                ---
                title: Pictures and files
                description: written by the team
                ---

                # Pictures and files

                ![The overview](img/diagrams/overview.png)

                [The specification](files/spec.pdf), [a sample](files/data/sample.json) and
                [the configuration](config.yaml).
                """).getBytes(StandardCharsets.UTF_8));
        files.put("1-intro/img/diagrams/overview.png", picture());
        files.put("1-intro/files/spec.pdf", "%PDF-1.4\n% the specification\n".getBytes(StandardCharsets.UTF_8));
        files.put("1-intro/files/data/sample.json", "{\"sample\": true}\n".getBytes(StandardCharsets.UTF_8));
        files.put("1-intro/config.yaml", "timeout: 5s\n".getBytes(StandardCharsets.UTF_8));
        Map<String, String> parameters = componentDocs();
        parameters.put("component", ASSET_COMPONENT);
        upload(parameters, zipOf(files));

        String route = BASE + "/systems/" + SYSTEM + "/system-architecture/building-block-view/components/"
                       + ASSET_COMPONENT + "/component-architecture/intro/pictures/";
        buildUntilServed(route);
        String page = mockMvc.perform(get(route)).andReturn().getResponse().getContentAsString();

        assertServed(page, "src", "overview", "png", files.get("1-intro/img/diagrams/overview.png"), "image/png");
        assertServed(page, "href", "spec", "pdf", files.get("1-intro/files/spec.pdf"), "application/pdf");
        assertServed(page, "href", "sample", "json", files.get("1-intro/files/data/sample.json"),
                "application/json");
        assertServed(page, "href", "config", "yaml", files.get("1-intro/config.yaml"), "application/yaml");
    }

    /**
     * The file the page references is published as a file of its own and served as it was uploaded.
     * <p>
     * <b>Followed the way a browser follows it.</b> Docusaurus names the emitted file after the uploaded one
     * with a content hash, writes the attribute unquoted where it can, and gives a link the trailing slash of a
     * route - which the service answers with a redirect to the file.
     */
    private void assertServed(String page, String attribute, String name, String extension, byte[] uploaded,
                              String mediaType) throws Exception {
        Matcher matcher = Pattern.compile(attribute + "=\"?([^\"\\s>]*/" + name + "[^\"\\s>/]*\\." + extension
                                          + "/?)[\"\\s>]").matcher(page);
        assertThat(matcher.find()).describedAs("the page references %s.%s", name, extension).isTrue();
        String location = matcher.group(1);
        assertThat(location).describedAs("published as a file of its own rather than inlined into the page")
                .startsWith(BASE + "/");

        MockHttpServletResponse served = mockMvc.perform(get(location)).andReturn().getResponse();
        if (served.getStatus() == 301) {
            location = served.getHeader("Location");
            served = mockMvc.perform(get(location)).andReturn().getResponse();
        }
        assertThat(served.getStatus()).describedAs("%s is served", location).isEqualTo(200);
        assertThat(served.getContentType()).describedAs("the media type of %s", location).contains(mediaType);
        assertThat(served.getContentAsByteArray()).describedAs("%s as it was uploaded", location)
                .isEqualTo(uploaded);
    }

    private static byte[] zipOf(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** A PNG of noise, which does not compress below the size Docusaurus would inline. */
    private static byte[] picture() {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * <b>A chapter whose only documentation is a microsite is still a chapter.</b> An HTML set has no page
     * rows by design - it is served file by file - so nothing but the microsite itself says that its chapter
     * exists, and the page that frames it is the only thing the reader can reach it through.
     */
    @Test
    @Order(60)
    void anUploadedMicrosite_isFramedByAPageInTheChapterItNames() throws Exception {
        upload(micrositeDocs(), micrositeBundle());

        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/crosscutting-concepts/"
                         + "microsites/configuration-reference/");

        String page = mockMvc.perform(get(BASE + "/systems/" + SYSTEM + "/system-architecture/"
                                          + "crosscutting-concepts/microsites/configuration-reference/"))
                .andReturn().getResponse().getContentAsString();
        // Named by the label the upload gave it. Where its frame points is not in this HTML: the frame is
        // rendered once the page has mounted, and MicrositeBrowserIT asserts it in a browser.
        assertThat(page).describedAs("the page is the one for the microsite the upload named")
                .contains("<title data-rh=true>Configuration Reference");
        // Under the site it belongs to: the page carries the path within the service, and the site's own base
        // URL - its context path, and the /site/<id>/ of a site that is not the default one - stands in front.
        assertThat(mockMvc.perform(get(BASE + "/microsites/" + SYSTEM + "/arc42/8-crosscutting-concepts/"
                                       + "configuration-reference/pages/timeouts.html"))
                .andReturn().getResponse().getStatus())
                .describedAs("and its files are served under the site it belongs to").isEqualTo(200);
    }

    /**
     * <b>A subject whose only documentation is a microsite is still a subject.</b> A system nothing has
     * deployed, a component the model does not hold, and a library - none of them has a page row, so nothing
     * but the microsite says they exist, and each one's tree has to be written for its framing page to be
     * reachable at all.
     */
    @Test
    @Order(70)
    void aSubjectDocumentedOnlyByAMicrosite_isPublishedWithItsFramingPage() throws Exception {
        Map<String, String> unknownSystem = micrositeDocs();
        unknownSystem.put("system", "atlas");
        unknownSystem.put("topic", "reference");
        upload(unknownSystem, micrositeBundle());
        Map<String, String> component = micrositeDocs();
        component.put("type", "component-docs");
        component.put("component", "catalog-reports");
        component.put("version", "1.2.0");
        component.put("topic", "reference");
        upload(component, micrositeBundle());
        Map<String, String> library = micrositeDocs();
        library.put("type", "library-docs");
        library.put("library", "catalog-client");
        library.put("version", "3.1.0");
        library.put("topic", "reference");
        upload(library, micrositeBundle());

        buildUntilServed(BASE + "/systems/atlas/system-architecture/crosscutting-concepts/microsites/reference/");
        buildUntilServed(BASE + "/systems/" + SYSTEM + "/system-architecture/building-block-view/components/"
                         + "catalog-reports/component-architecture/crosscutting-concepts/microsites/reference/");
        buildUntilServed(BASE + LIBRARY_TREE + "crosscutting-concepts/microsites/reference/");
    }

    /**
     * <b>And a library documented only by a microsite still says what its upload said.</b> The overview reads
     * the version and the provenance of the library's set, and a lookup narrowed to Markdown found none - so
     * the page said nothing about a library whose upload had stated its version.
     */
    @Test
    @Order(71)
    void aLibraryDocumentedOnlyByAMicrosite_hasAnOverviewWithItsVersionAndProvenance() throws Exception {
        buildUntilServed(BASE + LIBRARY_TREE + "intro/library-overview/");

        mockMvc.perform(get(BASE + LIBRARY_TREE + "intro/library-overview/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3.1.0")))
                .andExpect(content().string(containsString("catalog-docs.git")));
    }

    /** Where the library documented only by a microsite is published. */
    private static final String LIBRARY_TREE = "/systems/" + SYSTEM + "/system-architecture/building-block-view/"
                                               + "libraries/catalog-client/library-architecture/";
}
