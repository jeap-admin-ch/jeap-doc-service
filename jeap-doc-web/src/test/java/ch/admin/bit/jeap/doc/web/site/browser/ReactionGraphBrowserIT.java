package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.template.ReactionIds;
import com.microsoft.playwright.Locator;

import java.util.List;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The runtime views in a browser: the three pages that draw a reaction graph, and the four things the story
 * asks a reader to be able to do with one.
 * <p>
 * <b>Every page here is written by the structure template itself</b>, from a model and a reaction graph - see
 * {@link SiteBrowserTestBase}. A fence a test wrote by hand would prove that the plugin draws DOT, which
 * nobody doubts, and nothing about the DOT the generator emits: the node ids a deep link needs, the links that
 * have to carry the environment prefix because nothing rewrites a fence, and a label that survives being
 * quoted.
 * <p>
 * <b>And the four features are the plugin's</b>, not this service's. They are asserted all the same, because
 * what this service chose is the plugin and its options - a plugin registered without {@code zoom}, or with a
 * source limit below a real graph, is a page where none of them is there.
 */
class ReactionGraphBrowserIT extends SiteBrowserTestBase {

    /**
     * Where a diagram's own toolbar and the rendered picture live. Not {@code data-plantuml-zoom}, which is
     * the current scale rather than the container.
     */
    private static final String DIAGRAM = "[data-plantuml-diagram]";

    @Test
    void systemReactions_thenTheGraphIsDrawnAndTheTableSaysTheSameThing() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        assertThat(diagram()).isVisible();
        // What proves the DOT parsed is the content: Graphviz draws nothing at all for a source it refuses.
        assertThat(diagram()).containsText("OrdersPaymentAccepted");
        assertThat(diagram()).containsText("shipping-dispatch");
        // The table below it is the complete list, and the one the browser's own find-in-page searches.
        assertThat(page.getByRole(AriaRole.TABLE))
                .containsText("OrdersPaymentAcceptedEvent");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void componentReactions_thenTheComponentsOwnGraphIsDrawn() {
        open(route(COMPONENT_REACTIONS_ROUTE));

        assertThat(diagram()).isVisible();
        assertThat(diagram()).containsText("OrdersPaymentAccepted");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The one runtime view that is not in chapter 6: a message's graph is a section of the message's own page,
     * so that a reader who has the message in front of them sees what answers it without leaving the page.
     */
    @Test
    void messageReactions_thenTheGraphIsASectionOfTheMessagePage() {
        open(route(MESSAGE_REACTIONS_ROUTE));

        Locator heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Reactions"));
        assertThat(heading).isVisible();
        // Scrolled to first: a message's page carries its reactions below everything the model says about it,
        // and the plugin renders a diagram when it comes into view rather than on load.
        heading.scrollIntoViewIfNeeded();

        assertThat(diagram()).isVisible();
        assertThat(diagram()).containsText("shipping-dispatch");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Jump to node via a link.</b> The hash names a node by the id the generator wrote, which is the
     * observer's own id for that reaction - so the link a system's graph carries into a component's page lands
     * on the same reaction rather than on whatever happens to contain the same text.
     */
    @Test
    void deepLink_thenTheNamedNodeIsFocused() {
        open(route(COMPONENT_REACTIONS_ROUTE) + "#graph?highlight-node=REACTION-" + REACTION_ID);

        Locator focused = page.locator("[data-plantuml-focused-node='true']");
        assertThat(focused).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Following a hit is a page load.</b> A reaction on the system's graph links into the component's own
     * runtime view: the two pages belong to different builds of a site cut into parts, so the link is a URL
     * the server resolves and not a route this build knows.
     */
    @Test
    void aReactionOnTheSystemsGraph_linksIntoTheComponentsOwnRuntimeView() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        // The anchor Graphviz wrapped the node in, which is what the URL attribute becomes.
        Locator link = diagram().locator("a[*|href*='component-reactions']").first();
        assertThat(link).hasCount(1);
        link.click();
        page.waitForURL("**/component-reactions/**");
        // A click inside a diagram is a page load, not a route: the site is cut into parts and this link
        // leaves the one that drew the graph. So the next page has to hydrate before anything is asserted.
        page.waitForFunction("() => document.documentElement.dataset.hasHydrated === 'true'");

        // And it lands on the reaction it came from. The URL is what is asserted rather than the highlight:
        // the plugin marks the focused node while it draws attention to it and takes the mark off again, so
        // the highlight is a moment and the link is the promise.
        org.assertj.core.api.Assertions.assertThat(page.url())
                .endsWith("#graph?highlight-node=REACTION-" + REACTION_ID);
        // And what it lands on is a drawn graph. The container is asserted through rather than the picture:
        // the plugin draws a diagram when it comes into view, and the reader arrives above this one.
        Locator arrived = page.locator(DIAGRAM).first();
        assertThat(arrived).isVisible();
        arrived.scrollIntoViewIfNeeded();
        assertThat(diagram()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A message on a graph lands on its own diagram, not on the top of a page of diagrams.</b> A message
     * page draws one diagram per variant - eighty-two of them for the busiest type on a real landscape - so
     * the fragment is what makes the link useful, and the id it names has to be the one that variant's diagram
     * carries. This fixture's message page draws two, and the link comes from the express variant's node: a
     * prefix computed any other way lands on the first diagram or on no node at all.
     */
    @Test
    void aMessageOnTheSystemsGraph_linksIntoTheDiagramOfItsOwnVariant() {
        String node = ReactionIds.messageId(ReactionIds.prefixOf(VARIANT), VARIANT_MESSAGE_ID);
        open(route(SYSTEM_REACTIONS_ROUTE));

        Locator link = diagram().locator("a[*|href*='highlight-node=" + node + "']").first();
        assertThat(link).hasCount(1);
        link.click();
        page.waitForURL("**/" + REACTING_MESSAGE + "/**");
        page.waitForFunction("() => document.documentElement.dataset.hasHydrated === 'true'");

        org.assertj.core.api.Assertions.assertThat(page.url())
                .endsWith("#graph?highlight-node=" + node);
        // And the id it addresses is really a node of that page: a URL inside a fence is checked by nothing,
        // so a fragment naming an id nobody wrote is a link that arrives and does nothing. The diagram is
        // scrolled to first - the plugin draws one when it comes into view.
        Locator arrived = page.locator(DIAGRAM).last();
        arrived.scrollIntoViewIfNeeded();
        assertThat(page.locator("[id='" + node + "']")).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** <b>Search.</b> The lens opens a bar that finds the text of the rendered diagram and counts the hits. */
    @Test
    void search_thenAMatchInTheDiagramIsFound() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        page.getByLabel("Search diagram").first().click();
        Locator input = page.getByLabel("Search diagram text").first();
        assertThat(input).isVisible();
        input.fill("dispatch");

        assertThat(page.getByLabel("Next match").first()).isEnabled();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Fit to screen.</b> The control that puts a graph back where a reader can see all of it, which the
     * plugin offers <b>while the diagram is maximized</b> - the view a reader opens a large graph in, and the
     * one a zoom is worth coming back from.
     */
    @Test
    void fitToScreen_whenTheDiagramIsMaximized_thenItCanBeFittedAgain() {
        open(route(SYSTEM_REACTIONS_ROUTE));
        page.getByLabel("Maximize diagram").first().click();

        page.getByLabel("Zoom in").first().click();
        Locator fit = page.getByLabel("Fit diagram to screen").first();
        assertThat(fit).isVisible();
        fit.click();

        assertThat(diagram()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** <b>The minimap.</b> A toggle, and the panel it opens - the way a large graph stays navigable. */
    @Test
    void minimap_whenOpened_thenThePanelIsShown() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        page.getByLabel("Show minimap").first().click();

        assertThat(page.locator("[data-plantuml-minimap]").first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /** <b>Maximize.</b> The fourth control, which fills the viewport with the graph. */
    @Test
    void maximize_thenTheDiagramFillsTheViewport() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        page.getByLabel("Maximize diagram").first().click();

        assertThat(diagram().first()).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Every link a graph offers resolves.</b> Docusaurus checks the links of a page and never looks inside
     * a fence, so a URL written into DOT is checked by nothing at all - and a link to a page this run did not
     * write builds cleanly and answers 404 to the reader who follows it. A browser found exactly that: a
     * component the landscape documents, whose runtime view was not written because nothing was observed
     * reacting in it, was still linked from the system's graph.
     */
    @Test
    void everyLinkInAGraph_resolvesToAPageThatExists() {
        open(route(SYSTEM_REACTIONS_ROUTE));
        diagram().waitFor();

        List<String> targets = (List<String>) (List<?>) page.locator(DIAGRAM + " a").evaluateAll(
                "anchors => anchors.map(a => a.getAttribute('href') ?? a.getAttribute('xlink:href'))");
        org.assertj.core.api.Assertions.assertThat(targets)
                .describedAs("the graph offers links at all").isNotEmpty();
        for (String target : targets) {
            int status = page.request().get(url(target.replaceAll("#.*$", ""))).status();
            org.assertj.core.api.Assertions.assertThat(status)
                    .describedAs("what a reader gets from %s", target).isEqualTo(200);
        }
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A message of another system is outlined, not filled.</b> The plugin gives a linked node's text the
     * theme's link colour and retargets every other label at the page's text colour - both light in dark mode
     * - so a light fill is a label nobody can read there, and a CSS rule beats any font colour the generator
     * could write. The label is read against the page instead.
     */
    @Test
    void aMessageOfAnotherSystem_isMarkedWithoutMakingItsLabelUnreadable() {
        open(route(SYSTEM_REACTIONS_ROUTE));
        diagram().waitFor();

        Locator marked = page.locator(DIAGRAM + " g.node:has([stroke='#4a90d9'])").first();
        assertThat(marked).isVisible();
        Object painted = marked.locator("ellipse").first().evaluate("node => getComputedStyle(node).fill");
        org.assertj.core.api.Assertions.assertThat(painted.toString())
                .describedAs("nothing is painted under the label")
                .matches("none|rgba\\(0, 0, 0, 0\\)");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The table under a graph is the complete list: every reaction the diagram draws has a row, which is what
     * a reader searches with their browser and what is left if a graph is ever too large to draw.
     */
    @Test
    void theTableUnderTheGraph_listsEveryReactionOnIt() {
        open(route(SYSTEM_REACTIONS_ROUTE));

        assertThat(page.locator("article table tbody tr")).hasCount(6 + WIDE_REACTIONS);
        org.assertj.core.api.Assertions.assertThat(page.locator("article table").first().innerText())
                .contains("no trigger observed")
                .contains("nothing observed");
        assertNothingWentWrongInTheBrowser();
    }

    /** The rendered picture, not the toolbar's own icons: the svg that carries text is the diagram. */
    private Locator diagram() {
        return page.locator(DIAGRAM + " svg:has(text)").first();
    }

    /**
     * A page of the main environment's tree, as the site serves it - <b>at the site root, with no prefix</b>.
     * Every other environment carries its id in front of the same route, which is what the switcher rewrites.
     */
    private String route(String route) {
        return "/" + route + "/";
    }
}
