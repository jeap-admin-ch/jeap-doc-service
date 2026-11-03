package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadDescriptor;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationUploadService;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A static site, uploaded, built by the runner, served by the service and read in a browser: one tree at the
 * site root, its uploaded folders as the navigation, with no environment switcher, no banner, and a search that
 * finds its pages.
 * <p>
 * It has a site of its own, because the classes of this module share one database.
 */
class SiteWithoutEnvironmentsBrowserIT extends BrowserTestBase {

    private static final String SITE = "handbook-without-environments";

    private static final String ROOT = "/site/" + SITE;

    /** A word on the root page and nowhere else, so a search for it can only find that page. */
    private static final String ONLY_ON_THE_ROOT_PAGE = "zeppelin";

    /** A word on the one uploaded page, so a search for it can only find that page. */
    private static final String ONLY_ON_THE_UPLOADED_PAGE = "dirigible";

    /** How many ticks the site is given before the suite gives up. */
    private static final int TICKS_UNTIL_BUILT = 5;

    /** Built once for the class: a Docusaurus build takes most of a minute, and every test only reads it. */
    private static boolean published;

    @Autowired
    private DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRunner runner;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @Autowired
    private DocumentationUploadService uploads;

    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "Handbook");
        registry.add("jeap.doc.sites." + SITE + ".tagline", () -> "How the " + ONLY_ON_THE_ROOT_PAGE
                                                                   + " crew works");
        registry.add("jeap.doc.sites." + SITE + ".per-environment", () -> false);
        registry.add("jeap.doc.sites." + SITE + ".content", () -> "static-site");
    }

    @Override
    protected void prepareWhatIsServed() {
        if (published) {
            return;
        }
        uploadTheTree();
        trigger.requestEveryPart(SITE);
        for (int tick = 0; tick < TICKS_UNTIL_BUILT && isOwedABuild(); tick++) {
            runner.runOnce();
        }
        assertThat(isOwedABuild()).describedAs("the site is still owed a build after %d ticks", TICKS_UNTIL_BUILT)
                .isFalse();
        published = true;
    }

    /** The site's tree, uploaded the way its pipeline would - a folder with a label and one page in it. */
    private void uploadTheTree() {
        byte[] bundle = zipOf(Map.of(
                "intro/_category_.json", "{\"label\": \"Introduction\", \"position\": 1}",
                "intro/why.md", "---\ntitle: Why We Fly\n---\n\nA " + ONLY_ON_THE_UPLOADED_PAGE
                                + " is slow, and that is the point.\n"));
        DocumentationUploadDescriptor descriptor = DocumentationUploadDescriptor.builder()
                .site(SITE)
                .type(DocumentationType.SITE_DOCS)
                .sourceFormat(SourceFormat.MARKDOWN)
                .sourceRepository("ssh://git@bitbucket.example.ch/handbook/handbook.git")
                .sourceRevision("9a1c2f8")
                .sourceRef("main")
                .sourceTimestamp(Instant.parse("2026-10-01T08:00:00Z"))
                .build();
        uploads.receive(UUID.randomUUID(), descriptor, new ByteArrayInputStream(bundle), bundle.length);
    }

    private static byte[] zipOf(Map<String, String> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private boolean isOwedABuild() {
        return requests.pending().stream().anyMatch(request -> request.site().equals(SITE));
    }

    @Test
    void rootPage_thenItIsServedAtTheSiteRootWithNoSwitcherAndNoBanner() {
        Response served = open(ROOT + "/");

        assertThat(served.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Handbook").setLevel(1))).isVisible();
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Switch environment").setExact(false))).hasCount(0);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.NOTE)).hasCount(0);
        PlaywrightAssertions.assertThat(page.locator("meta[name=robots]")).hasCount(0);
        assertThat(open(ROOT + "/about-this-documentation/").status()).isEqualTo(200);
        assertNothingWentWrongInTheBrowser();
    }

    /** The search is not scoped to an environment, so it finds what the one tree carries. */
    @Test
    void searchBox_whenAWordOfThePageIsTyped_thenTheHitLeadsToIt() {
        open(ROOT + "/about-this-documentation/");
        Locator box = page.locator("input.navbar__search-input").first();
        PlaywrightAssertions.assertThat(box).hasAttribute("placeholder", "Search the documentation");

        box.click();
        box.pressSequentially(ONLY_ON_THE_ROOT_PAGE, new Locator.PressSequentiallyOptions().setDelay(40));
        Locator hit = page.getByRole(AriaRole.OPTION).first();
        PlaywrightAssertions.assertThat(hit).isVisible();
        hit.click();

        page.waitForURL(url(ROOT + "/"));
        PlaywrightAssertions.assertThat(page.getByText("How the " + ONLY_ON_THE_ROOT_PAGE + " crew works"))
                .isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** The uploaded folder is in the navigation, with its label and its page, and the page is served. */
    @Test
    void navigation_thenTheUploadedFoldersAreInItAndTheirPagesAreServed() {
        open(ROOT + "/");

        Locator sidebar = page.locator("nav.menu");
        PlaywrightAssertions.assertThat(sidebar.getByText("Introduction")).isVisible();
        assertThat(open(ROOT + "/intro/why/").status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Why We Fly").setLevel(1))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void searchPage_whenAWordOfAnUploadedPageIsSearched_thenItIsFound() {
        open(ROOT + "/search/?q=" + ONLY_ON_THE_UPLOADED_PAGE);

        Locator hits = page.getByRole(AriaRole.LIST, new Page.GetByRoleOptions().setName("Search results"))
                .locator("li a[href]");
        PlaywrightAssertions.assertThat(hits).hasCount(1);
        assertThat(hits.first().getAttribute("href")).endsWith(ROOT + "/intro/why/");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * This instance serves several sites, so the header bar has a tab for each, this one marked, and the title
     * of the default site beside the logo - while the browser tab still names this site.
     */
    @Test
    void headerBar_thenATabPerSiteWithThisOneMarked() {
        open(ROOT + "/");

        Locator tabs = page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Sites"))
                .getByRole(AriaRole.LINK);
        PlaywrightAssertions.assertThat(tabs.first()).isVisible();
        PlaywrightAssertions.assertThat(page.locator("a[aria-current=page]", new Page.LocatorOptions()
                .setHasText("Handbook")).first()).hasAttribute("href", ROOT + "/");
        assertThat(tabs.allTextContents()).contains("Documentation", "Governance", "Handbook");
        PlaywrightAssertions.assertThat(page.locator(".navbar__title")).hasText("Documentation");
        assertThat(page.title()).contains("Handbook");
        assertNothingWentWrongInTheBrowser();
    }

    /** On a narrow screen the tabs leave the bar and are in the navigation menu. */
    @Test
    void headerBar_whenTheScreenIsNarrow_thenTheTabsAreInTheMenu() {
        page.setViewportSize(390, 844);
        open(ROOT + "/");

        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.NAVIGATION,
                new Page.GetByRoleOptions().setName("Sites"))).isHidden();
        page.locator("button.navbar__toggle").click();
        // A page of the documentation opens the menu on its own sidebar; the bar's items are the main menu.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Back to main menu")).click();
        Locator governance = page.locator(".navbar-sidebar").getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName("Governance"));
        PlaywrightAssertions.assertThat(governance).isVisible();
        PlaywrightAssertions.assertThat(governance).isInViewport();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void searchPage_whenOpenedWithAQuery_thenItFindsThePage() {
        open(ROOT + "/search/?q=" + ONLY_ON_THE_ROOT_PAGE);

        Locator hits = page.getByRole(AriaRole.LIST, new Page.GetByRoleOptions().setName("Search results"))
                .locator("li a[href]");
        PlaywrightAssertions.assertThat(hits).hasCount(1);
        assertThat(hits.first().getAttribute("href")).endsWith(ROOT + "/");
        assertNothingWentWrongInTheBrowser();
    }
}
