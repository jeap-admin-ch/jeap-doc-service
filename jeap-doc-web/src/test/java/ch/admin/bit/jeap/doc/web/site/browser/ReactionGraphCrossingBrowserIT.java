package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Locator;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// Playwright's assertThat is the one this suite reaches for; AssertJ's is spelled out on Assertions, because
// the two cannot share the name.
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Crossing between two systems through the reaction graphs, the way a reader does.
 * <p>
 * <b>This is the case a single system cannot cover.</b> One system answers a message of the other and
 * publishes one it draws, so every kind of link a graph offers leaves the page it is on: a message node into
 * another system's chapter 5, a reaction node into a component's own chapter 6, and back again. On a site cut
 * into one build per system those are not routes this build knows - they are URLs the server resolves - and a
 * link that is one segment wrong builds cleanly, looks right in the markup and answers 404 to the reader.
 */
class ReactionGraphCrossingBrowserIT extends SiteBrowserTestBase {

    /** Where a diagram's toolbar and its picture live. */
    private static final String DIAGRAM = "[data-plantuml-diagram]";

    /**
     * The walk: one system's graph, out to the other system's message, on to what answered it, into that
     * component's own view, and back to the first system's message. Each hop is a page the server had to
     * serve.
     */
    @Test
    void aReaderCanWalkFromOneSystemsGraphIntoTheOthersAndBack() {
        open(route(SYSTEM_REACTIONS_ROUTE));
        diagram().waitFor();

        // Out: a message this system answers is defined by the other one, and its node links there.
        follow(nodeLinking("/systems/" + OTHER_SYSTEM + "/"));
        assertThat(page).hasURL(Pattern.compile(".*/systems/" + OTHER_SYSTEM + "/.*"));

        // On that message's page, what was observed answering it - and from there into the component's own
        // chapter 6, focused on the reaction the graph drew.
        open(route("systems/" + OTHER_SYSTEM + "/system-architecture/building-block-view/events/"
                   + OTHER_MESSAGE));
        page.getByRole(com.microsoft.playwright.options.AriaRole.HEADING,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("Reactions"))
                .scrollIntoViewIfNeeded();
        diagram().waitFor();
        follow(nodeLinking("component-reactions"));
        assertThat(page).hasURL(Pattern.compile(
                ".*/components/" + OTHER_COMPONENT + "/.*/component-reactions/.*"));

        // And back: this component answers a message of the first system, whose node links into its tree.
        diagram().waitFor();
        follow(nodeLinking("/systems/" + REACTING_SYSTEM + "/"));
        assertThat(page).hasURL(Pattern.compile(".*/systems/" + REACTING_SYSTEM + "/.*"));
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Every link on every reaction graph of both systems answers.</b> Docusaurus checks the links of a page
     * and never looks inside a fence, so a URL written into DOT is checked by nothing at all - which is how a
     * link to a page this run did not write builds cleanly and 404s for the reader who follows it.
     */
    @Test
    void everyLinkOnEveryGraphOfBothSystems_resolves() {
        List<String> pages = List.of(
                SYSTEM_REACTIONS_ROUTE,
                COMPONENT_REACTIONS_ROUTE,
                MESSAGE_REACTIONS_ROUTE,
                "systems/" + OTHER_SYSTEM + "/system-architecture/runtime-view/system-reactions",
                "systems/" + OTHER_SYSTEM + "/system-architecture/building-block-view/components/"
                + OTHER_COMPONENT + "/component-architecture/runtime-view/component-reactions",
                "systems/" + OTHER_SYSTEM + "/system-architecture/building-block-view/events/" + OTHER_MESSAGE);

        Set<String> checked = new LinkedHashSet<>();
        for (String each : pages) {
            open(route(each));
            // A message's page carries its graph below everything the model says, and a diagram is drawn when
            // it comes into view.
            page.mouse().wheel(0, 4000);
            diagram().waitFor();
            for (String target : linksOf(page.locator(DIAGRAM))) {
                if (!checked.add(target)) {
                    continue;
                }
                int status = page.request().get(url(target.replaceAll("#.*$", ""))).status();
                Assertions.assertThat(status)
                        .describedAs("what a reader gets from %s, offered by %s", target, each)
                        .isEqualTo(200);
            }
        }
        Assertions.assertThat(checked)
                .describedAs("the graphs of two systems offer links in both directions")
                .anyMatch(target -> target.contains("/systems/" + REACTING_SYSTEM + "/"))
                .anyMatch(target -> target.contains("/systems/" + OTHER_SYSTEM + "/"))
                .anyMatch(target -> target.contains("component-reactions"))
                .anyMatch(target -> target.contains("/events/"));
        assertNothingWentWrongInTheBrowser();
    }

    /** A page of a system that has no graph at all is not linked from one that has. */
    @Test
    void aComponentWithoutARuntimeView_isNotLinkedFromTheGraphThatDrewIt() {
        open(route(SYSTEM_REACTIONS_ROUTE));
        diagram().waitFor();

        Assertions.assertThat(linksOf(page.locator(DIAGRAM)))
                .describedAs("the busy component has no runtime view of its own, so nothing links to one")
                .noneMatch(target -> target.contains("/components/" + BUSY_COMPONENT + "/"));
    }

    /** Following a link inside a graph is a page load, and it lands where the link said. */
    private void follow(Locator node) {
        String target = hrefOf(node);
        Assertions.assertThat(target)
                .describedAs("the node that was to be followed is a link").isNotNull();
        node.scrollIntoViewIfNeeded();
        assertThatCode(() -> {
            node.click();
            page.waitForURL("**" + target.replaceAll("#.*$", "") + "**");
            page.waitForFunction("() => document.documentElement.dataset.hasHydrated === 'true'");
        }).describedAs("following %s", target).doesNotThrowAnyException();
    }

    /** The link of this graph that points at the given part of the site. */
    private Locator nodeLinking(String targetPart) {
        return page.locator(DIAGRAM + " a").all().stream()
                .filter(anchor -> hrefOf(anchor) != null && hrefOf(anchor).contains(targetPart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node of this graph links to " + targetPart));
    }

    private List<String> linksOf(Locator within) {
        List<String> targets = new ArrayList<>();
        for (Locator anchor : within.locator("a").all()) {
            String href = hrefOf(anchor);
            if (href != null && !href.isBlank()) {
                targets.add(href);
            }
        }
        return targets;
    }

    private static String hrefOf(Locator anchor) {
        Object href = anchor.evaluate("a => a.getAttribute('href') ?? a.getAttribute('xlink:href')");
        return href == null ? null : href.toString();
    }

    private Locator diagram() {
        return page.locator(DIAGRAM + " svg:has(text)").first();
    }

    private String route(String route) {
        return "/" + route + "/";
    }
}
