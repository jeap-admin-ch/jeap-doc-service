package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Every destination offered by the new viewer must resolve across system boundaries. */
class ReactionGraphCrossingBrowserIT extends SiteBrowserTestBase {

    @Test
    void everyDocumentationLinkOnTheGraphsOfBothSystems_resolves() {
        List<String> pages = List.of(
                SYSTEM_REACTIONS_ROUTE, COMPONENT_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE,
                "systems/" + OTHER_SYSTEM + "/system-architecture/runtime-view/system-reactions",
                "systems/" + OTHER_SYSTEM + "/system-architecture/building-block-view/components/"
                        + OTHER_COMPONENT + "/component-architecture/runtime-view/component-reactions",
                "systems/" + OTHER_SYSTEM + "/system-architecture/building-block-view/events/" + OTHER_MESSAGE);
        Set<String> checked = new LinkedHashSet<>();
        for (String each : pages) {
            open("/" + each + "/");
            showReactionViewer(0);
            var viewers = page.locator("section[aria-label='Reaction chains']");
            for (int index = 0; index < viewers.count(); index++) {
                showReactionViewer(index);
                var viewer = viewers.nth(index);
                var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
                // Pointer and keyboard menu interaction is covered in ReactionViewerBrowserIT. Here we
                // enumerate every rendered target, including those initially outside the viewport.
                var ids = (List<?>) canvas.evaluate("""
                        element => element._cyreg.cy.nodes().filter(node =>
                            ['group', 'message', 'http'].includes(node.data('kind'))).map(node => node.id())
                        """);
                for (Object id : ids) {
                    canvas.evaluate("""
                            (element, id) => { element._cyreg.cy.getElementById(id)
                                .emit({type: 'cxttap', renderedPosition: {x: 100, y: 100}}); }
                            """, id);
                    var menu = viewer.getByRole(AriaRole.MENU);
                    assertThat(menu).isVisible();
                    for (var link : menu.locator("a[href]").all()) {
                        String target = link.getAttribute("href");
                        org.assertj.core.api.Assertions.assertThat(target)
                                .doesNotContain("/components/" + UNDOCUMENTED_COMPONENT + "/");
                        if (checked.add(target)) {
                            org.assertj.core.api.Assertions.assertThat(page.request().get(url(target)).status())
                                    .describedAs("destination %s offered by %s", target, each).isEqualTo(200);
                        }
                    }
                    page.keyboard().press("Escape");
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(checked)
                .anyMatch(target -> target.contains("/systems/" + REACTING_SYSTEM + "/"))
                .anyMatch(target -> target.contains("/systems/" + OTHER_SYSTEM + "/"))
                .anyMatch(target -> target.contains("component-reactions"))
                .anyMatch(target -> target.contains("/events/"));
        assertNothingWentWrongInTheBrowser();
    }
}
