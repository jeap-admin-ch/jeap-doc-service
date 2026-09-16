package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
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
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A system's first upload to a site with no architecture repository, as a reader finds it: through the systems
 * index, not by its URL.
 * <p>
 * Only the upload and the removal ask for builds here. Nothing else rebuilds such a site, so the index shows the
 * system only if the upload also asked for the shell.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstUploadBrowserIT extends BrowserTestBase {

    private static final String SITE = "first-upload";
    private static final String ROOT = "/site/" + SITE;
    private static final String SYSTEM = "ledger";
    private static final String HEADING = "What the ledger is for";
    private static final int TICKS_UNTIL_BUILT = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRunner runner;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "First Upload");
        // An environment no other class uses, and none a site defaults to.
        registry.add("jeap.doc.sites." + SITE + ".environments[0].id", () -> "kiosk");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].short-name", () -> "KIOSK");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].label", () -> "Kiosk");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].main", () -> "true");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].latest", () -> "true");
    }

    @Test
    @Order(1)
    void aSystemsFirstUpload_isListedInTheIndexAndReachedFromIt() throws Exception {
        // The site as it starts: a shell and no system.
        trigger.requestEveryPart(SITE);
        buildWhatIsOwed();

        upload();
        buildWhatIsOwed();

        open(ROOT + "/");
        PlaywrightAssertions.assertThat(page.locator("nav.menu a[href$='/systems/" + SYSTEM + "/']")).isVisible();

        open(ROOT + "/systems/");
        Locator listed = page.locator("article a[href$='/systems/" + SYSTEM + "/']").first();
        PlaywrightAssertions.assertThat(listed).isVisible();
        listed.click();
        page.waitForURL(url -> url.endsWith("/systems/" + SYSTEM + "/"));

        open(ROOT + "/systems/" + SYSTEM + "/system-architecture/intro/purpose/");
        PlaywrightAssertions.assertThat(page.locator("article h1")).hasText(HEADING);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    @Order(2)
    void removingASystemsDocumentation_takesItOffTheIndex() throws Exception {
        mockMvc.perform(delete("/api/docs/custom/systems").param("site", SITE).param("system", SYSTEM)
                        .with(authentication(tokenWithRoles(sitesRole("admin")))))
                .andExpect(status().isOk());
        buildWhatIsOwed();

        // The root page's sidebar lists every system. A site with no system left writes no systems index.
        open(ROOT + "/");
        PlaywrightAssertions.assertThat(page.locator("nav.menu a[href$='/systems/" + SYSTEM + "/']")).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    private void upload() throws Exception {
        MockHttpServletRequestBuilder request = put(UploadPaths.DOCS + "/{uploadId}", UUID.randomUUID())
                .contentType("application/zip").content(bundle());
        request.param("site", SITE)
                .param("type", "system-docs")
                .param("system", SYSTEM)
                .param("template", "arc42")
                .param("source-format", "markdown")
                .param("source-repository", "ssh://git@example.ch/ledger/ledger-docs.git")
                .param("source-revision", "beef1234")
                .param("source-ref", "main")
                .param("source-timestamp", "2026-09-01T09:12:00+02:00");
        mockMvc.perform(request.with(authentication(tokenWithRoles(uploadsRole(SYSTEM, "write")))))
                .andExpect(status().isCreated());
    }

    private static byte[] bundle() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("1-intro/purpose.md"));
            zip.write("""
                    ---
                    title: %s
                    description: written by the team
                    ---

                    # %s

                    The ledger keeps what was booked.
                    """.formatted(HEADING, HEADING).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private void buildWhatIsOwed() {
        for (int tick = 0; tick < TICKS_UNTIL_BUILT; tick++) {
            if (owedParts().isEmpty()) {
                return;
            }
            runner.runOnce();
        }
        throw new AssertionError("The parts of %s were still owed a build after %d ticks: %s"
                .formatted(SITE, TICKS_UNTIL_BUILT, owedParts()));
    }

    private List<String> owedParts() {
        return requests.pending().stream().filter(request -> request.site().equals(SITE))
                .map(request -> request.part().part()).toList();
    }
}
