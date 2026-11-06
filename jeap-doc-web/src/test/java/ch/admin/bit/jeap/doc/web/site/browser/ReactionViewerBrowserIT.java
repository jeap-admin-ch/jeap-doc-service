package ch.admin.bit.jeap.doc.web.site.browser;

import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.Route.FulfillOptions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The React viewer consumes the real published JSON under the service's CSP. */
class ReactionViewerBrowserIT extends SiteBrowserTestBase {
    @Test
    void returningToAPageFetchesTheNewSnapshotWithoutReloadingTheDocument() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        page.route("**/reaction-graph.json", route -> {
            var response = route.fetch();
            int visit = requests.incrementAndGet();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var payload = json.readTree(response.text());
            if (visit > 1) {
                for (var node : payload.path("graph").path("nodes")) {
                    if (node.path("reaction").isObject()) {
                        ((tools.jackson.databind.node.ObjectNode) node.path("reaction")).put("median", 9876);
                    }
                }
            }
            route.fulfill(new FulfillOptions().setResponse(response).setBody(json.writeValueAsString(payload)));
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        showReactionViewer(0);
        showReactionViewer(1);
        org.assertj.core.api.Assertions.assertThat(requests.get()).isEqualTo(1);
        page.evaluate("window.reactionVisitMarker = 'same-document'");
        page.locator("a.navbar__brand").click();
        page.waitForURL(url -> !url.contains(MESSAGE_REACTIONS_ROUTE));
        // Docusaurus changes the URL before it replaces the page while loading the next route.
        assertThat(page.locator(".jeapReactionView")).hasCount(0);
        page.goBack();
        showReactionViewer(0);
        showReactionViewer(1);
        assertThat(page.locator("table[data-reaction-table='live']").first()).containsText("9876");
        org.assertj.core.api.Assertions.assertThat(requests.get()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(page.evaluate("window.reactionVisitMarker")).isEqualTo("same-document");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void offscreenVariants_waitForTheViewportAndShareOneSnapshotRequest() {
        page.setViewportSize(1280, 500);
        var graphRequests = new java.util.concurrent.atomic.AtomicInteger();
        page.onRequest(request -> {
            if (request.url().endsWith("/reaction-graph.json")) graphRequests.incrementAndGet();
        });
        open("/" + MESSAGE_REACTIONS_ROUTE + "/");
        var entries = page.locator(".jeapReactionView");
        var viewers = page.locator("section[aria-label='Reaction chains']");
        assertThat(viewers).hasCount(2);

        showReactionViewer(0);

        org.assertj.core.api.Assertions.assertThat(viewers.nth(1).evaluate(
                "element => element.getBoundingClientRect().top > innerHeight + 300")).isEqualTo(true);
        assertThat(viewers.nth(1).locator("canvas")).hasCount(0);
        assertThat(entries.nth(1).locator("table[data-reaction-table='live']")).hasCount(0);
        assertThat(entries.nth(1).locator(".jeapReactionTableFallback table")).isVisible();

        showReactionViewer(1);

        assertThat(entries.nth(1).locator("table[data-reaction-table='live']")).isVisible();
        assertThat(entries.nth(1).locator(".jeapReactionTableFallback")).isHidden();
        assertThat(viewers.first().locator("canvas").first()).isVisible();
        org.assertj.core.api.Assertions.assertThat(graphRequests.get()).isEqualTo(1);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void allEntryPages_renderThePublishedSnapshot() {
        // The busy component previously had no separate component snapshot. Its page now comes from the full graph.
        for (String route : java.util.List.of(SYSTEM_REACTIONS_ROUTE, COMPONENT_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE,
                COMPONENT_REACTIONS_ROUTE.replace(REACTING_COMPONENT, BUSY_COMPONENT))) {
            open("/" + route + "/");
            var viewer = page.getByRole(AriaRole.REGION, new com.microsoft.playwright.Page.GetByRoleOptions().setName("Reaction chains")).first();
            assertThat(viewer).isVisible();
            showReactionViewer(0);
            assertGraphFitted(viewer);
            viewer.getByRole(AriaRole.BUTTON,
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
            viewer.getByRole(AriaRole.BUTTON,
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Reset view")).click();
            assertGraphFitted(viewer);
            assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).isVisible();
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
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
            var canvas = viewers.nth(index).locator("div[aria-label^='Reaction graph.']");
            // Cytoscape owns the canvas; use its rendered node position for a real pointer selection.
            var position = (java.util.Map<?, ?>) canvas.evaluate("""
                    element => element._cyreg.cy.nodes().filter(node => node.data('kind') === 'reaction'
                        && node.data('label').includes('<script>alert(1)</script>')).first().renderedPosition()
                    """);
            canvas.hover(new com.microsoft.playwright.Locator.HoverOptions().setPosition(
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
        var buttons = viewer.getByRole(AriaRole.BUTTON);
        assertThat(buttons).hasCount(7);
        for (var button : buttons.all()) {
            assertThat(button).hasText("");
            assertThat(button.locator("svg")).hasCount(1);
            org.assertj.core.api.Assertions.assertThat(button.getAttribute("title"))
                    .isEqualTo(button.getAttribute("aria-label"));
        }
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        assertThat(viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Expand group"))).isDisabled();
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).isVisible();
        openGroupMenu(viewer, group);
        assertThat(viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group"))).isDisabled();
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Expand group")).click();
        awaitGroupMembers(group, 3);
        var zoom = viewer.getByLabel("Zoom", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true));
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        String fitted = zoom.textContent();
        viewer.getByLabel("Zoom in", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true)).click();
        assertThat(zoom).not().hasText(fitted);
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fullscreen").setExact(true)).click();
        assertThat(viewer.getByRole(AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Exit fullscreen"))).isVisible();
        assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).isVisible();
        page.keyboard().press("Escape");
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void initialViewAndResetFitTheWholeGraphWithoutEnlargingSmallGraphs() {
        for (String route : java.util.List.of(SYSTEM_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE)) {
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
            org.assertj.core.api.Assertions.assertThat(canvas.evaluate(fits)).isEqualTo(true);
            viewer.getByLabel("Zoom in", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true)).click();
            viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Reset view")).click();
            org.assertj.core.api.Assertions.assertThat(canvas.evaluate(fits)).isEqualTo(true);
        }
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void diagramLabels_followTheOriginalConventionsAndFitInsideGroups() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        var viewer = page.locator("section[aria-label='Reaction chains']");
        showReactionViewer(0);
        @SuppressWarnings("unchecked")
        var labels = (java.util.Map<String, Object>) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
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
        org.assertj.core.api.Assertions.assertThat(labels)
                .containsEntry("variant", "OrdersPayment\nAccepted\n[express]")
                .containsEntry("reaction", "12")
                .containsEntry("foreignBorder", "rgb(74,144,217)")
                .doesNotContainEntry("ownBorder", labels.get("foreignBorder"));
        org.assertj.core.api.Assertions.assertThat((Boolean) labels.get("titlesInside")).isTrue();
        org.assertj.core.api.Assertions.assertThat((java.util.List<?>) labels.get("titles"))
                .isNotEmpty().allSatisfy(title -> org.assertj.core.api.Assertions.assertThat(title).hasToString("shipping-dispatch"));
        assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))
                .locator("svg")).hasCount(6);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void triggerArrows_followIndividualReactionsAndCollapseToOneGroupArrow() {
        for (String route : java.util.List.of(SYSTEM_REACTIONS_ROUTE, MESSAGE_REACTIONS_ROUTE)) {
            open("/" + route + "/");
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            showReactionViewer(0);
            var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
            var targets = (java.util.List<?>) canvas.evaluate("""
                    element => {
                        const cy = element._cyreg.cy;
                        const reaction = cy.nodes('[kind="reaction"]').filter(node => JSON.parse(node.id())[1] === '4243').first();
                        return reaction.parent().children().incomers('edge').map(edge => JSON.parse(edge.target().id())[1]).sort();
                    }
                    """);
            org.assertj.core.api.Assertions.assertThat(targets).isEqualTo(java.util.List.of("4243", "4244", "4245"));
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
            org.assertj.core.api.Assertions.assertThat(endpointsOnReactionBorders).isEqualTo(true);
            var legend = viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true));
            assertThat(legend).not().containsText("Interface");
            assertThat(legend).containsText("REST");
            String group = groupOfReaction(viewer, "4243");
            openGroupMenu(viewer, group);
            String zoomBefore = viewer.getByLabel("Zoom", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true)).textContent();
            viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group")).click();
            awaitGroupMembers(group, 0);
            assertThat(viewer.getByLabel("Zoom", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).hasText(zoomBefore);
            org.assertj.core.api.Assertions.assertThat(canvas.evaluate("""
                    element => element._cyreg.cy.nodes('[kind="reaction"]').some(node => JSON.parse(node.id())[1] === '4242')
                    """)).isEqualTo(true);
            var arrows = (java.util.List<?>) canvas.evaluate("""
                    element => element._cyreg.cy.nodes('[kind="group"]')
                        .filter(node => node.data('label') === 'shipping / shipping-label-printer'
                            && node.children('[kind="marker"]').some(marker => marker.data('label') === '3'))
                        .incomers('edge').map(edge => edge.data('tooltip'))
                    """);
            org.assertj.core.api.Assertions.assertThat(arrows).isEqualTo(java.util.List.of(
                    "Sum of individual medians – approximation: 404\n4243: 400\n4244: 4\n4245: 0"));
            assertThat(legend.locator("span")).hasText(route.equals(MESSAGE_REACTIONS_ROUTE)
                    ? new String[]{"Message", "REST", "Reaction", "Group", "2Hidden reactions"}
                    : new String[]{"Message", "REST", "Reaction", "Group", "2Hidden reactions", "Other system"});
            assertNothingWentWrongInTheBrowser();
        }
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
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Go to component documentation"));
        assertThat(documentation).isFocused();
        assertThat(documentation).hasAttribute("href", "/" + COMPONENT_REACTIONS_ROUTE.replace(REACTING_COMPONENT, BUSY_COMPONENT) + "/");
        page.keyboard().press("ArrowDown");
        assertThat(first.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group"))).isFocused();
        page.keyboard().press("Enter");
        awaitGroupMembers(group, 0);
        page.keyboard().press("Shift+F10");
        assertThat(documentation).isFocused();
        page.keyboard().press("ArrowDown");
        assertThat(first.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Expand group"))).isFocused();
        page.keyboard().press("Enter");
        awaitGroupMembers(group, 3);
        org.assertj.core.api.Assertions.assertThat(secondCanvas.evaluate("element => element._cyreg.cy.elements().map(node => node.id())"))
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
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Filter the rows of this table"));
        filter.fill(BUSY_COMPONENT);
        assertThat(table.locator("tbody tr")).hasCount(3);
        table.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Sort by Median per day")).click();
        table.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Sort by Median per day")).click();
        assertThat(table.locator("tbody tr td:last-child")).hasText(new String[]{"400", "4", "0"});
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(table.locator("tbody tr")).hasCount(0);
        assertThat(filter).hasValue(BUSY_COMPONENT);
        assertThat(otherTable.locator("tbody")).hasText(otherRows);
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Expand group")).click();
        awaitGroupMembers(group, 3);
        assertThat(table.locator("tbody tr td:last-child")).hasText(new String[]{"400", "4", "0"});
        filter.press("Escape");
        assertThat(table.locator("tbody tr")).hasCount(initialRows);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void groupMenu_navigatesToAvailableComponentPagesInTheSameEnvironment() {
        String otherEnvironment = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        for (String prefix : java.util.List.of("", otherEnvironment)) {
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
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Go to component documentation"));
            assertThat(link).hasAttribute("href", prefix + "/" + COMPONENT_REACTIONS_ROUTE + "/");
            assertThat(link).isFocused();
            page.keyboard().press("ArrowDown");
            assertThat(viewer.getByRole(AriaRole.MENUITEM,
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group"))).isFocused();
            page.keyboard().press("Home");
            assertThat(link).isFocused();
            page.keyboard().press("Enter");
            page.waitForURL("**/" + COMPONENT_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            assertThat(page.locator("article table").first()).isVisible();
            assertThat(page.locator("article [data-plantuml-diagram]")).hasCount(0);
            var componentViewer = page.locator("section[aria-label='Reaction chains']").first();
            openGroupMenu(componentViewer, groupOfReaction(componentViewer, "4242"));
            assertThat(page.getByRole(AriaRole.MENU).locator("a")).hasCount(0);
            assertNothingWentWrongInTheBrowser();
        }
    }

    @Test
    void interfaceMenus_linkEventsAndRestInTheCurrentEnvironmentWithoutNodeTooltips() {
        // Both HTTP observations and their navigation metadata come from the generated V2 fixture.
        String otherEnvironment = environments().stream().filter(environment -> !environment.main()).findFirst().orElseThrow().routePrefix();
        String restRoute = COMPONENT_REACTIONS_ROUTE.replace("runtime-view/component-reactions", "building-block-view/rest-api");
        for (String prefix : java.util.List.of("", otherEnvironment)) {
            open(prefix + "/" + SYSTEM_REACTIONS_ROUTE + "/");
            var viewer = page.locator("section[aria-label='Reaction chains']").first();
            showReactionViewer(0);
            openInterfaceMenu(viewer, UNDOCUMENTED_MESSAGE);
            assertThat(viewer.getByRole(AriaRole.MENUITEM)).isDisabled();
            page.keyboard().press("Escape");
            openInterfaceMenu(viewer, "OrdersPaymentAcceptedEvent");
            var eventLink = viewer.getByRole(AriaRole.MENUITEM,
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Go to event documentation"));
            assertThat(eventLink).hasAttribute("href", prefix + "/" + MESSAGE_REACTIONS_ROUTE + "/");
            eventLink.click();
            page.waitForURL("**/" + MESSAGE_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            org.assertj.core.api.Assertions.assertThat(page.locator("section[aria-label='Reaction chains']").first()
                    .locator("div[aria-label^='Reaction graph.']").evaluate("""
                    element => {
                        const cy = element._cyreg.cy;
                        return cy.nodes('[kind="message"]').length === 1
                            && cy.edges().every(edge => edge.source().data('pinned') || edge.target().data('pinned'));
                    }
                    """)).isEqualTo(true);

            open(prefix + "/" + SYSTEM_REACTIONS_ROUTE + "/");
            showReactionViewer(0);
            org.assertj.core.api.Assertions.assertThat(viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                    element => {
                        const rest = element._cyreg.cy.nodes('[kind="http"]').first();
                        return rest.visible() && rest.width() > 0 && rest.width() <= 200
                            && rest.height() >= 60 && rest.height() < 120;
                    }
                    """)).isEqualTo(true);
            openInterfaceMenu(viewer, "shipping-dispatch\nGET /shipments");
            var restLink = viewer.getByRole(AriaRole.MENUITEM,
                    new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Go to REST documentation"));
            assertThat(restLink).hasAttribute("href", prefix + "/" + restRoute + "/");
            page.keyboard().press("Enter");
            page.waitForURL("**/" + restRoute + "/");
            assertThat(page.locator("article")).containsText("/shipments");
            assertNothingWentWrongInTheBrowser();
        }
    }

    private void openInterfaceMenu(com.microsoft.playwright.Locator viewer, String label) {
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (java.util.Map<?, ?>) canvas.evaluate("""
                (element, label) => element._cyreg.cy.nodes().filter(node => node.data('label') === label).first().renderedPosition()
                """, label);
        double x = ((Number) position.get("x")).doubleValue(), y = ((Number) position.get("y")).doubleValue();
        canvas.hover(new com.microsoft.playwright.Locator.HoverOptions().setPosition(x, y));
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).hover();
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        canvas.click(new com.microsoft.playwright.Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT).setPosition(x, y));
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).hasCount(0);
        assertThat(viewer.locator(":scope > p")).hasCount(0);
    }

    @Test
    void edgeDetailsAppearOnHoverAndDisappearWhenLeavingTheGraph() {
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        var viewer = page.locator("section[aria-label='Reaction chains']").first();
        showReactionViewer(0);
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (java.util.Map<?, ?>) canvas.evaluate("""
                element => element._cyreg.cy.edges().filter(edge => JSON.parse(edge.source().id())[1] === '4242'
                    && edge.target().data('kind') === 'message').first().renderedMidpoint()
                """);
        canvas.hover(new com.microsoft.playwright.Locator.HoverOptions().setPosition(
                ((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
        assertThat(viewer.getByRole(AriaRole.TOOLTIP)).containsText("Median per day: 12");
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).hover();
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
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Object before = canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan(), ids: el._cyreg.cy.elements().map(n => n.id()).sort()})");
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Show source")).click();
        var source = viewer.getByRole(AriaRole.TEXTBOX, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Graph source (JSON)"));
        assertThat(source).isVisible();
        assertThat(viewers.nth(1).getByRole(AriaRole.TEXTBOX)).hasCount(0);
        showReactionViewer(1);
        org.assertj.core.api.Assertions.assertThat(source.inputValue()).contains("<script>alert(1)</script>");
        assertThat(viewer.locator("script")).hasCount(0);
        Object sourceIds = source.evaluate("el => JSON.parse(el.value).elements.map(n => n.data.id).sort()");
        org.assertj.core.api.Assertions.assertThat(sourceIds).isEqualTo(((java.util.Map<?, ?>) before).get("ids"));
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Copy source")).click();
        assertThat(viewer.locator("section[aria-label='Graph source'] [role='status']")).hasText("Copied.");
        org.assertj.core.api.Assertions.assertThat(page.evaluate("window.copiedGraphSource")).isEqualTo(source.inputValue());
        page.evaluate("() => { navigator.clipboard.writeText = async () => { throw new Error('denied'); }; }");
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Copy source")).click();
        assertThat(viewer.locator("section[aria-label='Graph source'] [role='status']")).containsText("Copy the selected text");
        org.assertj.core.api.Assertions.assertThat(source.evaluate("el => el.selectionStart === 0 && el.selectionEnd === el.value.length")).isEqualTo(true);
        source.press("Escape");
        assertThat(source).hasCount(0);
        assertThat(canvas).isVisible();
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan(), ids: el._cyreg.cy.elements().map(n => n.id()).sort()})"))
                .isEqualTo(before);
        assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).isVisible();
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
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Show minimap")).click();
        var minimap = viewer.getByRole(AriaRole.GROUP, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Minimap").setExact(true));
        assertThat(minimap).isVisible();
        assertThat(viewers.nth(1).getByRole(AriaRole.GROUP, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Minimap").setExact(true))).hasCount(0);
        org.assertj.core.api.Assertions.assertThat(minimap.locator("img").evaluate("img => img.complete && img.naturalWidth > 0")).isEqualTo(true);
        var frame = minimap.locator("[data-minimap-viewport]");
        String originalFrame = frame.getAttribute("style");
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Zoom in")).click();
        assertThat(frame).not().hasAttribute("style", originalFrame);
        var map = minimap.locator("[tabindex='0']");
        var box = map.boundingBox();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        Object zoom = canvas.evaluate("el => el._cyreg.cy.zoom()");
        map.click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(box.width * 0.75, box.height * 0.75));
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("""
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
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.pan()")).isNotEqualTo(clickedPan);
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.zoom()")).isEqualTo(zoom);
        Object draggedPan = canvas.evaluate("el => el._cyreg.cy.pan()");
        map.press("ArrowRight");
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("el => el._cyreg.cy.pan()")).isNotEqualTo(draggedPan);
        String originalImage = minimap.locator("img").getAttribute("src");
        String group = groupOfReaction(viewer, "4243");
        openGroupMenu(viewer, group);
        viewer.getByRole(AriaRole.MENUITEM, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Collapse group")).click();
        awaitGroupMembers(group, 0);
        assertThat(minimap.locator("img")).not().hasAttribute("src", originalImage);
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fullscreen").setExact(true)).click();
        assertThat(minimap).isVisible();
        assertThat(viewer.getByLabel("Legend", new com.microsoft.playwright.Locator.GetByLabelOptions().setExact(true))).isVisible();
        org.assertj.core.api.Assertions.assertThat(otherCanvas.evaluate("el => ({zoom: el._cyreg.cy.zoom(), pan: el._cyreg.cy.pan()})")).isEqualTo(otherView);
        assertNothingWentWrongInTheBrowser();
    }

    private void assertGraphFitted(com.microsoft.playwright.Locator viewer) {
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        org.assertj.core.api.Assertions.assertThat(canvas.evaluate("""
                element => {
                    const cy = element._cyreg.cy, box = cy.elements().renderedBoundingBox();
                    return cy.zoom() <= 1 && box.x1 >= 0 && box.y1 >= 0
                        && box.x2 <= cy.width() && box.y2 <= cy.height();
                }
                """)).isEqualTo(true);
    }

    private void openReactionMenu(com.microsoft.playwright.Locator viewer, String reaction) {
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (java.util.Map<?, ?>) canvas.evaluate("""
                (element, reaction) => element._cyreg.cy.nodes('[kind="reaction"]')
                    .filter(node => JSON.parse(node.id())[1] === reaction).first().renderedPosition()
                """, reaction);
        canvas.click(new com.microsoft.playwright.Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT)
                .setPosition(((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
    }

    private String groupOfReaction(com.microsoft.playwright.Locator viewer, String reaction) {
        return (String) viewer.locator("div[aria-label^='Reaction graph.']").evaluate("""
                (element, reaction) => element._cyreg.cy.nodes('[kind="reaction"]')
                    .filter(node => JSON.parse(node.id())[1] === reaction).first().parent().id()
                """, reaction);
    }

    private void openGroupMenu(com.microsoft.playwright.Locator viewer, String group) {
        viewer.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Fit all")).click();
        var canvas = viewer.locator("div[aria-label^='Reaction graph.']");
        var position = (java.util.Map<?, ?>) canvas.evaluate("""
                (element, id) => {
                    const cy = element._cyreg.cy;
                    const bounds = cy.getElementById(id).renderedBoundingBox({includeLabels: false});
                    return {x: (bounds.x1 + bounds.x2) / 2, y: bounds.y1 + 8 * cy.zoom()};
                }
                """, group);
        canvas.click(new com.microsoft.playwright.Locator.ClickOptions().setButton(com.microsoft.playwright.options.MouseButton.RIGHT)
                .setPosition(((Number) position.get("x")).doubleValue(), ((Number) position.get("y")).doubleValue()));
        assertThat(viewer.getByRole(AriaRole.MENU)).isVisible();
    }

    private void awaitGroupMembers(String group, int count) {
        page.waitForFunction("""
                args => {
                    const cy = document.querySelector('section[aria-label="Reaction chains"] div[aria-label^="Reaction graph."]')?._cyreg?.cy;
                    return cy && cy.getElementById(args.group).children('[kind="reaction"]').length === args.count;
                }
                """, java.util.Map.of("group", group, "count", count));
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
        var result = (java.util.Map<String, Object>) comparison;
        org.assertj.core.api.Assertions.assertThat(result)
                .containsEntry("published", result.get("table"))
                .containsEntry("published", result.get("fallback"));
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void invalidGraph_leavesTheDocumentationUsable() {
        page.route("**/reaction-graph.json", route -> route.fulfill(new FulfillOptions()
                .setContentType("application/json").setBody("{\"available\":false}")));
        open("/" + SYSTEM_REACTIONS_ROUTE + "/");
        assertThat(page.getByText("Reaction graph currently unavailable", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true))).isVisible();
        assertThat(page.locator("article table").first()).isVisible();
        assertThat(page.locator(".jeapReactionTableFallback")).isVisible();
        assertThat(page.locator("table[data-reaction-table='live']")).hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    @Test
    void withoutJavaScriptTheStaticReactionTableRemainsReadable() {
        try (var noScript = context.browser().newContext(new com.microsoft.playwright.Browser.NewContextOptions()
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
