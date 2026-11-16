package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route.FulfillOptions;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.MouseButton;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The React viewer consumes the real published JSON under the service's CSP. */
class ReactionViewerBrowserIT extends SiteBrowserTestBase {
    @Test
    void navigationOmitsWholeListItemsWithoutReactionsIncludingOnTheSearchPage() {
        for (String route : List.of("/abn/", "/dev/", "/search/?env=abn", "/search/?env=dev")) {
            boolean enabled = route.contains("dev");
            page.setViewportSize(1280, 900);
            open(route);
            var footer = page.locator("footer");
            var explorer = new Locator.GetByRoleOptions()
                    .setName("Reaction Graph Explorer").setExact(true);
            assertThat(footer.getByRole(AriaRole.LINK, explorer)).hasCount(enabled ? 1 : 0);
            assertThat(footer.locator("li:empty")).hasCount(0);
            assertThat(page.locator("nav").getByRole(AriaRole.LINK, explorer)).hasCount(enabled ? 1 : 0);
            if (enabled) {
                assertThat(footer.getByRole(AriaRole.LINK, explorer)).hasAttribute("href", "/dev/reactions/");
            }
            page.setViewportSize(400, 800);
            page.getByLabel("Toggle navigation bar").click();
            var sidebar = page.locator("div.navbar-sidebar");
            assertThat(sidebar.getByRole(AriaRole.LINK, explorer)).hasCount(enabled ? 1 : 0);
            assertThat(sidebar.locator("li:empty")).hasCount(0);
        }
        assertNothingWentWrongInTheBrowser();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-shell", "no-snapshot", "missing-route"})
    void explorerNavigationWaitsForPublishedShellAndSnapshot(String unavailable) {
        var ready = new AtomicBoolean(false);
        var checks = new AtomicInteger();
        page.route("**/reaction-explorer.json", route -> {
            checks.incrementAndGet();
            if (!ready.get() && unavailable.equals("missing-shell")) {
                route.fulfill(new FulfillOptions().setStatus(404));
            } else if (!ready.get() && unavailable.equals("no-snapshot")) {
                route.fulfill(new FulfillOptions().setContentType("application/json")
                        .setBody("{\"formatVersion\":1,\"site\":\"default\",\"environment\":\"prod\",\"available\":false}"));
            } else route.resume();
        });
        page.route("**/reactions/", route -> {
            if (!ready.get() && unavailable.equals("missing-route") && route.request().method().equals("HEAD")) {
                route.fulfill(new FulfillOptions().setStatus(404));
            } else route.resume();
        });
        page.route("**/reaction-graph.json", route -> {
            if (!ready.get() && unavailable.equals("no-snapshot")) {
                route.fulfill(new FulfillOptions().setContentType("application/json")
                        .setBody("{\"formatVersion\":1,\"site\":\"default\",\"environment\":\"prod\",\"available\":false}"));
            } else route.resume();
        });
        // This system page already contains the new navigation, but its shell can still be old or failed.
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        page.waitForFunction("() => performance.getEntriesByType('resource').some(entry => entry.name.endsWith('/reaction-explorer.json'))");
        if (unavailable.equals("missing-route")) {
            page.waitForFunction("() => performance.getEntriesByType('resource').some(entry => entry.name.endsWith('/reactions/'))");
        }
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true))).hasCount(0);
        assertThat(page.locator("footer li:empty")).hasCount(0);
        if (unavailable.equals("no-snapshot")) {
            open("/reactions/");
            assertThat(page.getByText("No reaction data has been published for this environment yet.",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
        }
        ready.set(true);
        page.reload();
        assertThat(page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true))).hasCount(0);
        Assertions.assertThat(checks.get()).isEqualTo(1);
        // A newly published shell becomes discoverable after the negative cache expires.
        page.addInitScript("const originalNow = Date.now; Date.now = () => originalNow() + 61_000;");
        page.reload();
        var link = page.locator("nav").getByRole(AriaRole.LINK,
                new Locator.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true));
        assertThat(link).isVisible();
        link.click();
        assertThat(page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"))).isVisible();
        Assertions.assertThat(checks.get()).isGreaterThanOrEqualTo(2);
        // The 404 is the deliberately unavailable shell/route, never an application exception.
        Assertions.assertThat(pageErrors).isEmpty();
        Assertions.assertThat(consoleErrors).allMatch(error -> error.contains("404"));
    }

    @Test
    void explorerAvailabilitySurvivesNavigationAndTabFocusWithoutRepeatedRequests() {
        var markerRequests = new AtomicInteger();
        var routeRequests = new AtomicInteger();
        page.onRequest(request -> {
            if (request.url().endsWith("/reaction-explorer.json")) markerRequests.incrementAndGet();
            if (request.url().endsWith("/reactions/") && request.method().equals("HEAD")) {
                routeRequests.incrementAndGet();
            }
        });
        page.addInitScript("""
                const originalNow = Date.now;
                window.testTimeOffset = 0;
                Date.now = () => originalNow() + window.testTimeOffset;
                """);
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        var explorer = new Locator.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true);
        assertThat(page.locator("nav").getByRole(AriaRole.LINK, explorer)).isVisible();
        assertThat(page.locator("footer").getByRole(AriaRole.LINK, explorer)).isVisible();
        page.evaluate("""
                () => {
                    window.testTimeOffset = 6_000;
                    window.dispatchEvent(new Event('focus'));
                    document.dispatchEvent(new Event('visibilitychange'));
                }
                """);
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        Assertions.assertThat(markerRequests.get()).isEqualTo(1);
        Assertions.assertThat(routeRequests.get()).isEqualTo(1);

        // Independent part builds load fresh JavaScript; their navigation still shares the tab's cache.
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        assertThat(page.locator("nav").getByRole(AriaRole.LINK, explorer)).isVisible();
        page.reload();
        assertThat(page.locator("footer").getByRole(AriaRole.LINK, explorer)).isVisible();
        Assertions.assertThat(markerRequests.get()).isEqualTo(1);
        Assertions.assertThat(routeRequests.get()).isEqualTo(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void unknownInterfacesCanBeFoundInGlobalAndLocalSearch() {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var json = JsonMapper.builder().build();
            var payload = (ObjectNode) json.readTree(response.text());
            var node = ((ArrayNode) payload.path("graph").path("nodes")).addObject();
            node.putObject("identity").put("nodeType", "INTERFACE").put("key", "opaque-v2");
            node.put("nodeType", "FUTURE_INTERFACE");
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        open("/reactions/");
        var global = page.getByRole(AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Find an entry"));
        global.fill("opaque-v2");
        assertThat(page.getByRole(AriaRole.OPTION)).containsText("FUTURE_INTERFACE");
        global.press("Enter");
        showReactionViewer(0);
        awaitGraphKeys(List.of("opaque-v2"), true);
        var local = page.getByRole(AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Search this graph"));
        local.fill("future_interface");
        assertThat(page.getByRole(AriaRole.OPTION)).containsText("opaque-v2");
        local.press("Enter");
        awaitSearchHighlight("opaque-v2");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void localSearchLocatesExistingNodesWithoutChangingExpandedContentsPositionsPinsOrHistory() {
        installVisibilityFixture(false);
        var requests = new AtomicInteger();
        page.onRequest(request -> { if (request.url().endsWith("/reaction-graph.json")) requests.incrementAndGet(); });
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        var search = viewer.getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Search this graph"));
        var back = viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true));
        var reset = viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reset view"));
        String initial = semanticProjection(viewer);
        Object positions = ((Map<?, ?>) presentationOf(viewer)).get("positions");
        Assertions.assertThat(search.boundingBox().x).isLessThan(back.boundingBox().x);
        search.fill("   ");
        assertThat(viewer.getByRole(AriaRole.LISTBOX)).hasCount(0);
        search.fill(" c ");
        var cResult = viewer.getByRole(AriaRole.OPTION, new Locator.GetByRoleOptions().setName("C — C").setExact(true));
        assertThat(cResult).isVisible();
        viewer.locator("div[aria-label^='Reaction graph.']").evaluate("element => element._cyreg.cy.pan({x: 5000, y: 5000})");
        assertThat(cResult).isVisible();
        assertThat(back).isDisabled();
        search.press("Escape");
        assertThat(viewer.getByRole(AriaRole.LISTBOX)).hasCount(0);
        search.press("ArrowDown");
        search.press("Enter");
        awaitSearchHighlight("C");
        Assertions.assertThat(semanticProjection(viewer)).isEqualTo(initial);
        Assertions.assertThat(((Map<?, ?>) presentationOf(viewer)).get("positions")).isEqualTo(positions);
        assertThat(back).isDisabled();
        openFixtureInterfaceMenu(viewer, "C");
        menuAction(viewer, "Hide");
        awaitGraphKeys(List.of("C"), false);
        String hidden = semanticProjection(viewer);
        search.fill("C");
        assertThat(cResult).hasCount(0);
        search.fill("unknown event");
        assertThat(viewer.getByText("No matches in this graph.", new Locator.GetByTextOptions().setExact(true))).isVisible();
        search.fill(" A ");
        search.press("Enter");
        awaitSearchHighlight("A");
        Assertions.assertThat(semanticProjection(viewer)).isEqualTo(hidden);
        back.click(); // Back undoes Hide, not locating A.
        awaitSemanticProjection(page, 0, initial);
        search.fill("D");
        search.press("Enter");
        awaitSearchHighlight("D");
        search.fill("E");
        search.press("Enter");
        awaitSearchHighlight("E");
        Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']").evaluate("element => element._cyreg.cy.nodes('.newlyExpanded').map(node => node.data('identity')?.key)"))
                .isEqualTo(List.of("E"));
        openFixtureInterfaceMenu(viewer, "D");
        menuAction(viewer, "Hide");
        reset.click();
        awaitSemanticProjection(page, 0, initial);
        Assertions.assertThat(requests.get()).isEqualTo(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void localSearchDistinguishesAndLocatesVariantsComponentsHttpAndGenericInterfaces() {
        installLocalSearchFixture();
        open("/reactions/");
        var global = page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"));
        global.fill("fast");
        global.press("Enter");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        var search = viewer.getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Search this graph"));
        String entry = semanticProjection(viewer);
        search.fill("slow");
        assertThat(viewer.getByText("No matches in this graph.")).isVisible();
        global.fill("slow");
        assertThat(page.getByRole(AriaRole.OPTION)).containsText("slow");
        global.press("Escape");
        openFixtureInterfaceMenu(viewer, "fast");
        menuAction(viewer, "Expand outgoing paths…");
        var dialog = page.getByRole(AriaRole.DIALOG);
        dialog.getByLabel("Edge depth").fill("2");
        dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Expand").setExact(true)).click();
        awaitGraphKeys(List.of("slow", "http", "generic"), true);
        String expanded = semanticProjection(viewer);
        search.fill("OrderEvent");
        assertThat(viewer.getByRole(AriaRole.OPTION)).hasCount(2);
        assertThat(viewer.getByRole(AriaRole.OPTION).nth(0)).containsText("fast");
        assertThat(viewer.getByRole(AriaRole.OPTION).nth(1)).containsText("slow");
        search.press("ArrowDown");
        search.press("Enter");
        awaitSearchHighlight("slow");
        search.fill("dispatch");
        assertThat(viewer.getByRole(AriaRole.OPTION)).hasText(new String[]{"dispatch — Component (billing)", "dispatch — Component (shipping)"});
        search.press("ArrowDown");
        search.press("Enter");
        Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']").evaluate("element => element._cyreg.cy.nodes('.newlyExpanded').map(node => [node.data('kind'), node.data('system')])"))
                .isEqualTo(List.of(List.of("group", "shipping")));
        Assertions.assertThat(semanticProjection(viewer)).isEqualTo(expanded);
        search.fill(" get /orders ");
        assertThat(viewer.getByRole(AriaRole.OPTION)).containsText("orders-api");
        search.press("Enter");
        awaitSearchHighlight("http");
        Assertions.assertThat(semanticProjection(viewer)).isEqualTo(expanded);
        search.fill("FILE");
        assertThat(viewer.getByRole(AriaRole.OPTION)).containsText("orders.csv");
        search.press("Enter");
        awaitSearchHighlight("generic");
        Assertions.assertThat(semanticProjection(viewer)).isEqualTo(expanded);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, entry); // One Back undoes the expansion, despite four searches.
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void localSearchIsIndependentForEachPreparedMessageVariant() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        String first = semanticProjection(viewers.nth(0));
        String second = semanticProjection(viewers.nth(1));
        var firstSearch = viewers.nth(0).getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Search this graph"));
        var secondSearch = viewers.nth(1).getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Search this graph"));
        firstSearch.fill("express");
        assertThat(viewers.nth(0).getByText("No matches in this graph.")).isVisible();
        secondSearch.fill("express");
        assertThat(viewers.nth(1).getByRole(AriaRole.OPTION)).containsText("express");
        firstSearch.fill(REACTING_COMPONENT);
        firstSearch.press("Enter");
        Assertions.assertThat(semanticProjection(viewers.nth(0))).isEqualTo(first);
        Assertions.assertThat(semanticProjection(viewers.nth(1))).isEqualTo(second);
        assertThat(secondSearch).hasValue("express");
        assertNothingWentWrongInTheBrowser();
    }

    private void awaitSearchHighlight(String key) {
        page.waitForFunction("""
                key => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    const node = cy?.nodes().filter(node => node.data('identity')?.key === key).first();
                    if (!node?.length || !node.hasClass('newlyExpanded')) return false;
                    const point = node.renderedPosition();
                    return Math.abs(point.x - cy.width() / 2) < 5 && Math.abs(point.y - cy.height() / 2) < 5;
                }
                """, key);
    }

    private void installLocalSearchFixture() {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var mapper = JsonMapper.builder().build();
            var payload = (ObjectNode) mapper.readTree(response.text());
            payload.putArray("ownership");
            var graph = payload.putObject("graph");
            var nodes = graph.putArray("nodes");
            for (String variant : List.of("fast", "slow")) {
                var node = nodes.addObject();
                node.putObject("identity").put("nodeType", "INTERFACE").put("key", variant);
                node.put("nodeType", "MESSAGE");
                node.putObject("interfaceDetails").putObject("message").put("messageType", "OrderEvent").put("variant", variant);
            }
            var http = nodes.addObject();
            http.putObject("identity").put("nodeType", "INTERFACE").put("key", "http");
            http.put("nodeType", "HTTP_OPERATION");
            http.putObject("interfaceDetails").putObject("http").put("provider", "orders-api").put("method", "GET").put("path", "/orders");
            var generic = nodes.addObject();
            generic.putObject("identity").put("nodeType", "INTERFACE").put("key", "generic");
            generic.put("nodeType", "OTHER");
            generic.putObject("interfaceDetails").put("interfaceType", "FILE").put("fqn", "orders.csv");
            for (String name : List.of("r1", "r2")) {
                var node = nodes.addObject();
                node.putObject("identity").put("nodeType", "REACTION").put("key", name);
                node.put("nodeType", "REACTION");
                node.putObject("reaction").put("system", name.equals("r1") ? "shipping" : "billing")
                        .put("component", "dispatch").put("median", 10).put("status", "ACTIVE").put("groupKey", name);
            }
            var edges = graph.putArray("edges");
            addFixtureEdge(edges, "fast", "r1", "TRIGGER");
            addFixtureEdge(edges, "fast", "r2", "TRIGGER");
            for (String[] flow : new String[][]{{"fast", "r1", "slow"}, {"fast", "r1", "http"}, {"fast", "r2", "generic"}}) {
                addFixtureEdge(edges, flow[1], flow[2], "ACTION");
            }
            route.fulfill(new FulfillOptions().setResponse(response).setBody(mapper.writeValueAsString(payload)));
        });
    }

    @Test
    void explorerSearchStartsWithoutACanvasAndOpensEntriesOutsideTheCurrentView() {
        installVisibilityFixture(false);
        open("/reactions/");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true))).isVisible();
        assertThat(page).hasTitle(Pattern.compile("^Reaction Graph Explorer.*"));
        var search = page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"));
        assertThat(search).isVisible();
        assertThat(page.locator("canvas")).hasCount(0);
        assertThat(page.getByRole(AriaRole.LISTBOX)).hasCount(0);
        assertThat(search).not().hasAttribute("aria-controls", Pattern.compile(".*"));
        search.fill("does-not-exist");
        assertThat(page.getByText("No matches.", new Page.GetByTextOptions().setExact(true))).isVisible();
        assertThat(search).not().hasAttribute("aria-controls", Pattern.compile(".*"));
        search.fill(" A ");
        assertThat(page.getByRole(AriaRole.LISTBOX)).isVisible();
        assertThat(search).hasAttribute("aria-controls", page.getByRole(AriaRole.LISTBOX).getAttribute("id"));
        search.press("Enter");
        assertThat(search).not().hasAttribute("aria-controls", Pattern.compile(".*"));
        showReactionViewer(0);
        awaitGraphKeys(List.of("A", "r1"), true);
        awaitGraphKeys(List.of("C", "D"), false);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        assertThat(viewer.locator("output[aria-label='Zoom']")).isVisible();
        search.fill("D");
        assertThat(page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("D — D").setExact(true))).isVisible();
        search.press("Escape");
        assertThat(page.getByRole(AriaRole.LISTBOX)).hasCount(0);
        assertThat(search).not().hasAttribute("aria-controls", Pattern.compile(".*"));
        awaitGraphKeys(List.of("D"), false);
        search.press("ArrowDown");
        assertThat(page.getByRole(AriaRole.LISTBOX)).isVisible();
        assertThat(search).hasAttribute("aria-controls", page.getByRole(AriaRole.LISTBOX).getAttribute("id"));
        search.press("Enter");
        awaitGraphKeys(List.of("D", "r4"), true);
        awaitGraphKeys(List.of("A"), false);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitGraphKeys(List.of("A", "r1"), true);
        String selected = semanticProjection(viewer);
        String link = sharedLink(viewer);
        page.navigate(link);
        page.reload();
        awaitSemanticProjection(page, 0, selected);
        assertThat(page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void explorerPathSelectionFiltersCandidatesAndSupportsKeyboardRemovalAndSeveralEndpoints() {
        installVisibilityFixture(false);
        open("/reactions/");
        var pathTab = page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Find paths").setExact(true));
        var entryTab = page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Find an entry").setExact(true));
        entryTab.focus();
        entryTab.press("ArrowRight");
        assertThat(pathTab).isFocused();
        assertThat(pathTab).hasAttribute("aria-selected", "true");
        var panel = page.getByRole(AriaRole.TABPANEL, new Page.GetByRoleOptions().setName("Find paths"));
        var search = panel.getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Add interface"));
        var show = panel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Show paths"));
        assertThat(page.getByRole(AriaRole.DIALOG)).hasCount(0);
        assertThat(show).isDisabled();
        search.fill("A");
        search.press("Enter");
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces"))).containsText("A — A");
        entryTab.click();
        page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry")).fill("draft");
        pathTab.click();
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces"))).containsText("A — A");
        search.fill("D"); // a separate chain cannot be used as the next endpoint
        assertThat(search).hasValue("D");
        assertThat(panel.getByText("No matches.", new Locator.GetByTextOptions().setExact(true))).isVisible();
        search.fill("B");
        panel.getByRole(AriaRole.OPTION, new Locator.GetByRoleOptions().setName("B — B").setExact(true)).click();
        search.fill("C");
        search.press("ArrowDown");
        search.press("Enter");
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces")).locator("li")).hasCount(3);
        panel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove B — B")).press("Enter");
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces")).locator("li")).hasCount(2);
        assertThat(show).isEnabled();
        search.fill("pending query");
        entryTab.click();
        assertThat(page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"))).hasValue("draft");
        pathTab.click();
        assertThat(search).hasValue("pending query");
        show.click();
        showReactionViewer(0);
        awaitGraphKeys(List.of("A", "B", "C", "r1", "r2"), true);
        awaitGraphKeys(List.of("D", "E", "r4"), false);
        assertThat(page.getByRole(AriaRole.DIALOG)).hasCount(0);
        String pathLink = sharedLink(page.locator("section[aria-label='Reaction chains']"));
        page.navigate(pathLink);
        page.reload();
        assertThat(pathTab).hasAttribute("aria-selected", "true");
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces")).locator("li")).hasCount(2);
        panel.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Clear selection")).click();
        assertThat(show).isDisabled();
        awaitGraphKeys(List.of("A", "B", "C", "r1", "r2"), true);
        openFixtureInterfaceMenu(page.locator("section[aria-label='Reaction chains']"), "A");
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Find paths from here")).click();
        assertThat(search).isFocused();
        assertThat(panel.getByRole(AriaRole.LIST, new Locator.GetByRoleOptions().setName("Selected interfaces")).locator("li")).hasCount(1);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        assertThat(page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Find paths").setExact(true))).hasCount(0);
        openFixtureInterfaceMenu(page.locator("section[aria-label='Reaction chains']"), "A");
        assertThat(page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Find paths from here"))).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explorerPathTabOpensEvenWhenLeavingFullscreenFails(boolean throwsSynchronously) {
        installVisibilityFixture(false);
        open("/reactions/");
        var entrySearch = page.getByRole(AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Find an entry"));
        entrySearch.fill("A");
        entrySearch.press("Enter");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        viewer.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Fullscreen").setExact(true)).click();
        assertThat(viewer.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Exit fullscreen"))).isVisible();
        page.evaluate("""
                synchronous => {
                    window.originalExitFullscreen = document.exitFullscreen.bind(document);
                    document.exitFullscreen = () => {
                        if (synchronous) throw new Error('Fullscreen exit failed');
                        return Promise.reject(new Error('Fullscreen exit failed'));
                    };
                }
                """, throwsSynchronously);
        openFixtureInterfaceMenu(viewer, "A");
        page.getByRole(AriaRole.MENUITEM,
                new Page.GetByRoleOptions().setName("Find paths from here")).click();
        assertThat(page.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Find paths").setExact(true)))
                .hasAttribute("aria-selected", "true");
        page.evaluate("""
                () => {
                    document.exitFullscreen = window.originalExitFullscreen;
                    return document.exitFullscreen();
                }
                """);
        var panel = page.getByRole(AriaRole.TABPANEL,
                new Page.GetByRoleOptions().setName("Find paths"));
        assertThat(panel).isVisible();
        assertThat(panel.getByRole(AriaRole.LIST,
                new Locator.GetByRoleOptions().setName("Selected interfaces")))
                .containsText("A — A");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void explorerSearchIncludesHttpAndGenericInterfacesAndIsBoundToItsEnvironment() {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var json = JsonMapper.builder().build();
            var payload = (ObjectNode) json.readTree(response.text());
            String environment = payload.path("environment").asString();
            var node = ((ArrayNode) payload.path("graph").path("nodes")).addObject();
            node.putObject("identity").put("nodeType", "INTERFACE").put("key", "generic-" + environment);
            node.put("nodeType", "OTHER");
            node.putObject("interfaceDetails").put("interfaceType", "FILE").put("fqn", environment + ".<script>window.explorerInjected=true</script>");
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        String prefix = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        String environment = prefix.substring(1);
        open(prefix + "/reactions/");
        var search = page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"));
        search.fill("GET /shipments");
        assertThat(page.getByRole(AriaRole.OPTION)).containsText("shipping-dispatch");
        search.press("Enter");
        showReactionViewer(0);
        awaitGraphKeys(List.of("rest-test"), true);
        search.fill("FILE");
        assertThat(page.getByRole(AriaRole.OPTION)).containsText(environment + ".<script>");
        search.press("Enter");
        awaitGraphKeys(List.of("generic-" + environment), true);
        String link = sharedLink(page.locator("section[aria-label='Reaction chains']"));
        Assertions.assertThat(URI.create(link).getPath()).isEqualTo(prefix + "/reactions/");
        Assertions.assertThat(page.evaluate("window.explorerInjected === true")).isEqualTo(false);
        open("/reactions/");
        search.fill("generic-" + environment);
        assertThat(page.getByText("No matches.", new Page.GetByTextOptions().setExact(true))).isVisible();
        search.fill("FILE");
        assertThat(page.getByRole(AriaRole.OPTION)).not().containsText(environment + ".<script>");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void explorerCannotReadAGraphFromAnotherSite() {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var json = JsonMapper.builder().build();
            var payload = (ObjectNode) json.readTree(response.text());
            payload.put("site", "another-site");
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        open("/reactions/");
        assertThat(page.getByText("Reaction graph currently unavailable")).isVisible();
        assertThat(page.locator("canvas")).hasCount(0);
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Reaction Graph Explorer").setExact(true))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void returningToAPageFetchesTheNewSnapshotWithoutReloadingTheDocument() {
        var requests = new AtomicInteger();
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            int visit = requests.incrementAndGet();
            var json = JsonMapper.builder().build();
            var payload = json.readTree(response.text());
            if (visit > 1) {
                for (var node : payload.path("graph").path("nodes")) {
                    if (node.path("reaction").isObject()) {
                        ((ObjectNode) node.path("reaction")).put("median", 9876);
                    }
                }
            }
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        Assertions.assertThat(requests.get()).isEqualTo(1);
        page.evaluate("window.reactionVisitMarker = 'same-document'");
        page.locator("a.navbar__brand").click();
        page.waitForURL(url -> !url.contains(MESSAGE_REACTIONS_ROUTE));
        // Docusaurus changes the URL before it replaces the page while loading the next route.
        assertThat(page.locator(".jeapReactionView")).hasCount(0);
        page.goBack();
        showReactionViewer(0);
        showReactionViewer(1);
        assertThat(page.locator("table[data-reaction-table='live']").first()).containsText("9876");
        Assertions.assertThat(requests.get()).isEqualTo(2);
        Assertions.assertThat(page.evaluate("window.reactionVisitMarker")).isEqualTo("same-document");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void offscreenVariants_waitForTheViewportAndShareOneSnapshotRequest() {
        page.setViewportSize(1280, 500);
        var graphRequests = new AtomicInteger();
        page.onRequest(request -> {
            if (request.url().endsWith("/reaction-graph.json")) graphRequests.incrementAndGet();
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var entries = page.locator(".jeapReactionView");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        assertThat(viewers).hasCount(2);

        showReactionViewer(0);

        Assertions.assertThat(viewers.nth(1).evaluate(
                "element => element.getBoundingClientRect().top > innerHeight + 300")).isEqualTo(true);
        assertThat(viewers.nth(1).locator("canvas")).hasCount(0);
        assertThat(entries.nth(1).locator("table[data-reaction-table='live']")).hasCount(0);
        assertThat(entries.nth(1).locator(".jeapReactionTableFallback table")).isVisible();

        showReactionViewer(1);

        assertThat(entries.nth(1).locator("table[data-reaction-table='live']")).isVisible();
        assertThat(entries.nth(1).locator(".jeapReactionTableFallback")).isHidden();
        assertThat(viewers.first().locator("canvas").first()).isVisible();
        Assertions.assertThat(graphRequests.get()).isEqualTo(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void allEntryPages_renderThePublishedSnapshot() {
        // The busy component previously had no separate component snapshot. Its page now comes from the full graph.
        for (String route : List.of(SYSTEM_REACTIONS_ROUTE, COMPONENT_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE,
                COMPONENT_REACTIONS_ROUTE.replace(REACTING_COMPONENT, BUSY_COMPONENT))) {
            open("/" + route + "/");
            var viewer = page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName("Reaction chains")).first();
            assertThat(viewer).isVisible();
            showReactionViewer(0);
            assertGraphFitted(viewer);
            viewer.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Fit all")).click();
            viewer.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Reset view")).click();
            assertGraphFitted(viewer);
            assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))).isVisible();
            assertThat(viewer.locator("details, ul")).hasCount(0);
            assertThat(page.locator("article table").first()).isVisible();
            assertThat(page.locator("article [data-plantuml-diagram]")).hasCount(0);
            assertNothingWentWrongInTheBrowser();
        }
    }

    @Test
    void messageVariants_haveIndependentViewersAndLabelsRemainText() {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            route.fulfill(new FulfillOptions().setResponse(response).setBody(
                    response.text().replace("shipping-dispatch", "<script>alert(1)</script>")));
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        assertThat(viewers).hasCount(2);
        for (int index = 0; index < 2; index++) {
            showReactionViewer(index);
            viewers.nth(index).getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Fit all")).click();
            var canvas = viewers.nth(index).locator("div[aria-label^='Reaction graph.']");
            // Cytoscape owns the canvas; use its rendered node position for a real pointer selection.
            var position = (Map<?, ?>) canvas.evaluate("""
                    element => element._cyreg.cy.nodes().filter(node => node.data('kind') === 'reaction'
                        && node.data('label').includes('<script>alert(1)</script>')).first().renderedPosition()
                    """);
            canvas.hover(new Locator.HoverOptions().setPosition(
                    ((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
            assertThat(viewers.nth(index).getByRole(AriaRole.TOOLTIP)).containsText("Median per day:");
            String group = (String) canvas.evaluate("""
                    element => element._cyreg.cy.nodes().filter(node => node.data('kind') === 'reaction'
                        && node.data('label').includes('<script>alert(1)</script>')).first().parent().id()
                    """);
            openGroupMenu(viewers.nth(index), group);
            assertThat(viewers.nth(index).getByRole(AriaRole.MENU)).containsText("<script>alert(1)</script>");
            assertThat(viewers.nth(index).locator("script")).hasCount(0);
            var liveTable = page.locator(".jeapReactionView").nth(index).locator("table[data-reaction-table='live']");
            assertThat(liveTable).containsText("<script>alert(1)</script>");
            assertThat(liveTable.locator("script")).hasCount(0);
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void largeGraph_hasUsableIconControlsAndAlwaysVisibleLegend() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        var viewer = page.locator("section[aria-label='Reaction chains']");
        showReactionViewer(0);
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                .setName("Open path view"))).hasCount(0);
        var buttons = viewer.getByRole(AriaRole.GROUP, new Locator.GetByRoleOptions()
                .setName("Graph view").setExact(true)).locator("button:not([aria-label=\"Open path view\"])");
        assertThat(buttons).hasCount(9);
        Assertions.assertThat(buttons.evaluateAll("buttons => buttons.map(button => button.getAttribute('aria-label'))"))
                .isEqualTo(List.of("Back", "Reset view", "Share link", "Show source", "Zoom out", "Zoom in", "Fit all", "Show minimap", "Fullscreen"));
        var toolbar = viewer.getByRole(AriaRole.GROUP, new Locator.GetByRoleOptions().setName("Graph view").setExact(true));
        assertThat(toolbar.locator(":scope > [role='group']")).hasCount(3);
        for (var button : buttons.all()) {
            assertThat(button).hasText("");
            assertThat(button.locator("svg")).hasCount(1);
            Assertions.assertThat(button.getAttribute("title"))
                    .isEqualTo(button.getAttribute("aria-label"));
        }
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        assertThat(viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Expand group"))).hasCount(0);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))).isVisible();
        openGroupMenu(viewer, group);
        assertThat(viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group"))).hasCount(0);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Expand group")).click();
        awaitGroupMembers(group, 3);
        var zoom = viewer.getByLabel("Zoom", new Locator.GetByLabelOptions().setExact(true));
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        String fitted = zoom.textContent();
        viewer.getByLabel("Zoom in", new Locator.GetByLabelOptions().setExact(true)).click();
        assertThat(zoom).not().hasText(fitted);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fullscreen").setExact(true)).click();
        assertThat(viewer.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Exit fullscreen"))).isVisible();
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))).isVisible();
        page.keyboard().press("Escape");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void initialViewAndResetFitTheWholeGraphWithoutEnlargingSmallGraphs() {
        for (String route : List.of(SYSTEM_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE)) {
            open("/" + route + "/");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
            String fits = """
                    element => {
                        const cy = element._cyreg.cy, box = cy.elements().renderedBoundingBox();
                        return cy.zoom() <= 1 && box.x1 >= 0 && box.y1 >= 0
                            && box.x2 <= cy.width() && box.y2 <= cy.height();
                    }
                    """;
            Assertions.assertThat(canvas.evaluate(fits)).isEqualTo(true);
            viewer.getByLabel("Zoom in", new Locator.GetByLabelOptions().setExact(true)).click();
            viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reset view")).click();
            Assertions.assertThat(canvas.evaluate(fits)).isEqualTo(true);
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void diagramLabels_followTheOriginalConventionsAndFitInsideGroups() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        var viewer = page.locator("section[aria-label='Reaction chains']");
        showReactionViewer(0);
        @SuppressWarnings("unchecked")
        var labels = (Map<String, Object>) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                element => {
                    const cy = element._cyreg.cy;
                    const groups = cy.nodes('[kind="group"]');
                    return {
                        variant: cy.nodes('[kind="message"]').filter(node => node.data('label').includes('express'))
                            .first().data('displayLabel'),
                        reaction: cy.nodes('[kind="reaction"]').filter(node => node.data('label').endsWith('4242'))
                            .first().data('displayLabel'),
                        titles: groups.filter(group => group.data('label') === 'shipping / shipping-dispatch')
                            .map(group => group.data('displayLabel')),
                        foreignBorder: cy.nodes('[kind="message"]').filter(node => node.data('label') === 'BillingSettledEvent')
                            .first().style('border-color'),
                        ownBorder: cy.nodes('[kind="message"]').filter(node => node.data('label') === 'ShippingDispatchedEvent')
                            .first().style('border-color'),
                        titlesInside: groups.every(group => {
                            const box = group.boundingBox({includeLabels: false, includeOverlays: false});
                            const title = group.boundingBox({includeNodes: false, includeEdges: false, includeOverlays: false});
                            return title.x1 >= box.x1 && title.x2 <= box.x2 && title.y1 >= box.y1
                                && title.y2 < group.children().boundingBox({includeLabels: false}).y1;
                        })
                    };
                }
                """);
        Assertions.assertThat(labels)
                .containsEntry("variant", "OrdersPayment\nAccepted\n[express]")
                .containsEntry("reaction", "12")
                .containsEntry("foreignBorder", "rgb(74,144,217)")
                .doesNotContainEntry("ownBorder", labels.get("foreignBorder"));
        Assertions.assertThat((Boolean) labels.get("titlesInside")).isTrue();
        Assertions.assertThat((List<?>) labels.get("titles"))
                .isNotEmpty().allSatisfy(title -> Assertions.assertThat(title).hasToString("shipping-dispatch"));
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))
                .locator("svg")).hasCount(6);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void triggerArrows_followIndividualReactionsAndCollapseToOneGroupArrow() {
        for (String route : List.of(SYSTEM_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE)) {
            open("/" + route + "/");
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            showReactionViewer(0);
            var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
            var targets = (List<?>) canvas.evaluate("""
                    element => {
                        const cy = element._cyreg.cy;
                        const reaction = cy.nodes('[kind="reaction"]').filter(node => JSON.parse(node.id())[1] === '4243').first();
                        return reaction.parent().children().incomers('edge').map(edge => JSON.parse(edge.target().id())[1]).sort();
                    }
                    """);
            Assertions.assertThat(targets).isEqualTo(List.of("4243", "4244", "4245"));
            var endpointsOnReactionBorders = canvas.evaluate("""
                    element => element._cyreg.cy.edges().every(edge => {
                        const target = edge.target(), source = edge.source();
                        if (target.data('kind') === 'reaction') {
                            const tip = edge.targetEndpoint();
                            if (Math.abs(tip.x - (target.position('x') - target.outerWidth() / 2)) > 0.1
                                || Math.abs(tip.y - target.position('y')) > 0.1) return false;
                        }
                        if (source.data('kind') === 'reaction') {
                            const start = edge.sourceEndpoint();
                            if (Math.abs(start.x - (source.position('x') + source.outerWidth() / 2)) > 0.1
                                || Math.abs(start.y - source.position('y')) > 0.1) return false;
                        }
                        return true;
                    })
                    """);
            Assertions.assertThat(endpointsOnReactionBorders).isEqualTo(true);
            var legend = viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true));
            assertThat(legend).not().containsText("Interface");
            assertThat(legend).containsText("REST");
            String group = groupOfReaction(viewer, "4243");
            openGroupMenu(viewer, group);
            String zoomBefore = viewer.getByLabel("Zoom", new Locator.GetByLabelOptions().setExact(true)).textContent();
            viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group")).click();
            awaitGroupMembers(group, 0);
            assertThat(viewer.getByLabel("Zoom", new Locator.GetByLabelOptions().setExact(true))).hasText(zoomBefore);
            Assertions.assertThat(canvas.evaluate("""
                    element => element._cyreg.cy.nodes('[kind="reaction"]').some(node => JSON.parse(node.id())[1] === '4242')
                    """)).isEqualTo(true);
            var arrows = (List<?>) canvas.evaluate("""
                    element => element._cyreg.cy.nodes('[kind="group"]')
                        .filter(node => node.data('label') === 'shipping / shipping-label-printer'
                            && node.children('[kind="marker"]').some(marker => marker.data('label') === '3'))
                        .incomers('edge').map(edge => edge.data('tooltip'))
                    """);
            Assertions.assertThat(arrows).isEqualTo(List.of(
                    "Sum of individual medians – approximation: 404\n4243: 400\n4244: 4\n4245: 0"));
            assertThat(legend.locator("span")).hasText(route.equals(MESSAGE_REACTIONS_ROUTE)
                    ? new String[]{"Message", "REST", "Reaction", "Group", "2Hidden reactions", "Pinned"}
                    : new String[]{"Message", "REST", "Reaction", "Group", "2Hidden reactions", "Other system"});
            assertNothingWentWrongInTheBrowser();
        }
    }

    @Test
    void graphContextMenuSuppressesTheBrowserMenuInNormalAndFullscreenViews() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        String group = groupOfReaction(viewer, "4243");
        page.evaluate("""
                () => document.addEventListener('contextmenu', event => {
                    setTimeout(() => document.documentElement.dataset.contextPrevented = String(event.defaultPrevented), 0);
                }, {capture: true})
                """);
        for (boolean fullscreen : List.of(false, true)) {
            if (fullscreen) viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                    .setName("Fullscreen").setExact(true)).click();
            page.evaluate("() => delete document.documentElement.dataset.contextPrevented");
            openGroupMenu(viewer, group);
            assertThat(page.locator("html")).hasAttribute("data-context-prevented", "true");
            page.evaluate("() => delete document.documentElement.dataset.contextPrevented");
            viewer.getByRole(AriaRole.MENU).click(new Locator.ClickOptions()
                    .setButton(MouseButton.RIGHT).setPosition(10, 10));
            assertThat(page.locator("html")).hasAttribute("data-context-prevented", "true");
            viewer.locator("div[aria-label^='Reaction graph.']").click(
                    new Locator.ClickOptions().setPosition(5, 5));
        }
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions()
                .setName("Exit fullscreen").setExact(true)).click();
        // Normal browser actions elsewhere on the page remain available.
        Assertions.assertThat(page.locator("h1").first().evaluate("""
                el => el.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true, cancelable: true}))
                """)).isEqualTo(true);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void groupMenu_supportsKeyboardAndLeavesTheOtherViewerUnchanged() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var first = viewers.first();
        showReactionViewer(0);
        showReactionViewer(1);
        String group = groupOfReaction(first, "4243");
        var secondCanvas = viewers.nth(1).locator("div[aria-label^='Reaction graph.']");
        Object otherNodes = secondCanvas.evaluate("element => element._cyreg.cy.elements().map(node => node.id())");
        openGroupMenu(first, group);
        page.keyboard().press("Escape");
        assertThat(first.getByRole(AriaRole.MENU)).hasCount(0);
        assertThat(first.locator("div[aria-label^='Reaction graph.']")).isFocused();
        page.keyboard().press("Shift+F10");
        var documentation = first.getByRole(AriaRole.MENUITEM,
                new Locator.GetByRoleOptions().setName("Go to component documentation"));
        assertThat(documentation).isFocused();
        assertThat(documentation).hasAttribute("href", "/" + COMPONENT_REACTIONS_ROUTE.replace(REACTING_COMPONENT, BUSY_COMPONENT) + "/");
        page.keyboard().press("ArrowDown");
        assertThat(first.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group"))).isFocused();
        Object beforeCollapse = actionPosition(first, group);
        page.keyboard().press("Enter");
        awaitGroupMembers(group, 0);
        awaitActionPosition(group, beforeCollapse);
        page.keyboard().press("Shift+F10");
        assertThat(documentation).isFocused();
        page.keyboard().press("ArrowDown");
        assertThat(first.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Expand group"))).isFocused();
        Object beforeExpand = actionPosition(first, group);
        page.keyboard().press("Enter");
        awaitGroupMembers(group, 3);
        awaitExpansionPosition(group, beforeExpand);
        Assertions.assertThat(secondCanvas.evaluate("element => element._cyreg.cy.elements().map(node => node.id())"))
                .isEqualTo(otherNodes);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void reactionTablesFollowTheirOwnViewerAndRetainFilteringAndSorting() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var entries = page.locator(".jeapReactionView");
        var viewer = entries.first().locator("section[aria-label='Reaction chains']");
        var table = entries.first().locator("table[data-reaction-table='live']");
        var otherTable = entries.nth(1).locator("table[data-reaction-table='live']");
        showReactionViewer(0);
        showReactionViewer(1);
        assertThat(table).isVisible();
        assertThat(otherTable).isVisible();
        assertThat(entries.first().locator(".jeapReactionTableFallback")).isHidden();
        int initialRows = table.locator("tbody tr").count();
        String otherRows = otherTable.locator("tbody").textContent();
        String group = groupOfReaction(viewer, "4243");
        var filter = entries.first().getByRole(AriaRole.SEARCHBOX,
                new Locator.GetByRoleOptions().setName("Filter the rows of this table"));
        filter.fill(BUSY_COMPONENT);
        assertThat(table.locator("tbody tr")).hasCount(3);
        table.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Sort by Median per day")).click();
        table.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Sort by Median per day")).click();
        assertThat(table.locator("tbody tr td:last-child")).hasText(new String[]{"400", "4", "0"});
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(table.locator("tbody tr")).hasCount(0);
        assertThat(filter).hasValue(BUSY_COMPONENT);
        assertThat(otherTable.locator("tbody")).hasText(otherRows);
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Expand group")).click();
        awaitGroupMembers(group, 3);
        assertThat(table.locator("tbody tr td:last-child")).hasText(new String[]{"400", "4", "0"});
        filter.press("Escape");
        assertThat(table.locator("tbody tr")).hasCount(initialRows);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void groupMenu_navigatesToAvailableComponentPagesInTheSameEnvironment() {
        String otherEnvironment = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        for (String prefix : List.of("", otherEnvironment)) {
            open(prefix + "/" + SYSTEM_REACTIONS_ROUTE + "/");
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            showReactionViewer(0);
            openGroupMenu(viewer, groupOfReaction(viewer, "4246"));
            assertThat(viewer.getByRole(AriaRole.MENU).locator("a")).hasCount(0);
            page.keyboard().press("Escape");
            openReactionMenu(viewer, "4242");
            assertThat(viewer.getByRole(AriaRole.MENU).locator("a")).hasCount(0);
            page.keyboard().press("Escape");
            openGroupMenu(viewer, groupOfReaction(viewer, "4242"));
            var link = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to component documentation"));
            assertThat(link).hasAttribute("href", prefix + "/" + COMPONENT_REACTIONS_ROUTE + "/");
            assertThat(link).isFocused();
            page.keyboard().press("ArrowDown");
            assertThat(viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Collapse group"))).isFocused();
            page.keyboard().press("Home");
            assertThat(link).isFocused();
            page.keyboard().press("Enter");
            page.waitForURL("**/" + COMPONENT_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            assertThat(page.locator("article table").first()).isVisible();
            assertThat(page.locator("article [data-plantuml-diagram]")).hasCount(0);
            var componentViewer = page.locator("section[aria-label='Reaction chains']").first();
            Assertions.assertThat(componentViewer.locator("div[aria-label^='Reaction graph.']")
                    .evaluate("el => el._cyreg.cy.nodes('[kind=\"reaction\"]').length")).isEqualTo(0);
            String collapsedGroup = (String) componentViewer.locator("div[aria-label^='Reaction graph.']")
                    .evaluate("el => el._cyreg.cy.nodes('[kind=\"group\"]').first().id()");
            openGroupMenu(componentViewer, collapsedGroup);
            assertThat(componentViewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Expand group"))).isVisible();
            assertThat(page.getByRole(AriaRole.MENU).locator("a")).hasCount(0);
            assertNothingWentWrongInTheBrowser();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explorerMenusLinkToGeneratedDocumentationInTheSelectedEnvironment(boolean severalTemplates) {
        if (severalTemplates) page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var json = JsonMapper.builder().build();
            var payload = json.readTree(response.text());
            for (String kind : List.of("componentLinks", "interfaceLinks")) {
                var links = (ArrayNode) payload.path("documentation").path(kind);
                var alternatives = new ArrayList<ObjectNode>();
                for (var value : links) {
                    var link = (ObjectNode) value;
                    link.put("templateId", "arc42");
                    var alternative = link.deepCopy();
                    alternative.put("templateId", "alternative");
                    alternative.put("href", link.path("href").asString().replaceAll("system-architecture/.*", ""));
                    alternatives.add(alternative);
                }
                alternatives.forEach(links::add);
            }
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        String suffix = severalTemplates ? " (arc42)" : "";
        String other = environments().stream().filter(environment -> !environment.main() && !environment.id().equals("abn"))
                .findFirst().orElseThrow().id();
        for (String environment : List.of("", other)) {
            String prefix = environment.isEmpty() ? "" : "/" + environment;
            open(prefix + "/reactions/");
            var search = page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Find an entry"));
            search.fill("OrdersPaymentAcceptedEvent");
            search.press("Enter");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']");
            openInterfaceMenu(viewer, "OrdersPaymentAcceptedEvent\n" + VARIANT);
            var eventLink = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to event documentation" + suffix).setExact(true));
            assertThat(eventLink).hasAttribute("href", prefix + "/" + MESSAGE_REACTIONS_ROUTE + "/");
            if (severalTemplates) {
                var alternative = viewer.getByRole(AriaRole.MENUITEM,
                        new Locator.GetByRoleOptions().setName("Go to event documentation (alternative)").setExact(true));
                assertThat(alternative).hasAttribute("href", prefix + "/systems/shipping/");
                assertThat(alternative).isFocused();
                page.keyboard().press("ArrowDown");
                assertThat(eventLink).isFocused();
            }
            eventLink.click();
            page.waitForURL("**/" + MESSAGE_REACTIONS_ROUTE + "/");
            assertThat(page.locator("article")).containsText("OrdersPaymentAcceptedEvent");

            open(prefix + "/reactions/");
            search.fill(REACTING_COMPONENT);
            search.press("Enter");
            showReactionViewer(0);
            String group = (String) viewer.locator("div[aria-label^='Reaction graph.']")
                    .evaluate("el => el._cyreg.cy.nodes('[kind=\"group\"]').first().id()");
            openGroupMenu(viewer, group);
            var componentLink = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to component documentation" + suffix).setExact(true));
            assertThat(componentLink).hasAttribute("href", prefix + "/" + COMPONENT_REACTIONS_ROUTE + "/");
            page.keyboard().press("Escape");
            openInterfaceMenu(viewer, "shipping-dispatch\nGET /shipments");
            var restLink = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to REST documentation" + suffix).setExact(true));
            String restRoute = COMPONENT_REACTIONS_ROUTE.replace("runtime-view/component-reactions", "building-block-view/rest-api");
            assertThat(restLink).hasAttribute("href", prefix + "/" + restRoute + "/");
            restLink.click();
            page.waitForURL("**/" + restRoute + "/");
            assertThat(page.locator("article")).containsText("/shipments");
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void locatingAndRestoringVeryLargeGroupsKeepsTheInteractiveZoomLimit() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        canvas.evaluate("""
                element => {
                    const cy = element._cyreg.cy;
                    // Spread the members of one expanded group far enough to require a zoom below 3%.
                    const group = cy.nodes('[kind="group"]').filter(node => node.data('label').includes('shipping-label-printer')).first();
                    group.children(':childless').positions((node, index) => ({x: index * 100000, y: 0}));
                }
                """);
        var search = viewer.getByRole(AriaRole.COMBOBOX, new Locator.GetByRoleOptions().setName("Search this graph"));
        search.fill(BUSY_COMPONENT);
        search.press("Enter");
        page.waitForFunction("""
                () => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && cy.zoom() < 0.03 && cy.minZoom() === cy.zoom();
                }
                """);
        assertWheelZoomOutKeepsViewport(canvas);
        String link = sharedLink(viewer);
        page.navigate(link);
        showReactionViewer(0);
        page.waitForFunction("""
                () => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && cy.zoom() < 0.03 && cy.minZoom() === cy.zoom();
                }
                """);
        assertWheelZoomOutKeepsViewport(canvas);
        canvas.evaluate("el => el._cyreg.cy.zoom(0.05)");
        Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.minZoom()")).isEqualTo(0.03);
        wheelOverGraph(canvas, 500);
        page.waitForFunction("""
                () => {
                    const zoom = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy.zoom();
                    return zoom >= 0.03 && zoom < 0.05;
                }
                """);
        canvas.evaluate("el => el._cyreg.cy.zoom(0.03)");
        assertWheelZoomOutKeepsViewport(canvas);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Zoom out").setExact(true)).click();
        Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.zoom()")).isEqualTo(0.03);
        assertNothingWentWrongInTheBrowser();
    }

    private void assertWheelZoomOutKeepsViewport(Locator canvas) {
        Object before = canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan()})");
        wheelOverGraph(canvas, 200);
        Assertions.assertThat(canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan()})")).isEqualTo(before);
    }

    private void wheelOverGraph(Locator canvas, double delta) {
        canvas.hover();
        page.waitForFunction("() => !document.querySelector('div[aria-label^=\"Reaction graph.\"]')?._cyreg?.cy.renderer().scrollingPage");
        canvas.evaluate("el => { window.wheelHandled = false; el._cyreg.cy.one('scrollzoom', () => { window.wheelHandled = true; }); }");
        page.mouse().wheel(0, delta);
        page.waitForFunction("() => window.wheelHandled === true");
    }

    @Test
    void interfaceMenus_linkEventsAndRestInTheCurrentEnvironmentWithoutNodeTooltips() {
        // Both HTTP observations and their navigation metadata come from the generated V2 fixture.
        String otherEnvironment = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        String restRoute = COMPONENT_REACTIONS_ROUTE.replace("runtime-view/component-reactions", "building-block-view/rest-api");
        for (String prefix : List.of("", otherEnvironment)) {
            open(prefix + "/" + SYSTEM_REACTIONS_ROUTE + "/");
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            showReactionViewer(0);
            openInterfaceMenu(viewer, UNDOCUMENTED_MESSAGE);
            assertThat(viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Documentation unavailable"))).isDisabled();
            page.keyboard().press("Escape");
            openInterfaceMenu(viewer, "OrdersPaymentAcceptedEvent");
            var eventLink = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to event documentation"));
            assertThat(eventLink).hasAttribute("href", prefix + "/" + MESSAGE_REACTIONS_ROUTE + "/");
            eventLink.click();
            page.waitForURL("**/" + MESSAGE_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            Assertions.assertThat(page.locator("section[aria-label='Reaction chains']").first()
                    .locator("div[aria-label^='Reaction graph.']").evaluate("""
                    element => {
                        const cy = element._cyreg.cy;
                        const messages = cy.nodes('[kind="message"]');
                        return messages.length === 1 && messages.first().data('pinned') && cy.nodes('[?pinned]').length === 1
                            && cy.edges().every(edge => edge.source().id() === messages.first().id()
                                || edge.target().id() === messages.first().id());
                    }
                    """)).isEqualTo(true);

            open(prefix + "/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                    element => {
                        const rest = element._cyreg.cy.nodes('[kind="http"]').first();
                        return rest.visible() && rest.width() > 0 && rest.width() <= 200
                            && rest.height() >= 60 && rest.height() < 120;
                    }
                    """)).isEqualTo(true);
            openInterfaceMenu(viewer, "shipping-dispatch\nGET /shipments");
            var restLink = viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Go to REST documentation"));
            assertThat(restLink).hasAttribute("href", prefix + "/" + restRoute + "/");
            page.keyboard().press("Enter");
            page.waitForURL("**/" + restRoute + "/");
            assertThat(page.locator("article")).containsText("/shipments");
            assertNothingWentWrongInTheBrowser();
        }
    }

    private void openInterfaceMenu(Locator viewer, String label) {
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (Map<?, ?>) canvas.evaluate("""
                (element, label) => element._cyreg.cy.nodes().filter(node => node.data('label') === label).first().renderedPosition()
                """, label);
        double x = ((Number) position.get("x")).doubleValue(), y = ((Number) position.get("y")).doubleValue();
        canvas.hover(new Locator.HoverOptions().setPosition(x, y));
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).hover();
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        canvas.click(new Locator.ClickOptions().setButton(MouseButton.RIGHT).setPosition(x, y));
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        assertThat(viewer.locator(":scope > p")).hasCount(0);
    }

    @Test
    void edgeDetailsAppearOnHoverAndDisappearWhenLeavingTheGraph() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        showReactionViewer(0);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (Map<?, ?>) canvas.evaluate("""
                element => element._cyreg.cy.edges().filter(edge => JSON.parse(edge.source().id())[1] === '4242'
                    && edge.target().data('kind') === 'message').first().renderedMidpoint()
                """);
        canvas.hover(new Locator.HoverOptions().setPosition(
                ((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).containsText("Median per day: 12");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).hover();
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        assertThat(viewer.locator(":scope > p")).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sourceView_copiesTheDisplayedProjectionAndPreservesViewerState() {
        page.addInitScript("""
                Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
                    writeText: async text => { window.copiedGraphSource = text; }
                }});
                """);
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            route.fulfill(new FulfillOptions().setResponse(response).setBody(
                    response.text().replace("shipping-dispatch", "<script>alert(1)</script>")));
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var viewer = viewers.first();
        showReactionViewer(0);
        showReactionViewer(1);
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Object before = canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan(), ids: el._cyreg.cy.elements().map(n => n.id()).sort()})");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Show source")).click();
        var source = viewer.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Graph source (JSON)"));
        assertThat(source).isVisible();
        Assertions.assertThat(source.evaluate(
                "el => el.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true, cancelable: true}))"))
                .isEqualTo(true);
        assertThat(viewers.nth(1).getByRole(AriaRole.TEXTBOX)).hasCount(0);
        showReactionViewer(1);
        Assertions.assertThat(source.inputValue()).contains("<script>alert(1)</script>");
        assertThat(viewer.locator("script")).hasCount(0);
        Object sourceIds = source.evaluate("el => JSON.parse(el.value).elements.filter(n => n.data.identity).map(n => JSON.stringify(n.data.identity)).sort()");
        Assertions.assertThat(sourceIds).isEqualTo(canvas.evaluate(
                "el => el._cyreg.cy.nodes().filter(n => n.data('identity')).map(n => JSON.stringify(n.data('identity'))).sort()"));
        Assertions.assertThat(source.evaluate("""
                el => {
                    const source = JSON.parse(el.value), ids = new Set(source.elements.map(e => e.data.id));
                    return source.formatVersion === 2 && source.elements.every(({data}) =>
                        /^(node|group|edge|marker)-[0-9]+$/.test(data.id)
                        && ['parent', 'source', 'target', 'anchor'].every(field => !data[field] || ids.has(data[field])));
                }
                """)).isEqualTo(true);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Copy source")).click();
        var feedback = viewer.getByText("Code copied", new Locator.GetByTextOptions().setExact(true));
        assertThat(feedback).isVisible();
        assertThat(feedback).hasAttribute("role", "status");
        assertThat(viewer.locator("section[aria-label='Graph source'] [role='status']")).hasCount(0);
        Assertions.assertThat(page.evaluate("window.copiedGraphSource")).isEqualTo(source.inputValue());
        page.evaluate("() => { navigator.clipboard.writeText = async () => { throw new Error('denied'); }; }");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Copy source")).click();
        assertThat(feedback).hasCount(0);
        assertThat(viewer.locator("section[aria-label='Graph source'] [role='status']")).containsText("Copy the selected text");
        Assertions.assertThat(source.evaluate("el => el.selectionStart === 0 && el.selectionEnd === el.value.length")).isEqualTo(true);
        source.press("Escape");
        assertThat(source).hasCount(0);
        assertThat(canvas).isVisible();
        Assertions.assertThat(canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan(), ids: el._cyreg.cy.elements().map(n => n.id()).sort()})"))
                .isEqualTo(before);
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void minimap_tracksViewportAndSupportsClickDragKeyboardAndGroupChanges() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var viewer = viewers.first();
        showReactionViewer(0);
        showReactionViewer(1);
        var otherCanvas = viewers.nth(1).locator("div[aria-label^='Reaction graph.']");
        Object otherView = otherCanvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan()})");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Show minimap")).click();
        var minimap = viewer.getByRole(AriaRole.GROUP, new Locator.GetByRoleOptions().setName("Minimap").setExact(true));
        assertThat(minimap).isVisible();
        assertThat(viewers.nth(1).getByRole(AriaRole.GROUP, new Locator.GetByRoleOptions().setName("Minimap").setExact(true))).hasCount(0);
        Assertions.assertThat(minimap.locator("img").evaluate("img => img.complete && img.naturalWidth > 0")).isEqualTo(true);
        var frame = minimap.locator("[data-minimap-viewport]");
        String originalFrame = frame.getAttribute("style");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Zoom in")).click();
        assertThat(frame).not().hasAttribute("style", originalFrame);
        var map = minimap.locator("[tabindex='0']");
        var box = map.boundingBox();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Object zoom = canvas.evaluate("el => el._cyreg.cy.zoom()");
        map.click(new Locator.ClickOptions().setPosition(box.width * 0.75, box.height * 0.75));
        Assertions.assertThat(canvas.evaluate("""
                el => { const cy = el._cyreg.cy, bb = cy.elements().boundingBox(), extent = cy.extent();
                    return Math.abs((extent.x1 + extent.x2) / 2 - (bb.x1 + bb.w * .75)) < bb.w * .02
                        && Math.abs((extent.y1 + extent.y2) / 2 - (bb.y1 + bb.h * .75)) < bb.h * .02; }
                """)).isEqualTo(true);
        Object clickedPan = canvas.evaluate("el => el._cyreg.cy.pan()");
        box = map.boundingBox(); // Clicking may scroll the page; use the map's current screen coordinates.
        page.mouse().move(box.x + box.width * .75, box.y + box.height * .75);
        page.mouse().down();
        page.mouse().move(box.x + box.width * .25, box.y + box.height * .25);
        page.mouse().up();
        Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.pan()")).isNotEqualTo(clickedPan);
        Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.zoom()")).isEqualTo(zoom);
        Object draggedPan = canvas.evaluate("el => el._cyreg.cy.pan()");
        map.press("ArrowRight");
        Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.pan()")).isNotEqualTo(draggedPan);
        String originalImage = minimap.locator("img").getAttribute("src");
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(minimap.locator("img")).not().hasAttribute("src", originalImage);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fullscreen").setExact(true)).click();
        assertThat(minimap).isVisible();
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true))).isVisible();
        Assertions.assertThat(otherCanvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan()})")).isEqualTo(otherView);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void groupMenuPreservesHiddenMembersAndGroupMarkerRestoresThem() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        String group = groupOfReaction(viewer, "4243");
        openReactionMenu(viewer, "4244");
        menuAction(viewer, "Hide");
        awaitGroupMembers(group, 2);
        openGroupMenu(viewer, group);
        menuAction(viewer, "Collapse group");
        awaitGroupMembers(group, 0);
        openGroupMenu(viewer, group);
        menuAction(viewer, "Expand group");
        awaitGroupMembers(group, 2);
        awaitGraphKeys(List.of("4244"), false);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        clickGraphNode(viewer, "hidden:" + group, false);
        awaitGroupMembers(group, 3);
        awaitGraphKeys(List.of("4244"), true);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void pinnedReactionSurvivesCollapseAndMarkerRestoresTheOtherMembers() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        String group = groupOfReaction(viewer, "4243");
        openReactionMenu(viewer, "4243");
        menuAction(viewer, "Pin");
        awaitPinned("4243");
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true)))
                .containsText("Pinned");
        Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                el => {
                    const node = el._cyreg.cy.nodes('[?pinned]').filter(n => n.data('identity')?.key === '4243').first();
                    return node.style('background-image').includes('data:image/svg+xml')
                        && node.style('border-color') === 'rgb(182,144,69)';
                }
                """)).isEqualTo(true);
        openGroupMenu(viewer, group);
        menuAction(viewer, "Collapse group");
        awaitGroupMembers(group, 1);
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Assertions.assertThat(canvas.evaluate("""
                (el, group) => {
                    const cy = el._cyreg.cy, frame = cy.getElementById(group);
                    return {pins: frame.children('[?pinned]').length,
                        hidden: frame.children('[kind="marker"]').data('label'),
                        bundles: frame.connectedEdges().length};
                }
                """, group)).isEqualTo(Map.of("pins", 1, "hidden", "2", "bundles", 1));
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        clickGraphNode(viewer, "hidden:" + group, false);
        awaitGroupMembers(group, 3);
        openGroupMenu(viewer, group);
        menuAction(viewer, "Unpin members and hide group");
        awaitGraphKeys(List.of("4243"), false);
        assertThat(viewer.getByText("Hidden content", new Locator.GetByTextOptions().setExact(true))).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void hideWithoutPinsRemovesOnlyTheTargetAndOrphanNeighbours() {
        for (boolean group : List.of(false, true)) {
            installVisibilityFixture(false);
            open("/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']");
            if (group) {
                openGroupMenu(viewer, groupOfReaction(viewer, "r1"));
                menuAction(viewer, "Hide group");
            } else {
                openFixtureInterfaceMenu(viewer, "B");
                menuAction(viewer, "Hide");
            }
            awaitGraphKeys(List.of("C", "r2"), true);
            awaitGraphKeys(group ? List.of("A", "r1") : List.of("B"), false);
            awaitGraphKeys(group ? List.of("B") : List.of("A", "r1"), true);
            awaitGraphKeys(List.of("D", "E", "r4"), true);
            assertNothingWentWrongInTheBrowser();
            page.unroute("**/reaction-graph.json");
        }
    }

    @Test
    void hiddenTriggerMarkersFollowReactionRowsAndCollapseToOneGroupMarker() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        menuAction(viewer, "Pin");
        String message = (String) canvas.evaluate("el => el._cyreg.cy.nodes('[kind=\"message\"]').first().id()");
        clickGraphNode(viewer, message, true);
        menuAction(viewer, "Unpin and hide");
        awaitGroupMembers(group, 3);
        Assertions.assertThat(canvas.evaluate("""
                (el, group) => {
                    const cy = el._cyreg.cy;
                    const markers = cy.nodes('[kind="marker"]').filter(n => n.data('anchor')
                        && cy.getElementById(n.data('anchor')).parent().id() === group && n.data('restore'));
                    return markers.length === 3 && markers.every(marker => {
                        const anchor = cy.getElementById(marker.data('anchor'));
                        return Math.abs(marker.position('y') - anchor.position('y')) <= 10
                            && Math.abs(marker.position('x') - anchor.position('x')) < 100;
                    });
                }
                """, group)).isEqualTo(true);
        openGroupMenu(viewer, group);
        menuAction(viewer, "Collapse group");
        awaitGroupMembers(group, 0);
        Assertions.assertThat(canvas.evaluate("""
                (el, group) => el._cyreg.cy.nodes('[kind="marker"]')
                    .filter(n => n.data('anchor') === group && n.data('restore')).map(n => n.data('label'))
                """, group)).isEqualTo(List.of("1"));
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void hideUsesOnlyVisibleAlternatePathsAndKeepsSeparateRegions() {
        for (boolean alternate : List.of(false, true)) {
            installVisibilityFixture(alternate);
            open("/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']");
            openFixtureInterfaceMenu(viewer, "A");
            menuAction(viewer, "Pin");
            awaitPinned("A");
            openFixtureInterfaceMenu(viewer, "B");
            menuAction(viewer, "Hide");
            awaitGraphKeys(List.of("B"), false);
            awaitGraphKeys(List.of("A", "r1", "D", "r4", "E"), true);
            awaitGraphKeys(List.of("C", "r2"), true);
            viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
            String marker = (String) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                    el => el._cyreg.cy.nodes('[kind="marker"]').filter(n => n.data('restore')?.some(id => JSON.parse(id)[1] === 'B')).first().id()
                    """);
            clickGraphNode(viewer, marker, false);
            awaitGraphKeys(List.of("B"), true);
            assertNothingWentWrongInTheBrowser();
            page.unroute("**/reaction-graph.json");
        }
    }

    @Test
    void hidingHighlightsTheSurvivingPredecessorOrSuccessorWithoutPinningIt() {
        for (boolean predecessor : List.of(true, false)) {
            installVisibilityFixture(false);
            open("/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']");
            if (predecessor) {
                openFixtureInterfaceMenu(viewer, "B");
                menuAction(viewer, "Hide");
            } else {
                openReactionMenu(viewer, "r1");
                menuAction(viewer, "Hide");
            }
            String expected = predecessor ? "r1" : "B";
            page.waitForFunction("""
                    key => {
                        const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                        if (!cy) return false;
                        const marked = cy.nodes('.newlyExpanded');
                        if (marked.length !== 1 || JSON.parse(marked.first().id())[1] !== key) return false;
                        const point = marked.first().renderedPosition();
                        return !marked.first().data('pinned') && point.x >= 0 && point.x <= cy.width()
                            && point.y >= 0 && point.y <= cy.height();
                    }
                    """, expected);
            page.waitForFunction("""
                    () => document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy
                        .nodes('.newlyExpanded').length === 0
                    """);
            assertNothingWentWrongInTheBrowser();
            page.unroute("**/reaction-graph.json");
        }
    }

    @Test
    void connectedSubgraphHidesBothDirectionsAndStopsAtPins() {
        for (boolean pinned : List.of(false, true)) {
            installVisibilityFixture(false);
            open("/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            var viewer = page.locator("section[aria-label='Reaction chains']");
            if (pinned) {
                openFixtureInterfaceMenu(viewer, "B");
                menuAction(viewer, "Pin");
                awaitPinned("B");
            }
            openReactionMenu(viewer, "r2");
            assertThat(viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Hide").setExact(true))).isVisible();
            assertThat(viewer.getByRole(AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Hide outgoing paths").setExact(true))).isVisible();
            menuAction(viewer, "Hide connected subgraph");
            awaitGraphKeys(List.of("C", "r2"), false);
            awaitGraphKeys(List.of("A", "r1", "B"), pinned);
            awaitGraphKeys(List.of("D", "r4", "E"), true);
            assertNothingWentWrongInTheBrowser();
            page.unroute("**/reaction-graph.json");
        }
    }

    @Test
    void downstreamSubgraphHideStopsAtPinnedReaction() {
        installVisibilityFixture(false);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        openReactionMenu(viewer, "r2");
        menuAction(viewer, "Pin");
        awaitPinned("r2");
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Hide outgoing paths");
        awaitGraphKeys(List.of("r1", "B"), false);
        awaitGraphKeys(List.of("A", "r2", "C", "D", "r4", "E"), true);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void outgoingHideTerminatesOnCyclesAndSupersededRevealCanBePinned() {
        installVisibilityFixture(true);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        viewer.locator("summary").filter(new Locator.FilterOptions().setHasText("superseded reactions hidden")).click();
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Show shipping-dispatch: old")).click();
        awaitGraphKeys(List.of("old"), true);
        openReactionMenu(viewer, "old");
        menuAction(viewer, "Pin");
        awaitPinned("old");
        openFixtureInterfaceMenu(viewer, "B");
        menuAction(viewer, "Hide outgoing paths");
        awaitGraphKeys(List.of("B", "old", "D", "r4", "E"), true);
        awaitGraphKeys(List.of("A", "C", "r1", "r2", "r3"), false);
        openReactionMenu(viewer, "old");
        menuAction(viewer, "Unpin and hide");
        awaitGraphKeys(List.of("old"), false);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void boundaryCirclesOpenTheNextReactionAndInterface() {
        installVisibilityFixture(false);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Focus");
        awaitGraphKeys(List.of("A", "r1"), true);
        awaitGraphKeys(List.of("B", "r2", "C"), false);
        assertNoPins(viewer);
        for (String key : List.of("B", "r2", "C")) {
            viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
            String marker = (String) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                    el => el._cyreg.cy.nodes('[kind="marker"]').filter(n => n.data('explore')?.direction === 'outgoing').first().id()
                    """);
            String anchor = (String) viewer.locator("div[aria-label^='Reaction graph.']").evaluate(
                    "(el, id) => el._cyreg.cy.getElementById(id).data('anchor')", marker);
            Object beforeExpand = actionPosition(viewer, anchor);
            clickGraphNode(viewer, marker, false);
            awaitGraphKeys(List.of(key), true);
            awaitExpansionPosition(anchor, beforeExpand);
            assertThat(viewer.locator("div[aria-label^='Reaction graph.']")).isFocused();
        }
        awaitGraphKeys(List.of("D", "E", "r4", "old"), false);
        assertNoPins(viewer);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void directedExpansionChecksDepthAndPreparedViewsDoNotOfferPathSearch() {
        installVisibilityFixture(true);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        openFixtureInterfaceMenu(viewer, "B");
        menuAction(viewer, "Focus");
        awaitGraphKeys(List.of("B", "r1", "r2"), true);
        openFixtureInterfaceMenu(viewer, "B");
        menuAction(viewer, "Expand outgoing paths…");
        var dialog = page.getByRole(AriaRole.DIALOG);
        var expand = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Expand").setExact(true));
        assertThat(expand).isDisabled();
        dialog.getByLabel("Edge depth").fill("0");
        assertThat(expand).isDisabled();
        dialog.getByLabel("Edge depth").fill("2");
        expand.click();
        awaitGraphKeys(List.of("C"), true);
        assertNoPins(viewer);
        assertThat(viewer.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Open path view"))).hasCount(0);
        openFixtureInterfaceMenu(viewer, "B");
        assertThat(viewer.getByRole(AriaRole.MENUITEM,
                new Locator.GetByRoleOptions().setName("Find paths from here…"))).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void largeExpansionAutomaticallyFitsAndHighlightsNewNodes() {
        installVisibilityFixture(false);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Focus");
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Expand outgoing paths…");
        canvas.evaluate("el => el._cyreg.cy.zoom(3)");
        var dialog = page.getByRole(AriaRole.DIALOG);
        dialog.getByLabel("Edge depth").fill("4");
        dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Expand").setExact(true)).click();
        awaitGraphKeys(List.of("C", "r2"), true);
        var fit = viewer.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Show expansion").setExact(true));
        assertThat(fit).hasCount(0);
        Assertions.assertThat(canvas.evaluate(
                "el => el._cyreg.cy.nodes('.newlyExpanded').length > 0")).isEqualTo(true);
        page.waitForFunction("""
                () => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && !cy.animated() && cy.zoom() < 3;
                }
                """);
        Assertions.assertThat(canvas.evaluate("""
                el => {
                    const cy = el._cyreg.cy;
                    return cy.nodes('[kind="message"], [kind="reaction"]').every(node => {
                        const p = node.renderedPosition();
                        return p.x >= 0 && p.x <= cy.width() && p.y >= 0 && p.y <= cy.height();
                    });
                }
                """)).isEqualTo(true);
        page.waitForFunction("""
                () => document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy
                    .nodes('.newlyExpanded').length === 0
                """);
        assertNothingWentWrongInTheBrowser();
    }

    private void assertNoPins(Locator viewer) {
        Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']")
                .evaluate("el => el._cyreg.cy.nodes('[?pinned]').length")).isEqualTo(0);
        assertThat(viewer.getByLabel("Legend", new Locator.GetByLabelOptions().setExact(true)))
                .not().containsText("Pinned");
    }

    private Object actionPosition(Locator viewer, String id) {
        return viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                (el, id) => {
                    const cy = el._cyreg.cy;
                    return {point: cy.getElementById(id).renderedPosition(), zoom: cy.zoom()};
                }
                """, id);
    }

    private void awaitExpansionPosition(String id, Object before) {
        page.waitForFunction("""
                ({id, before}) => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    if (!cy || cy.animated() || !cy.getElementById(id).length) return false;
                    const point = cy.getElementById(id).renderedPosition();
                    return point.x >= 0 && point.x <= cy.width() && point.y >= 0 && point.y <= cy.height()
                        && cy.zoom() > 0 && cy.zoom() <= before.zoom + 0.001;
                }
                """, Map.of("id", id, "before", before));
    }

    private void awaitActionPosition(String id, Object before) {
        page.waitForFunction("""
                ({id, before}) => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    if (!cy || !cy.getElementById(id).length) return false;
                    const point = cy.getElementById(id).renderedPosition();
                    return Math.abs(point.x - before.point.x) < 2 && Math.abs(point.y - before.point.y) < 2
                        && Math.abs(cy.zoom() - before.zoom) < 0.001;
                }
                """, Map.of("id", id, "before", before));
    }

    private void menuAction(Locator viewer, String name) {
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions()
                .setName(name).setExact(true)).click();
    }

    private void openFixtureInterfaceMenu(Locator viewer, String name) {
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        clickGraphNode(viewer, "[\"INTERFACE\",\"" + name + "\"]", true);
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
    }

    private void clickGraphNode(Locator viewer, String id, boolean contextMenu) {
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        // Finish scrolling before resolving the renderer's click coordinates.
        canvas.scrollIntoViewIfNeeded();
        var position = (Map<?, ?>) canvas.evaluate("""
                async (el, id) => {
                    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                    delete el.dataset.clickedNode;
                    el._cyreg.cy.one('tapstart cxttap', 'node', event => { el.dataset.clickedNode = event.target.id(); });
                    return el._cyreg.cy.getElementById(id).renderedPosition();
                }
                """, id);
        canvas.click(new Locator.ClickOptions()
                .setButton(contextMenu ? MouseButton.RIGHT : MouseButton.LEFT)
                .setPosition(((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
        assertThat(canvas).hasAttribute("data-clicked-node", id);
    }

    private void awaitPinned(String key) {
        page.waitForFunction("""
                key => [...document.querySelectorAll('div[aria-label^="Reaction graph."]')]
                    .some(el => el._cyreg?.cy.nodes().some(n => n.data('identity')?.key === key && n.data('pinned')))
                """, key);
    }

    private void awaitGraphKeys(List<String> keys, boolean present) {
        page.waitForFunction("""
                args => {
                    const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && args.keys.every(key => cy.nodes().some(n => n.data('identity')?.key === key) === args.present);
                }
                """, Map.of("keys", keys, "present", present));
    }

    private void installVisibilityFixture(boolean cycle) {
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            var mapper = JsonMapper.builder().build();
            var payload = (ObjectNode) mapper.readTree(response.text());
            payload.putArray("ownership");
            var graph = payload.putObject("graph");
            var nodes = graph.putArray("nodes");
            for (String name : List.of("A", "B", "C", "D", "E")) {
                var node = nodes.addObject();
                node.putObject("identity").put("nodeType", "INTERFACE").put("key", name);
                node.put("nodeType", "MESSAGE");
                node.putObject("interfaceDetails").putObject("message").put("messageType", name).put("variant", "");
            }
            for (String name : List.of("r1", "r2", "r3", "r4", "old")) {
                var node = nodes.addObject();
                node.putObject("identity").put("nodeType", "REACTION").put("key", name);
                node.put("nodeType", "REACTION");
                node.putObject("reaction").put("system", "shipping").put("component", "shipping-dispatch")
                        .put("median", 10).put("status", name.equals("old") ? "SUPERSEDED" : "ACTIVE").put("groupKey", name);
            }
            var edges = graph.putArray("edges");
            for (String[] flow : new String[][]{{"A", "r1", "B"}, {"B", "r2", "C"}, {"D", "r4", "E"}, {"A", "old", "C"}}) {
                addFixtureEdge(edges, flow[0], flow[1], "TRIGGER");
                addFixtureEdge(edges, flow[1], flow[2], "ACTION");
            }
            if (cycle) {
                addFixtureEdge(edges, "C", "r3", "TRIGGER");
                addFixtureEdge(edges, "r3", "A", "ACTION");
            }
            var predecessor = edges.addObject().put("edgeType", "PREDECESSOR");
            predecessor.putObject("source").put("nodeType", "REACTION").put("key", "r1");
            predecessor.putObject("target").put("nodeType", "REACTION").put("key", "old");
            route.fulfill(new FulfillOptions().setResponse(response).setBody(mapper.writeValueAsString(payload)));
        });
    }

    private void addFixtureEdge(ArrayNode edges, String source, String target, String type) {
        var edge = edges.addObject().put("edgeType", type);
        edge.putObject("source").put("nodeType", type.equals("ACTION") ? "REACTION" : "INTERFACE").put("key", source);
        edge.putObject("target").put("nodeType", type.equals("TRIGGER") ? "REACTION" : "INTERFACE").put("key", target);
    }

    @Test
    void viewerBackRestoresTheViewportAndPositionsBeforeTheSemanticAction() {
        installVisibilityFixture(false);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        openFixtureInterfaceMenu(viewer, "A");
        page.keyboard().press("Escape");
        canvas.evaluate("""
                el => {
                    const cy = el._cyreg.cy;
                    cy.getElementById('["INTERFACE","A"]').position({x: 120, y: 140});
                    cy.zoom(1); cy.pan({x: 90, y: 60});
                }
                """);
        Object preceding = presentationOf(viewer);
        canvas.focus();
        page.keyboard().press("Shift+F10");
        menuAction(viewer, "Pin");
        awaitPinned("A");
        canvas.evaluate("el => { el._cyreg.cy.zoom(1.8); el._cyreg.cy.pan({x: -170, y: -90}); }");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitPresentation(page, 0, preceding, true);
        assertThat(viewer.getByLabel("Zoom", new Locator.GetByLabelOptions().setExact(true))).hasText("100%");
        assertNoPins(viewer);
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sharedLinksRestoreZoomAndCentreInAFreshBrowserWithADifferentViewportSize() {
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                el => { el._cyreg.cy.zoom(1.3); el._cyreg.cy.pan({x: -210, y: 75}); }
                """);
        Object preceding = presentationOf(viewer);
        String link = sharedLink(viewer);
        try (var fresh = context.browser().newContext(new Browser.NewContextOptions()
                .setViewportSize(900, 950).setColorScheme(ColorScheme.LIGHT))) {
            var document = fresh.newPage();
            document.navigate(link);
            document.locator("section[aria-label='Reaction chains']").scrollIntoViewIfNeeded();
            awaitPresentation(document, 0, preceding, false);
            document.reload();
            document.locator("section[aria-label='Reaction chains']").scrollIntoViewIfNeeded();
            awaitPresentation(document, 0, preceding, false);
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void viewerBackRestoresCombinedActionsAndResetWithoutChangingBrowserNavigationOrOtherViewers() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var viewer = viewers.first();
        String initial = semanticProjection(viewer), other = semanticProjection(viewers.nth(1));
        String originalUrl = page.url();
        String group = groupOfReaction(viewer, "4243");
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group").setExact(true)).click();
        awaitGroupMembers(group, 0);
        String collapsed = semanticProjection(viewer);
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Pin").setExact(true)).click();
        page.waitForFunction("""
                id => document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')
                    ?._cyreg?.cy.getElementById(id).data('pinned') === true
                """, group);
        String pinned = semanticProjection(viewer);
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Unpin members and hide group").setExact(true)).click();
        page.waitForFunction("""
                id => !document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')
                    ?._cyreg?.cy.getElementById(id).length
                """, group);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, pinned);
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Focus").setExact(true)).click();
        page.waitForFunction("""
                () => document.querySelector('section[aria-label="Reaction chains"] strong')?.textContent.includes('(Group)')
                """);
        String focused = semanticProjection(viewer);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reset view").setExact(true)).click();
        awaitSemanticProjection(page, 0, initial);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, focused);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Zoom in").setExact(true)).click();
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all").setExact(true)).click();
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, pinned);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, collapsed);
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, initial);
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        Assertions.assertThat(semanticProjection(viewers.nth(1))).isEqualTo(other);
        Assertions.assertThat(URI.create(page.url()).getPath())
                .isEqualTo(URI.create(originalUrl).getPath());
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sharedVariantOpensInAFreshLightBrowserWithEmptyHistoryAndAnUndoableReset() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var viewer = viewers.nth(1);
        String initial = semanticProjection(viewer), other = semanticProjection(viewers.first());
        String group = groupOfReaction(viewer, "5242");
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Collapse group").setExact(true)).click();
        page.waitForFunction("""
                () => document.querySelectorAll('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')[1]
                    ?._cyreg?.cy.nodes('[kind="reaction"]').every(node => JSON.parse(node.id())[1] !== '5242')
                """);
        String shared = semanticProjection(viewer);
        String link = sharedLink(viewer);
        try (var fresh = context.browser().newContext(new Browser.NewContextOptions()
                .setColorScheme(ColorScheme.LIGHT))) {
            var document = fresh.newPage();
            document.addInitScript("""
                    window.reactionTestScrollTo = window.scrollTo.bind(window);
                    window.reactionPageScrolls = 0;
                    window.scrollTo = (...args) => {
                        window.reactionPageScrolls++;
                        window.reactionTestScrollTo(...args);
                    };
                    """);
            document.navigate(link);
            var restored = document.locator("section[aria-label='Reaction chains']").nth(1);
            // Opening the link itself activates and scrolls to the second variant, even below the fold.
            awaitSemanticProjection(document, 1, shared);
            assertThat(restored).isInViewport();
            document.waitForFunction("""
                    () => {
                        const top = document.querySelectorAll('section[aria-label="Reaction chains"]')[1].getBoundingClientRect().top;
                        return top >= 60 && top < 150;
                    }
                    """);
            int initialScrolls = assertNoRepeatedScrollAfterAddressUpdate(document, restored);
            document.locator("section[aria-label='Reaction chains']").first().scrollIntoViewIfNeeded();
            awaitSemanticProjection(document, 0, other);
            assertThat(restored.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
            restored.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reset view").setExact(true)).click();
            awaitSemanticProjection(document, 1, initial);
            restored.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
            awaitSemanticProjection(document, 1, shared);
            Assertions.assertThat(document.evaluate("window.reactionPageScrolls")).isEqualTo(initialScrolls);
            document.reload();
            // Opening the link itself activates and scrolls to the second variant, even below the fold.
            awaitSemanticProjection(document, 1, shared);
            assertThat(restored).isInViewport();
            document.waitForFunction("""
                    () => {
                        const top = document.querySelectorAll('section[aria-label="Reaction chains"]')[1].getBoundingClientRect().top;
                        return top >= 60 && top < 150;
                    }
                    """);
            assertNoRepeatedScrollAfterAddressUpdate(document, restored);
            assertThat(restored.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sharedInterfaceReferencesFollowTheMessageWhenNumericKeysAreReused() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        openFixtureInterfaceMenu(viewer, "3");
        menuAction(viewer, "Pin");
        awaitPinned("3");
        String link = sharedLink(viewer);
        try (var fresh = context.browser().newContext()) {
            var document = fresh.newPage();
            document.route("**/reaction-graph.json", route -> {
                var response = route.fetch();
                var mapper = JsonMapper.builder().build();
                var payload = mapper.readTree(response.text());
                for (var node : payload.path("graph").path("nodes")) swapInterfaceKey(node.path("identity"));
                for (var edge : payload.path("graph").path("edges")) {
                    swapInterfaceKey(edge.path("source"));
                    swapInterfaceKey(edge.path("target"));
                }
                route.fulfill(new FulfillOptions().setResponse(response).setBody(mapper.writeValueAsString(payload)));
            });
            document.navigate(link);
            document.locator("section[aria-label='Reaction chains']").scrollIntoViewIfNeeded();
            document.waitForFunction("""
                    () => {
                        const cy = document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                        if (!cy) return false;
                        const pins = cy.nodes('[?pinned]');
                        return pins.length === 1 && pins[0].data('identity')?.key === '5'
                            && pins[0].data('label') === 'ShippingDispatchedEvent';
                    }
                    """);
            assertThat(document.getByText("This view cannot be restored on the current graph.",
                    new Page.GetByTextOptions().setExact(true))).hasCount(0);
        }
        assertNothingWentWrongInTheBrowser();
    }

    private void swapInterfaceKey(JsonNode identity) {
        if (!identity.path("nodeType").asString().equals("INTERFACE")) return;
        String key = identity.path("key").asString();
        if (key.equals("3") || key.equals("5")) {
            ((ObjectNode) identity).put("key", key.equals("3") ? "5" : "3");
        }
    }

    @Test
    void sharedReactionSurvivesANewOpaqueIdButFailsAtomicallyWhenItsTargetIsRemoved() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Pin").setExact(true)).click();
        page.waitForFunction("""
                id => document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')
                    ?._cyreg?.cy.getElementById(id).data('pinned') === true
                """, group);
        openReactionMenu(viewer, "4242");
        viewer.getByRole(AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName("Pin").setExact(true)).click();
        page.waitForFunction("""
                () => document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')
                    ?._cyreg?.cy.nodes('[kind="reaction"]').some(node => JSON.parse(node.id())[1] === '4242' && node.data('pinned'))
                """);
        String link = sharedLink(viewer);
        for (boolean remove : new boolean[]{false, true}) {
            try (var fresh = context.browser().newContext()) {
                var document = fresh.newPage();
                document.route("**/reaction-graph.json", route -> {
                    var response = route.fetch();
                    var json = JsonMapper.builder().build();
                    var payload = json.readTree(response.text());
                    var graph = (ObjectNode) payload.path("graph");
                    var nodes = json.createArrayNode();
                    for (var node : graph.path("nodes")) {
                        if (node.path("identity").path("key").asString().equals("4242")) {
                            if (remove) continue;
                            ((ObjectNode) node.path("identity")).put("key", "new-reaction-id");
                        }
                        nodes.add(node);
                    }
                    var edges = json.createArrayNode();
                    for (var edge : graph.path("edges")) {
                        boolean affected = false;
                        for (String endpoint : List.of("source", "target")) {
                            if (edge.path(endpoint).path("key").asString().equals("4242")) {
                                affected = true;
                                ((ObjectNode) edge.path(endpoint)).put("key", "new-reaction-id");
                            }
                        }
                        if (!remove || !affected) edges.add(edge);
                    }
                    graph.set("nodes", nodes);
                    graph.set("edges", edges);
                    route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
                });
                document.navigate(link);
                document.locator("section[aria-label='Reaction chains']").first().scrollIntoViewIfNeeded();
                if (remove) {
                    assertThat(document.getByText("This view cannot be restored on the current graph.",
                            new Page.GetByTextOptions().setExact(true))).isVisible();
                    assertThat(document.locator("article h1")).isVisible();
                    document.waitForFunction("""
                            () => {
                                const cy = document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                                return cy && cy.nodes('[kind="reaction"]').length
                                    && !cy.nodes('[kind="reaction"], [kind="group"]').some(node => node.data('pinned'));
                            }
                            """);
                } else {
                    document.waitForFunction("""
                            () => document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')
                                ?._cyreg?.cy.nodes('[kind="reaction"]').some(node => JSON.parse(node.id())[1] === 'new-reaction-id' && node.data('pinned'))
                            """);
                }
            }
        }
        assertNothingWentWrongInTheBrowser();
    }

    private int assertNoRepeatedScrollAfterAddressUpdate(Page document, Locator viewer) {
        int scrolls = ((Number) document.evaluate("window.reactionPageScrolls")).intValue();
        String previousAddress = document.url();
        document.evaluate("window.reactionTestScrollTo(0, 0)");
        viewer.locator("div[aria-label^='Reaction graph.']")
                .evaluate("element => element._cyreg.cy.panBy({x: 40, y: 30})");
        // Wait for the debounced address publication and its render frames, not an elapsed interval.
        document.waitForURL(address -> !address.equals(previousAddress));
        document.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        Assertions.assertThat(document.evaluate("window.reactionPageScrolls")).isEqualTo(scrolls);
        Assertions.assertThat(((Number) document.evaluate("window.scrollY")).doubleValue()).isZero();
        return scrolls;
    }

    private String sharedLink(Locator viewer) {
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Share link").setExact(true)).click();
        var link = viewer.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Shared view link").setExact(true));
        assertThat(link).isVisible();
        String address = link.inputValue();
        Assertions.assertThat(URI.create(address).getRawFragment()).startsWith("reaction-view=d1.");
        Assertions.assertThat(page.url()).isEqualTo(address);
        return address;
    }

    @Test
    void sharingShowsTemporaryCopyFeedbackAndReplacesTheAddressWithoutResettingEitherViewer() {
        page.addInitScript("""
                Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
                    writeText: async text => { window.copiedReactionLink = text; }
                }});
                """);
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        var first = viewers.first();
        String initial = semanticProjection(first), other = semanticProjection(viewers.nth(1));
        String group = groupOfReaction(first, "4243");
        openGroupMenu(first, group);
        menuAction(first, "Collapse group");
        awaitGroupMembers(group, 0);
        String collapsed = semanticProjection(first);
        Object viewport = presentationOf(first);
        Object historyLength = page.evaluate("window.reactionShareDocument = 'same-document'; history.length");
        first.locator("div[aria-label^='Reaction graph.']").evaluate("el => { window.reactionShareRenderer = el._cyreg.cy; }");

        String link = sharedLink(first);

        var feedback = first.getByText("Link copied", new Locator.GetByTextOptions().setExact(true));
        assertThat(feedback).isVisible();
        assertThat(feedback).hasAttribute("role", "status");
        Assertions.assertThat(page.evaluate("window.copiedReactionLink")).isEqualTo(link);
        Assertions.assertThat(page.evaluate("history.length")).isEqualTo(historyLength);
        Assertions.assertThat(page.evaluate("window.reactionShareDocument")).isEqualTo("same-document");
        Assertions.assertThat(first.locator("div[aria-label^='Reaction graph.']")
                .evaluate("el => el._cyreg.cy === window.reactionShareRenderer")).isEqualTo(true);
        awaitSemanticProjection(page, 0, collapsed);
        awaitSemanticProjection(page, 1, other);
        awaitPresentation(page, 0, viewport, true);
        assertThat(viewers.nth(1).getByText("Link copied")).hasCount(0);
        assertThat(first.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isEnabled();
        assertThat(feedback).hasCount(0);
        first.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, initial);
        awaitAutomaticLink(0, "");
        Assertions.assertThat(page.evaluate("history.length")).isEqualTo(historyLength);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void actionsZoomAndPanUpdateTheAddressAutomaticallyWithoutCopyingOrCreatingUndoSteps() {
        page.addInitScript("""
                window.automaticClipboardWrites = 0;
                Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
                    writeText: async () => { window.automaticClipboardWrites++; }
                }});
                """);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        String initial = semanticProjection(viewer);
        Assertions.assertThat(URI.create(page.url()).getFragment()).isNull();
        Object historyLength = page.evaluate("window.automaticAddressDocument = 'same-document'; history.length");
        openFixtureInterfaceMenu(viewer, "3");
        menuAction(viewer, "Pin");
        awaitPinned("3");
        awaitAutomaticLink(1, null);
        String pinned = page.url();
        viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                el => { el._cyreg.cy.zoom(1.3); el._cyreg.cy.pan({x: -200, y: 80}); }
                """);
        Object viewport = presentationOf(viewer);
        page.waitForURL(address -> !address.equals(pinned));
        String current = page.url();
        Assertions.assertThat(page.evaluate("window.automaticClipboardWrites")).isEqualTo(0);
        Assertions.assertThat(page.evaluate("history.length")).isEqualTo(historyLength);
        Assertions.assertThat(page.evaluate("window.automaticAddressDocument")).isEqualTo("same-document");
        assertThat(viewer.getByText("Link copied")).hasCount(0);
        try (var fresh = context.browser().newContext()) {
            var document = fresh.newPage();
            document.navigate(current);
            document.locator("section[aria-label='Reaction chains']").scrollIntoViewIfNeeded();
            awaitPresentation(document, 0, viewport, false);
            document.waitForFunction("""
                    () => document.querySelector('div[aria-label^="Reaction graph."]')?._cyreg?.cy.nodes('[?pinned]')
                        .some(node => node.data('identity')?.key === '3')
                    """);
        }
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true)).click();
        awaitSemanticProjection(page, 0, initial);
        awaitAutomaticLink(0, null);
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        Assertions.assertThat(page.evaluate("history.length")).isEqualTo(historyLength);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void theMostRecentlyEditedViewerOwnsTheAutomaticLinkWithoutInitialViewersOverwritingIt() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        String firstInitial = semanticProjection(viewers.first());
        openGroupMenu(viewers.first(), groupOfReaction(viewers.first(), "4243"));
        menuAction(viewers.first(), "Collapse group");
        awaitAutomaticLink(1, "");
        String group = groupOfReaction(viewers.nth(1), "5242");
        openGroupMenu(viewers.nth(1), group);
        menuAction(viewers.nth(1), "Collapse group");
        page.waitForFunction("""
                group => {
                    const cy = document.querySelectorAll('div[aria-label^="Reaction graph."]')[1]?._cyreg?.cy;
                    return cy && !cy.destroyed() && cy.getElementById(group).length === 1
                        && cy.getElementById(group).children('[kind="reaction"]').length === 0;
                }
                """, group);
        String secondSelection = semanticProjection(viewers.nth(1));
        awaitAutomaticLink(1, VARIANT);
        String second = (String) page.evaluate("location.href");
        try (var fresh = context.browser().newContext()) {
            var document = fresh.newPage();
            document.navigate(second);
            var restored = document.locator("section[aria-label='Reaction chains']");
            restored.first().scrollIntoViewIfNeeded();
            awaitSemanticProjection(document, 0, firstInitial);
            restored.nth(1).scrollIntoViewIfNeeded();
            awaitSemanticProjection(document, 1, secondSelection);
            Assertions.assertThat(document.url()).isEqualTo(second);
        }
        assertNothingWentWrongInTheBrowser();
    }

    private void awaitAutomaticLink(int steps, String variant) {
        var expected = new HashMap<String, Object>();
        expected.put("steps", steps);
        if (variant != null) expected.put("variant", variant);
        page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate("""
                async expected => {
                    const prefix = '#reaction-view=d1.', hash = location.hash;
                    if (!hash.startsWith(prefix)) return false;
                    try {
                        const binary = atob(hash.slice(prefix.length).replaceAll('-', '+').replaceAll('_', '/'));
                        const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
                        const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
                        const state = JSON.parse(await new Response(stream).text());
                        return state.v === 1 && state.s.length === expected.steps
                            && (!('variant' in expected) || state.r[state.w].targets[0].identity.message[1] === expected.variant);
                    } catch { return false; }
                }
                """, expected)));
        String current = (String) page.evaluate("location.href");
        page.waitForURL(address -> address.equals(current));
    }

    @Test
    void automaticEncodingFailureRemovesTheStaleLinkAndKeepsTheEditedGraphUsable() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        sharedLink(viewer);
        openFixtureInterfaceMenu(viewer, "3");
        page.evaluate("() => { window.CompressionStream = class { constructor() { throw new Error('test compression failure'); } }; }");
        menuAction(viewer, "Pin");
        awaitPinned("3");
        assertThat(viewer.getByText("test compression failure", new Locator.GetByTextOptions().setExact(true))).isVisible();
        page.waitForURL(address -> URI.create(address).getFragment() == null);
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isEnabled();
        assertThat(viewer.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Shared view link").setExact(true))).hasCount(0);
        // A successful source copy must not hide a still-active encoding error.
        page.evaluate("Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {writeText: async text => { window.sourceCopied = text; }}})");
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Show source")).click();
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Copy source")).click();
        page.waitForFunction("() => typeof window.sourceCopied === 'string'");
        var error = viewer.getByText("test compression failure", new Locator.GetByTextOptions().setExact(true));
        assertThat(error).isVisible();
        assertThat(error).hasClass(Pattern.compile(".*errorFeedback.*"));
        assertThat(viewer.getByText("Code copied", new Locator.GetByTextOptions().setExact(true))).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sharingUpdatesTheAddressWhenClipboardAccessIsDeniedWithoutClaimingItWasCopied() {
        page.addInitScript("""
                Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
                    writeText: async () => { throw new Error('denied'); }
                }});
                """);
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        String link = sharedLink(viewer);
        assertThat(viewer.getByText("Link copied")).hasCount(0);
        var feedback = viewer.getByText("Copy the shared view link below.", new Locator.GetByTextOptions().setExact(true));
        assertThat(feedback).isVisible();
        assertThat(feedback).not().hasClass(Pattern.compile(".*errorFeedback.*"));
        assertThat(feedback).hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(5000));
        page.reload();
        showReactionViewer(0);
        Assertions.assertThat(page.url()).isEqualTo(link);
        assertThat(page.getByText("This view cannot be restored on the current graph.")).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void aTruncatedCompressedLinkLeavesTheOrdinaryEntryAndDocumentationUsable() {
        open("/" + COMPONENT_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        String initial = semanticProjection(viewer);
        String link = sharedLink(viewer);
        page.navigate(link.substring(0, link.length() - 8));
        assertThat(page.getByText("This view cannot be restored on the current graph.",
                new Page.GetByTextOptions().setExact(true))).isVisible();
        viewer.scrollIntoViewIfNeeded();
        awaitSemanticProjection(page, 0, initial);
        assertThat(page.locator("article h1")).isVisible();
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void invalidLinksReportOnceAndStaleLinksOnlyInTheirMessageViewer() {
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        var viewers = page.locator("section[aria-label='Reaction chains']");
        String link = sharedLink(viewers.first());
        String stale = (String) page.evaluate("""
                async link => {
                    const url = new URL(link), token = url.hash.split('d1.')[1];
                    const bytes = Uint8Array.from(atob(token.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
                    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
                    const state = JSON.parse(await new Response(stream).text());
                    state.c[1] = 'missing-environment';
                    const compressed = new Blob([JSON.stringify(state)]).stream().pipeThrough(new CompressionStream('deflate-raw'));
                    const result = new Uint8Array(await new Response(compressed).arrayBuffer());
                    url.hash = '#reaction-view=d1.' + btoa(Array.from(result, byte => String.fromCharCode(byte)).join(''))
                        .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
                    return url.href;
                }
                """, link);
        page.navigate(stale);
        showReactionViewer(0);
        showReactionViewer(1);
        assertThat(viewers.first().getByText("This view cannot be restored on the current graph.")).isVisible();
        assertThat(viewers.nth(1).getByText("This view cannot be restored on the current graph.")).hasCount(0);
        var error = viewers.first().getByText("This view cannot be restored on the current graph.");
        Assertions.assertThat(error.evaluate("""
                el => {
                    const style = getComputedStyle(el), probe = document.createElement('span');
                    probe.style.color = 'var(--ifm-color-danger)';
                    el.append(probe);
                    const danger = getComputedStyle(probe).color;
                    probe.remove();
                    return style.position === 'absolute' && style.color === danger && style.borderTopColor === danger;
                }
                """)).isEqualTo(true);

        String trailing = (String) page.evaluate("""
                link => {
                    const url = new URL(link), token = url.hash.split('d1.')[1];
                    const bytes = atob(token.replaceAll('-', '+').replaceAll('_', '/')) + 'trailing';
                    url.hash = '#reaction-view=d1.' + btoa(bytes).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
                    return url.href;
                }
                """, link);
        for (String invalid : List.of(trailing, link.substring(0, link.length() - 8))) {
            page.navigate(invalid);
            showReactionViewer(0);
            showReactionViewer(1);
            assertThat(page.getByText("This view cannot be restored on the current graph.",
                    new Page.GetByTextOptions().setExact(true))).hasCount(1);
            assertThat(page.locator("article h1")).isVisible();
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void anEmptySharedSelectionKeepsTheLiveTableEmptyAndCanBeReset() {
        installVisibilityFixture(false);
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Focus");
        awaitGraphKeys(List.of("B", "r2"), false);
        openFixtureInterfaceMenu(viewer, "A");
        menuAction(viewer, "Hide connected subgraph");
        assertThat(viewer.locator("canvas")).hasCount(0);
        String link = sharedLink(viewer);
        page.navigate(link);
        page.reload();
        viewer.scrollIntoViewIfNeeded();
        var table = page.locator("table[data-reaction-table='live']");
        assertThat(table).isVisible();
        assertThat(table.locator("tbody tr")).hasCount(0);
        assertThat(page.locator(".jeapReactionTableFallback")).isHidden();
        assertThat(viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Back").setExact(true))).isDisabled();
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reset view").setExact(true)).click();
        assertThat(table.locator("tbody tr")).hasCount(4);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void sharedLinksRetainTheEnvironmentAndRejectReplayInAnotherEnvironment() {
        String prefix = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        open(prefix + "/" + COMPONENT_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        var viewer = page.locator("section[aria-label='Reaction chains']");
        String initial = semanticProjection(viewer);
        String link = sharedLink(viewer);
        Assertions.assertThat(URI.create(link).getPath())
                .isEqualTo(prefix + "/" + COMPONENT_REACTIONS_ROUTE + "/");
        page.navigate(link);
        showReactionViewer(0);
        awaitSemanticProjection(page, 0, initial);
        String fragment = URI.create(link).getRawFragment();
        open("/" + COMPONENT_REACTIONS_ROUTE + "/#" + fragment);
        assertThat(page.getByText("This view cannot be restored on the current graph.",
                new Page.GetByTextOptions().setExact(true))).isVisible();
        assertThat(page.locator("article h1")).isVisible();
        open("/" + COMPONENT_REACTIONS_ROUTE + "/#reaction-view=unsupported");
        assertThat(page.getByText("This view cannot be restored on the current graph.",
                new Page.GetByTextOptions().setExact(true))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    private static final String SEMANTIC_PROJECTION = """
            cy => JSON.stringify(cy.elements().map(element => element.data()).sort((a, b) => a.id.localeCompare(b.id)))
            """;

    private Object presentationOf(Locator viewer) {
        return viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                el => {
                    const cy = el._cyreg.cy;
                    return {zoom: cy.zoom(), pan: cy.pan(), width: cy.width(), height: cy.height(),
                        positions: cy.nodes(':childless').map(node => [node.id(), node.position()])};
                }
                """);
    }

    private void awaitPresentation(Page document, int index, Object expected, boolean positions) {
        document.waitForFunction("""
                args => {
                    const cy = document.querySelectorAll('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')[args.index]?._cyreg?.cy;
                    const before = args.expected, close = (a, b) => Math.abs(a - b) < .001;
                    if (!cy || !close(cy.zoom(), before.zoom)) return false;
                    if (args.positions) {
                        return close(cy.pan('x'), before.pan.x) && close(cy.pan('y'), before.pan.y)
                            && before.positions.every(([id, point]) => {
                                const node = cy.getElementById(id);
                                return node.length && close(node.position('x'), point.x) && close(node.position('y'), point.y);
                            });
                    }
                    const centre = {x: (cy.width() / 2 - cy.pan('x')) / cy.zoom(),
                        y: (cy.height() / 2 - cy.pan('y')) / cy.zoom()};
                    return close(centre.x, (before.width / 2 - before.pan.x) / before.zoom)
                        && close(centre.y, (before.height / 2 - before.pan.y) / before.zoom);
                }
                """, Map.of("index", index, "expected", expected, "positions", positions));
    }

    private String semanticProjection(Locator viewer) {
        return (String) viewer.locator("div[aria-label^='Reaction graph.']")
                .evaluate("element => (" + SEMANTIC_PROJECTION + ")(element._cyreg.cy)");
    }

    private void awaitSemanticProjection(Page document, int index, String expected) {
        try {
            document.waitForFunction("""
                args => {
                    const cy = document.querySelectorAll('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')[args.index]?._cyreg?.cy;
                    return cy && (""" + SEMANTIC_PROJECTION + ")(cy) === args.expected; }",
                Map.of("index", index, "expected", expected));
        } catch (TimeoutError error) {
            Assertions.assertThat(semanticProjection(document.locator("section[aria-label='Reaction chains']").nth(index)))
                    .as("Viewer %s semantic projection", index).isEqualTo(expected);
            throw error;
        }
    }

    private void assertGraphFitted(Locator viewer) {
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Assertions.assertThat(canvas.evaluate("""
                element => {
                    const cy = element._cyreg.cy, box = cy.elements().renderedBoundingBox();
                    return cy.zoom() <= 1 && box.x1 >= 0 && box.y1 >= 0
                        && box.x2 <= cy.width() && box.y2 <= cy.height();
                }
                """)).isEqualTo(true);
    }

    private void openReactionMenu(Locator viewer, String reaction) {
        viewer.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var id = (String) canvas.evaluate("""
                (element, reaction) => element._cyreg.cy.nodes('[kind="reaction"]')
                    .filter(node => JSON.parse(node.id())[1] === reaction).first().id()
                """, reaction);
        clickGraphNode(viewer, id, true);
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
    }

    private String groupOfReaction(Locator viewer, String reaction) {
        return (String) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                (element, reaction) => element._cyreg.cy.nodes('[kind="reaction"]')
                    .filter(node => JSON.parse(node.id())[1] === reaction).first().parent().id()
                """, reaction);
    }

    /**
     * Right-clicks the group itself and asserts that it was the group the renderer received the click on.
     * <p>
     * The point is chosen by the renderer's own hit test, not derived from the group's bounds: a band a few pixels
     * below the group's top edge was missed now and then, and the click then fell on the empty canvas - which
     * Playwright cannot tell from a hit, since every point of the canvas is the same element to it.
     */
    private void openGroupMenu(com.microsoft.playwright.Locator viewer, String group) {
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        String clicked = null;
        java.util.Map<?, ?> position = null;
        for (int attempt = 0; attempt < 2 && !group.equals(clicked); attempt++) {
            if (clicked != null && !clicked.isEmpty()) {
                page.keyboard().press("Escape");
            }
            // Finish scrolling before resolving the renderer's click coordinates.
            canvas.scrollIntoViewIfNeeded();
            position = (java.util.Map<?, ?>) canvas.evaluate("""
                    async (element, id) => {
                        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                        const cy = element._cyreg.cy, group = cy.getElementById(id), box = group.boundingBox({includeLabels: false});
                        const answersFor = (x, y) => cy.renderer().findNearestElement(x, y, true, false)?.same(group);
                        for (let y = box.y1 + 2; y < box.y2; y += 2) {
                            for (const x of [(box.x1 + box.x2) / 2, box.x1 + 4, box.x2 - 4]) {
                                if (answersFor(x, y)) {
                                    delete element.dataset.clickedNode;
                                    cy.one('cxttap', 'node', event => { element.dataset.clickedNode = event.target.id(); });
                                    return {x: x * cy.zoom() + cy.pan().x, y: y * cy.zoom() + cy.pan().y};
                                }
                            }
                        }
                        return null;
                    }
                    """, group);
            org.assertj.core.api.Assertions.assertThat(position).as("A point of the group %s that is not covered", group)
                    .isNotNull();
            canvas.click(new com.microsoft.playwright.Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT)
                    .setPosition(((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
            try {
                page.waitForCondition(() -> canvas.getAttribute("data-clicked-node") != null,
                        new com.microsoft.playwright.Page.WaitForConditionOptions().setTimeout(2_000));
            } catch (com.microsoft.playwright.TimeoutError missed) {
                // The click reached no node at all; the next attempt resolves the point again.
            }
            clicked = canvas.getAttribute("data-clicked-node");
            if (clicked == null) {
                clicked = "";
            }
        }
        org.assertj.core.api.Assertions.assertThat(clicked).as("The node right-clicked at %s", position).isEqualTo(group);
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
    }

    private void awaitGroupMembers(String group, int count) {
        page.waitForFunction("""
                args => {
                    const cy = document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && cy.getElementById(args.group).children('[kind="reaction"]').length === args.count;
                }
                """, Map.of("group", group, "count", count));
    }

    @Test
    void systemFixture_usesTheSameReactionsAndMediansAsTheDocumentationTable() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        assertThat(page.locator("table[data-reaction-table='live']")).isVisible();
        Object comparison = page.evaluate("""
                async () => {
                    const payload = await (await fetch('/reaction-graph.json')).json();
                    const owners = new Map(payload.ownership.map(owner => [JSON.stringify(owner.reaction), owner]));
                    const published = payload.graph.nodes.filter(node => node.reaction
                        && owners.get(JSON.stringify(node.identity))?.system === 'shipping')
                        .map(node => `${node.reaction.component}:${node.reaction.median}`).sort();
                    const table = [...document.querySelectorAll('table[data-reaction-table="live"] tbody tr')]
                        .map(row => `${row.cells[1].textContent.trim()}:${row.cells[3].textContent.trim()}`).sort();
                    const fallback = [...document.querySelectorAll('.jeapReactionTableFallback table tbody tr')]
                        .map(row => `${row.cells[1].textContent.trim()}:${row.cells[3].textContent.trim()}`).sort();
                    return {published, table, fallback};
                }
                """);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) comparison;
        Assertions.assertThat(result)
                .containsEntry("published", result.get("table"))
                .containsEntry("published", result.get("fallback"));
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void invalidGraph_leavesTheDocumentationUsable() {
        page.route("**/reaction-graph.json", route -> route.fulfill(new FulfillOptions()
                .setContentType("application/json").setBody("{\"available\":false}")));
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        assertThat(page.getByText("Reaction graph currently unavailable", new Page.GetByTextOptions().setExact(true))).isVisible();
        assertThat(page.locator("article table").first()).isVisible();
        assertThat(page.locator(".jeapReactionTableFallback")).isVisible();
        assertThat(page.locator("table[data-reaction-table='live']")).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void withoutJavaScriptTheStaticReactionTableRemainsReadable() {
        try (var noScript = context.browser().newContext(new Browser.NewContextOptions()
                .setJavaScriptEnabled(false))) {
            var document = noScript.newPage();
            document.navigate(url("/" + SYSTEM_REACTIONS_ROUTE + "/"));
            assertThat(document.locator(".jeapReactionTableFallback table")).isVisible();
            assertThat(document.locator(".jeapReactionTableFallback table")).containsText(REACTING_COMPONENT);
            assertThat(document.locator("table[data-reaction-table='live']")).hasCount(0);
        }
    }

    @Test
    void anotherEnvironment_loadsItsOwnGraph() {
        open(environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix()
                + "/" + COMPONENT_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        assertNothingWentWrongInTheBrowser();
    }
}
