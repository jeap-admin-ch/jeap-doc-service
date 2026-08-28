package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * What the site template does once a browser runs it.
 * <p>
 * Every case here corresponds to a claim the template makes or to a defect that has already happened and that
 * no assertion over the generated markup could have caught.
 */
class SiteTemplateBrowserIT extends SiteBrowserTestBase {

    private static final String GUIDE_TITLE = "The upload guide";

    /**
     * The switcher is a hover dropdown, and hover is available neither to a keyboard nor to a tablet. It is the
     * only navigation control in the navbar, on documentation published under admin.ch - so opening it by
     * clicking it is the minimum, and the handler that does went missing for nine review rounds because nothing
     * ran the component.
     */
    @Test
    void switcher_whenClicked_thenTheEnvironmentsAreReachable() {
        open("/");

        assertThat(switcher()).hasAttribute("aria-expanded", "false");
        switcher().click();

        assertThat(switcher()).hasAttribute("aria-expanded", "true");
        for (SiteEnvironment environment : environments()) {
            assertThat(environmentLink(environment)).isVisible();
        }
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * Escape closes it, which is how a keyboard user leaves a menu. The handlers sit on the button and on the
     * links rather than on the wrapper around them - an arrangement a test that only clicked could not tell
     * apart from a broken one.
     */
    @Test
    void switcher_whenOpenedFromTheKeyboard_thenEscapeClosesItAgain() {
        open("/");

        switcher().focus();
        page.keyboard().press("Enter");
        assertThat(switcher()).hasAttribute("aria-expanded", "true");

        page.keyboard().press("Escape");
        assertThat(switcher()).hasAttribute("aria-expanded", "false");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * Switching environment keeps the reader on the page they are reading. The href is composed from the base
     * url, the environment's route prefix and the path with its current prefix removed - three values that have
     * to agree with what the service serves, which is why this runs against the service.
     */
    @Test
    void switcher_whenAnEnvironmentIsChosen_thenTheSamePageOpensInThatTree() {
        open("/" + GUIDE_ROUTE + "/");

        switcher().click();
        environmentLink(environmentNamed("dev")).click();

        page.waitForURL(url("/dev/" + GUIDE_ROUTE + "/"));
        assertThat(guideTitle()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * At a narrow viewport the switcher belongs in the sidebar and nowhere else: it rendered twice for a while,
     * the second time as a hover dropdown inside a scrolling list, where an absolutely positioned menu has
     * nowhere to open.
     */
    @Test
    void switcher_whenTheViewportIsNarrow_thenItAppearsOnceAndInTheSidebar() {
        page.setViewportSize(400, 800);
        open("/");

        assertThat(switcher()).isHidden();
        page.getByLabel("Toggle navigation bar").click();

        // In the sidebar, and counted there: the root page has a table row headed "Environment" too, and the
        // question here is how many switchers there are, not how often the word appears.
        Locator sidebar = page.locator("div.navbar-sidebar");
        assertThat(sidebar.getByText("Environment", new Locator.GetByTextOptions().setExact(true))).hasCount(1);
        assertThat(sidebar.getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName(environmentNamed("dev").label()).setExact(false)))
                .isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * Documentation describing DEV is misleading to a reader who believes they are looking at production, so
     * every tree but the main one says what it is - and the main one must not.
     */
    @Test
    void banner_thenItStandsOnEveryEnvironmentButTheMainOne() {
        for (SiteEnvironment environment : environments()) {
            open(environment.main() ? "/" : "/" + environment.id() + "/");

            Locator banner = page.getByRole(AriaRole.NOTE);
            if (environment.main()) {
                assertThat(banner).hasCount(0);
            } else {
                assertThat(banner).containsText(environment.label());
            }
        }
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The search index was empty for four review rounds while every test passed: it is a file, and asserting
     * that the file exists says nothing about whether typing a word finds anything. The index is fetched by the
     * page, so this is also what would notice a {@code connect-src} the service does not allow.
     */
    @Test
    void search_whenAWordIsSearchedFor_thenTheHitLeadsToThePageItIsOn() {
        // Not through the navbar's autocomplete but through the search page, which is the same index and the
        // same query without a dropdown's timing. The service redirects onto the canonical trailing slash on
        // the way, so the query has to survive that redirect for this to find anything at all.
        open("/search?q=" + SEARCHABLE_TERM);

        Locator hit = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(GUIDE_TITLE)).first();
        assertThat(hit).isVisible();

        // Every environment holds the page, so which copy ranks first is not this test's business - that the
        // hit leads to the page is. The href carries the plugin's own highlight query, hence the pattern.
        hit.click();
        page.waitForURL(Pattern.compile(".*/" + GUIDE_ROUTE + "/.*"));
        // The page's own title, and only it: the URL changes before the router has rendered the route behind
        // it, and every result on the page left behind is a heading of the same name one level down.
        assertThat(guideTitle()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * A fence becomes a diagram in the browser, not on the server: the generated page carries only the
     * attribute saying that it should be one. The plugin renders with WebAssembly, which the service's
     * Content-Security-Policy has to allow - {@code 'wasm-unsafe-eval'} and {@code worker-src blob:} - so this
     * tests that policy as much as it tests the plugin.
     */
    @Test
    void diagrams_whenAPageHoldsAFence_thenItIsRenderedAsAnImage() {
        open("/" + GUIDE_ROUTE + "/");

        assertThat(page.locator("[data-plantuml-diagram] svg").first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The colour mode is stored by the reader's browser and applied by an inline script before the page paints.
     * That script needs {@code 'unsafe-inline'} in the policy; without it the toggle looks like it worked and
     * the choice is gone on the next page. The browser here prefers dark, so a chosen light mode is visibly the
     * reader's decision winning over the system's.
     */
    @Test
    void colorMode_whenChosen_thenItOutlastsTheSystemPreferenceAndTheNextPage() {
        open("/");
        assertThat(page.locator("html")).hasAttribute("data-theme", "dark");

        page.getByLabel("Switch between dark and light mode", new Page.GetByLabelOptions().setExact(false)).click();
        assertThat(page.locator("html")).hasAttribute("data-theme", "light");

        open("/" + GUIDE_ROUTE + "/");
        assertThat(page.locator("html")).hasAttribute("data-theme", "light");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The trigger of the environment switcher, addressed the way a reader's assistive technology addresses it.
     * A test that could not find it by its role would itself be the accessibility finding.
     */
    private Locator switcher() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Switch environment").setExact(false));
    }

    /** The title of the guide page, as the page itself renders it. */
    private Locator guideTitle() {
        return page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(GUIDE_TITLE).setLevel(1));
    }

    private Locator environmentLink(SiteEnvironment environment) {
        return page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(environment.label()).setExact(false));
    }

    private SiteEnvironment environmentNamed(String id) {
        return environments().stream()
                .filter(environment -> id.equals(environment.id()))
                .findFirst()
                .orElseThrow();
    }
}
