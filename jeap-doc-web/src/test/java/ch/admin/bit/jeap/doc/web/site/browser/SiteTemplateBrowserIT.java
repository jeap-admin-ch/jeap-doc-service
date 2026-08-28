package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.assertj.core.api.Assertions;
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

        // Which environment's copy this is belongs to the scoping tests below; that the hit leads to the page
        // is what this one is about. The href carries the plugin's own highlight query, hence the pattern.
        hit.click();
        page.waitForURL(Pattern.compile(".*/" + GUIDE_ROUTE + "/.*"));
        // The page's own title, and only it: the URL changes before the router has rendered the route behind
        // it, and every result on the page left behind is a heading of the same name one level down.
        assertThat(guideTitle()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The site is one build over four environment trees. Before the index was split, every query answered with
     * the same page once per environment and the reader had to read URLs to find the tree they were already in.
     */
    @Test
    void search_whenScopedToAnEnvironment_thenOnlyThatEnvironmentsCopyIsFound() {
        SiteEnvironment dev = environmentNamed("dev");

        open("/search?q=" + SEARCHABLE_TERM + "&ctx=" + dev.id());

        // More than one entry can lead to the same page - the index holds its title and its heading - so what
        // matters is that this environment is on the page at all and that no other one is.
        assertThat(hitsIn(dev).first()).isVisible();
        for (SiteEnvironment other : environments()) {
            if (!other.id().equals(dev.id())) {
                assertThat(hitsIn(other)).hasCount(0);
            }
        }
        // Not only the right link but the right page behind it: the four copies differ in one line, and this
        // is the one that says which tree it came out of.
        hitsIn(dev).first().click();
        page.waitForURL(Pattern.compile(".*/" + dev.id() + "/" + GUIDE_ROUTE + "/.*"));
        assertThat(page.getByText(guideMarkerOf(dev))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The main environment is served at the site root, so it is not one of the configured search paths - it is
     * what is left when none of them matched. That makes it the case the two options left at their defaults
     * would break, and the one worth a test of its own.
     */
    @Test
    void search_whenScopedToNothing_thenTheMainEnvironmentsCopyIsFound() {
        SiteEnvironment main = mainEnvironment();

        open("/search?q=" + SEARCHABLE_TERM);

        assertThat(hitsIn(main).first()).isVisible();
        for (SiteEnvironment other : environments()) {
            if (!other.main()) {
                assertThat(hitsIn(other)).hasCount(0);
            }
        }
        hitsIn(main).first().click();
        page.waitForURL(Pattern.compile(".*/" + GUIDE_ROUTE + "/.*"));
        assertThat(page.getByText(guideMarkerOf(main))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * One index file per environment, each holding only its own pages, and the main environment's is the one
     * with no environment in its name - it is what is left when none of the configured paths matched.
     * <p>
     * This is the mechanism rather than the appearance: the scoping is a partitioned index, not a filter over
     * results, so a reader on DEV is not shown a PROD hit because their browser never fetched one. Asked over
     * HTTP because that is how the browser asks, which also says the service serves the files at all.
     */
    @Test
    void search_thenEachEnvironmentIsServedAnIndexOfItsOwn() {
        open("/");

        for (SiteEnvironment environment : environments()) {
            String index = indexOf(environment);
            for (SiteEnvironment other : environments()) {
                boolean itsOwn = other.id().equals(environment.id());
                String route = other.main() ? "\"/" + GUIDE_ROUTE + "/\"" : "\"/" + other.id() + "/" + GUIDE_ROUTE + "/\"";
                if (itsOwn) {
                    Assertions.assertThat(index)
                            .describedAs("the index of %s should hold its own guide page", environment.id())
                            .contains(route);
                } else {
                    Assertions.assertThat(index)
                            .describedAs("the index of %s should not hold the guide page of %s",
                                    environment.id(), other.id())
                            .doesNotContain(route);
                }
            }
        }
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The navbar needs nothing from the reader: it takes the scope from the page it is on. What it derived is
     * in the link it offers to the full results, which is the only anchor its dropdown puts on the page.
     */
    @Test
    void search_whenReadingAnEnvironment_thenTheNavbarSearchesThatEnvironment() {
        SiteEnvironment dev = environmentNamed("dev");

        open("/" + dev.id() + "/" + GUIDE_ROUTE + "/");
        typeIntoTheSearchBox();

        assertThat(scopedResultsLink(dev).first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The two halves together: the switcher changes the route, and the scope of the search follows the route
     * without anything else being told. Switching environment and then searching is what a reader does, and
     * neither feature is worth much if it stops at the other one's edge.
     */
    @Test
    void search_whenTheEnvironmentIsSwitchedFirst_thenTheSearchFollowsIt() {
        SiteEnvironment dev = environmentNamed("dev");
        open("/" + GUIDE_ROUTE + "/");

        switcher().click();
        environmentLink(dev).click();
        page.waitForURL(url("/" + dev.id() + "/" + GUIDE_ROUTE + "/"));

        typeIntoTheSearchBox();

        // The scope the box derived from the route, as it puts it into the link to the full results - the
        // suggestions themselves are not links, so this is the one place it is visible in the page.
        assertThat(scopedResultsLink(dev).first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The search page takes its scope from the URL rather than from the path it is on, so it needs a control.
     * The search plugin renders one, and it cannot offer the main environment at all - that is the leftover
     * bucket and has no path to name it by - so the site brings its own and hides the plugin's.
     */
    @Test
    void searchPage_whenAnEnvironmentIsChosen_thenTheResultsMoveToItIncludingTheMainOne() {
        SiteEnvironment dev = environmentNamed("dev");

        open("/search?q=" + SEARCHABLE_TERM + "&ctx=" + dev.id());

        assertThat(environmentSelector()).hasValue(dev.id());
        assertThat(environmentSelector().locator("option")).hasCount(environments().size());
        assertThat(page.locator("#context-selector")).isHidden();

        // The main environment is the empty value, which is what the plugin knows the leftover bucket as.
        environmentSelector().selectOption("");

        assertThat(hitsIn(mainEnvironment()).first()).isVisible();
        assertThat(hitsIn(dev)).hasCount(0);
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

    /**
     * Puts the searchable term into the navbar's search box, which is what opens its results. Key by key: the
     * box loads its index when it is first focused and reacts to what is typed, and a value set in one go has
     * been seen to arrive before it is listening.
     */
    private void typeIntoTheSearchBox() {
        Locator box = page.locator("input.navbar__search-input").first();
        box.click();
        box.pressSequentially(SEARCHABLE_TERM, new Locator.PressSequentiallyOptions().setDelay(60));
    }

    /**
     * The navbar's link to the full results, which carries the scope it derived from the route it is on. The
     * suggestions themselves are not links - selecting one is a router push - so this is where what the box
     * decided becomes visible in the page.
     */
    private Locator scopedResultsLink(SiteEnvironment environment) {
        return page.locator("a[href*='ctx=" + environment.id() + "']");
    }

    /** The site's own environment selector on the search page. */
    private Locator environmentSelector() {
        return page.locator("#search-environment");
    }

    /**
     * The links on a search result page that lead to one environment's copy of the guide page. By the start of
     * the href, not by what it contains: the main environment is served at the site root, so a link to its
     * copy is a prefix of nothing while every other environment's copy carries its id in front.
     */
    private Locator hitsIn(SiteEnvironment environment) {
        String prefix = environment.main() ? "" : "/" + environment.id();
        return page.locator("a[href^='" + prefix + "/" + GUIDE_ROUTE + "/']");
    }

    /** One environment's search index, as the browser fetches it. */
    private String indexOf(SiteEnvironment environment) {
        String name = "search-index" + (environment.main() ? "" : "-" + environment.id()) + ".json";
        APIResponse response = page.request().get(url("/" + name));
        Assertions.assertThat(response.status())
                .describedAs("the service should serve %s", name).isEqualTo(200);
        return response.text();
    }

    private SiteEnvironment mainEnvironment() {
        return environments().stream()
                .filter(SiteEnvironment::main)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No environment is the main one."));
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
