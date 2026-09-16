package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

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
     * A fence becomes a diagram in the browser, not on the server: the generated page carries only the
     * attribute saying that it should be one. The plugin renders with WebAssembly, which the service's
     * Content-Security-Policy has to allow - {@code 'wasm-unsafe-eval'} and {@code worker-src blob:} - so this
     * tests that policy as much as it tests the plugin.
     */
    @Test
    void diagrams_whenAPageHoldsAFence_thenItIsRenderedAsAnImage() {
        open("/" + GUIDE_ROUTE + "/");

        // The svg that carries text, not the first one: the container also holds the toolbar's icons, and an
        // assertion that matched one of those would pass with the diagram itself broken.
        Locator diagram = page.locator("[data-plantuml-diagram] svg:has(text)").first();
        assertThat(diagram).isVisible();
        // The fence carries the constructs the generator emits. PlantUML draws a syntax error as a picture
        // too, so what proves it parsed is the content: the boxes are there and the complaint is not.
        assertThat(diagram).containsText("orders-intake");
        assertThat(diagram).containsText("shipping");
        assertThat(diagram).not().containsText("Syntax Error");
        // The gold of the subject and the colours of the relations. A colour written into the source is never
        // re-themed - the plugin re-renders with the engine's dark flag, which moves PlantUML's own palette
        // and leaves these where they are - and the browser here prefers dark, so this is the mode in which
        // they have to be legible.
        Assertions.assertThat(diagram.locator("[fill='#FFD700']").count())
                .describedAs("the box of the subject is gold")
                .isPositive();
        Assertions.assertThat(diagram.locator("[stroke='#0000FF']").count())
                .describedAs("a command and a REST call are blue")
                .isPositive();
        Assertions.assertThat(diagram.locator("[stroke='#008000']").count())
                .describedAs("an event is green")
                .isPositive();
        Assertions.assertThat(diagram.locator("[stroke-dasharray]").count())
                .describedAs("and a message is dashed")
                .isPositive();
        // Two systems each have a component called gateway. Two boxes sharing a label and differing only in
        // their alias is what the view emits for that, and one box carrying both sets of arrows is what it
        // would be if PlantUML read the label as the identity.
        Assertions.assertThat(diagram.getByText("gateway", new Locator.GetByTextOptions().setExact(true))
                        .count())
                .describedAs("two boxes, not one merged")
                .isEqualTo(2);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A package carries a link too.</b> A neighbouring system on a component context view is a package
     * rather than a box, so that is where the way into its own documentation lives - and a link inside a
     * fence is checked by nothing but a browser.
     */
    @Test
    void diagrams_whenAPackageIsLinked_thenItIsAnAnchorAsWell() {
        open("/" + GUIDE_ROUTE + "/");
        Locator diagram = page.locator("[data-plantuml-diagram] svg:has(text)").first();
        assertThat(diagram).isVisible();

        // Every anchor of the picture. The packages and the boxes of the fixture all link to the guide page
        // except one, so an anchor count below the number of packages would mean a package lost its link.
        Assertions.assertThat(diagram.locator("a[*|href='/guide/']").count())
                .describedAs("three packages and two boxes link to the page itself")
                .isGreaterThanOrEqualTo(3);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A link in a diagram is followed, and it is followed in the tab the reader is in.</b>
     * <p>
     * The links a system context view draws are the way from one system to its neighbours, and they are the
     * one kind of link nothing else can check: they sit inside a fence, so the generator does not rewrite
     * them and Docusaurus never sees them as routes. What renders them is PlantUML in the reader's browser,
     * which is where this looks.
     */
    @Test
    void diagrams_whenABoxIsLinked_thenItIsAnAnchorThatIsFollowedInTheSameTab() {
        open("/" + GUIDE_ROUTE + "/");
        Locator diagram = page.locator("[data-plantuml-diagram] svg:has(text)").first();
        assertThat(diagram).isVisible();

        // The anchor the linked box became. PlantUML wraps the box in an <a>, so a box that lost its link is
        // a box with no anchor rather than a broken one.
        Locator linked = diagram.locator("a[*|href='/']").first();
        assertThat(linked).isVisible();
        assertThat(linked).not().hasAttribute("target", "_blank");

        int tabsBefore = page.context().pages().size();
        linked.click();
        page.waitForURL(url -> url.endsWith("/"));

        Assertions.assertThat(page.context().pages())
                .describedAs("a link within the site must not open a new tab - a reader following a diagram "
                             + "into another system would collect one per hop")
                .hasSize(tabsBefore);
        Assertions.assertThat(page.url()).endsWith("/");
        Assertions.assertThat(page.url()).doesNotContain(GUIDE_ROUTE);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The other kind of diagram the generator writes: an entity relationship diagram. None of its constructs
     * appears in a component diagram, and PlantUML draws a syntax error as a picture too - so what proves it
     * parsed is that the tables are in it and the complaint is not.
     */
    @Test
    void diagrams_whenAPageHoldsAnEntityRelationshipFence_thenItIsRenderedToo() {
        open("/" + GUIDE_ROUTE + "/");

        Locator diagrams = page.locator("[data-plantuml-diagram] svg:has(text)");
        assertThat(diagrams).hasCount(2);

        Locator schema = diagrams.last();
        assertThat(schema).isVisible();
        assertThat(schema).containsText("orders_order");
        assertThat(schema).containsText("orders_party");
        // A column, which only an entity has: a component box carries no rows.
        assertThat(schema).containsText("party_id");
        assertThat(schema).not().containsText("Syntax Error");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A column is drawn where it was declared, whatever its type says.</b> PlantUML tells a field from a
     * method by whether the line has parentheses, and a column type usually has them - {@code numeric(12,2)},
     * {@code text[]} - but that heuristic is a {@code class} member's, not an {@code entity}'s: every member
     * of an entity is a field. If it ever became an entity's too, those two columns would be drawn in a
     * compartment of their own after {@code remark}, below a separator that means nothing and with a method
     * icon beside them. The source parses and reads correctly either way, so only a rendering can tell -
     * which is why the assertion is here and not in a unit test, and why the generator emits no
     * {@code {field}} markers to guard against something that does not happen.
     */
    @Test
    void diagrams_thenEveryColumnIsDrawnAsAColumnInItsDeclaredPlace() {
        open("/" + GUIDE_ROUTE + "/");

        // Waited for before it is read: the plugin renders in the browser, one diagram at a time, and
        // textContent() does not retry - so reading the last one too early reads the first one instead, which
        // is a green test that asserted the wrong diagram or a failure that looks like a generator bug.
        Locator diagrams = page.locator("[data-plantuml-diagram] svg:has(text)");
        assertThat(diagrams).hasCount(2);
        String schema = diagrams.last().textContent();

        Assertions.assertThat(schema).describedAs("the type is on the page as the database spells it")
                .contains("numeric(12,2)")
                .contains("text[]");
        Assertions.assertThat(schema.indexOf("remark"))
                .describedAs("the plain column declared after the parameterised ones is still drawn after "
                             + "them, so neither of those was moved into a compartment of methods")
                .isGreaterThan(schema.indexOf("numeric(12,2)"))
                .isGreaterThan(schema.indexOf("text[]"));
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
        // Scoped to the navbar switcher on purpose: the footer's Environments group links to the same
        // environments by the same names, and an unscoped role-and-name query would match both.
        return page.locator(".navbar").getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName(environment.label()).setExact(false));
    }

    private SiteEnvironment environmentNamed(String id) {
        return environments().stream()
                .filter(environment -> id.equals(environment.id()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The sidebar shows where the reader is: the path to the open page is open, a sibling is closed, and only
     * the open page carries the accent background.
     */
    @Test
    void sidebar_whenADeepPageIsOpen_thenOnlyItsPathIsOpenAndOnlyThePageStandsOut() {
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        Locator sidebar = page.locator("nav.menu");

        Locator current = sidebar.locator("a[aria-current='page']");
        assertThat(current).hasCount(1);
        Assertions.assertThat(current.getAttribute("href")).endsWith("/component-reactions/");
        Assertions.assertThat(backgroundOf(current)).describedAs("the open page").isNotEqualTo(TRANSPARENT);

        Locator ancestor = sidebar.locator(
                "a.menu__link--active[href$='/components/" + REACTING_COMPONENT + "/']");
        assertThat(ancestor).isVisible();
        Assertions.assertThat(backgroundOf(ancestor.locator("xpath=..")))
                .describedAs("a category on the path").isEqualTo(TRANSPARENT);

        assertThat(categoryOf(sidebar, "/components/" + BUSY_COMPONENT + "/"))
                .hasClass(COLLAPSED);
        assertThat(categoryOf(sidebar, "/building-block-view/events/"))
                .hasClass(COLLAPSED);
        assertNothingWentWrongInTheBrowser();
    }

    /** A category with a page of its own is the open page when the reader is on that page. */
    @Test
    void sidebar_whenACategoryPageIsOpen_thenThatCategoryStandsOut() {
        open("/systems/" + REACTING_SYSTEM + "/system-architecture/building-block-view/components/"
             + REACTING_COMPONENT + "/");

        Locator current = page.locator("nav.menu a[aria-current='page']");
        assertThat(current).hasCount(1);
        Assertions.assertThat(current.getAttribute("href"))
                .endsWith("/components/" + REACTING_COMPONENT + "/");
        // A category draws its background on the wrapper around the link and its caret.
        String category = backgroundOf(current.locator("xpath=.."));
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        Assertions.assertThat(category).describedAs("the same accent as an open page that is no category")
                .isNotEqualTo(TRANSPARENT)
                .isEqualTo(backgroundOf(page.locator("nav.menu a[aria-current='page']")));
        assertNothingWentWrongInTheBrowser();
    }

    /** Opening a category closes its open siblings, so the tree does not grow back as the reader moves. */
    @Test
    void sidebar_whenACategoryIsOpened_thenItsOpenSiblingCloses() {
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        Locator sidebar = page.locator("nav.menu");
        Locator reacting = categoryOf(sidebar, "/components/" + REACTING_COMPONENT + "/");
        assertThat(reacting).not().hasClass(COLLAPSED);

        categoryOf(sidebar, "/components/" + BUSY_COMPONENT + "/")
                .locator(":scope > .menu__list-item-collapsible > button.menu__caret").click();

        assertThat(reacting).hasClass(COLLAPSED);
        assertNothingWentWrongInTheBrowser();
    }

    private static final String TRANSPARENT = "rgba(0, 0, 0, 0)";
    private static final java.util.regex.Pattern COLLAPSED =
            java.util.regex.Pattern.compile("menu__list-item--collapsed");

    private static String backgroundOf(Locator link) {
        return (String) link.evaluate("element => getComputedStyle(element).backgroundColor");
    }

    /** The list item of the category whose link ends with the given path. */
    private static Locator categoryOf(Locator sidebar, String hrefEnd) {
        return sidebar.locator("li.theme-doc-sidebar-item-category").filter(new Locator.FilterOptions()
                .setHas(sidebar.page().locator(":scope > .menu__list-item-collapsible > a[href$='" + hrefEnd + "']")));
    }

    /** A page generated from the model says when the model was imported, as a reader reads a time. */
    @Test
    void provenance_namesTheImportTimeToTheSecond() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");

        Locator provenance = page.getByLabel("Where this page came from");
        assertThat(provenance).containsText("imported " + DisplayTime.of(GENERATED_AT) + ".");
        Assertions.assertThat(provenance.textContent()).doesNotContainPattern("\\dT\\d");
        assertNothingWentWrongInTheBrowser();
    }

    /** The root page names no import, so it names when it was generated - in the same form. */
    @Test
    void provenance_onTheRootPage_namesTheGenerationTimeToTheSecond() {
        open("/");

        Locator provenance = page.getByLabel("Where this page came from");
        assertThat(provenance).containsText("generated " + DisplayTime.of(GENERATED_AT) + ".");
        Assertions.assertThat(provenance.textContent()).doesNotContainPattern("\\dT\\d");
        assertNothingWentWrongInTheBrowser();
    }
}
