package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.security.test.client.configuration.JeapOAuth2IntegrationTestClientConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The governance excerpt, all the way to what the reader sees.</b>
 * <p>
 * The architecture repository answers over HTTP and the import stores its landscape; the governance data is
 * stored as the governance import stores it; both sites are really generated with Docusaurus and published; and
 * Chrome opens the architecture pages, which read the excerpt from the running service and link into the
 * governance site that was generated beside them. Nothing between the stored resources and the pixels is
 * stubbed.
 * <p>
 * Ordered, because the sites are built once - a build is most of a minute - and the case that changes the
 * governance data comes after the ones that read it. The sites, their environments and the governance data are
 * this class's own; what it stores of the governance data it removes again.
 */
@Import(JeapOAuth2IntegrationTestClientConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernanceExcerptBrowserIT extends BrowserTestBase {

    /** The architecture site, below {@code /site/}, with two environment trees of its own. */
    private static final String SITE = "govarch";
    private static final String MAIN = "glab";
    private static final String OTHER = "gref";
    private static final String ROOT = "/site/" + SITE;

    /** The governance site the excerpt links into. */
    private static final String GOVERNANCE_SITE = "govsite";
    private static final String GOVERNANCE_ROOT = "/site/" + GOVERNANCE_SITE;

    private static final String SCORED = "orders";
    private static final String UNSCORED = "tariffs";
    private static final String WEAK = "orders-intake";
    private static final String STRONG = "orders-payment";

    private static final String SYSTEM_PAGE = ROOT + "/systems/" + SCORED + "/";
    private static final String COMPONENT_PAGE = ROOT + "/systems/" + SCORED
            + "/system-architecture/building-block-view/components/" + WEAK + "/";

    /** Only in the excerpt, so a search for it can only find the excerpt. */
    private static final String COMMENT = "Signature is not enforced for consumption";

    private static final int TICKS_UNTIL_BUILT = 20;
    private static final LocalDate TODAY = LocalDate.now();
    private static final Path SCREENSHOTS = Path.of("target/governance-excerpt-screenshots");

    private static final WireMockServer ARCH_REPO = new WireMockServer(options().dynamicPort());

    @Autowired
    private ArchitectureImportJob importJob;

    @Autowired
    private DocumentationBuildRunner runner;

    @Autowired
    private DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @Autowired
    private GovernanceResourceRepository governance;

    private static boolean built;

    @BeforeAll
    static void startArchRepo() {
        ARCH_REPO.start();
        stub("/docs-api/systems", """
                {"systems": [{"name": "orders", "description": "Takes orders"},
                             {"name": "tariffs", "description": "Knows what things cost"}]}""");
        stub("/docs-api/systems/orders", """
                {"name": "orders", "description": "Takes orders",
                 "components": [{"name": "orders-intake", "type": "BACKEND_SERVICE"},
                                {"name": "orders-payment", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        stub("/docs-api/systems/tariffs", """
                {"name": "tariffs", "description": "Knows what things cost",
                 "components": [{"name": "tariffs-table", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        stub("/docs-api/openapi-specs", "{\"artifacts\": []}");
        stub("/docs-api/database-schemas", "{\"artifacts\": []}");
        stub("/docs-api/systems/orders/messages", "{\"messages\": []}");
        stub("/docs-api/systems/tariffs/messages", "{\"messages\": []}");
    }

    @AfterAll
    static void stopArchRepo() {
        ARCH_REPO.stop();
    }

    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        for (String environment : List.of(MAIN, OTHER)) {
            registry.add("jeap.doc.archrepo.environments." + environment + ".url", ARCH_REPO::baseUrl);
            registry.add("jeap.doc.archrepo.environments." + environment + ".client-registration",
                    () -> "archrepo");
        }
        registry.add("spring.security.oauth2.client.registration.archrepo.client-id", () -> "jme-doc-service");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.archrepo.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.archrepo.provider", () -> "archrepo");
        registry.add("spring.security.oauth2.client.provider.archrepo.token-uri",
                () -> "http://localhost/auth/realms/test/protocol/openid-connect/token");

        registry.add("jeap.doc.sites." + SITE + ".title", () -> "Architecture");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].id", () -> MAIN);
        registry.add("jeap.doc.sites." + SITE + ".environments[0].short-name", () -> "LAB");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].label", () -> "Laboratory");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].order", () -> "2");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].main", () -> "true");
        registry.add("jeap.doc.sites." + SITE + ".environments[1].id", () -> OTHER);
        registry.add("jeap.doc.sites." + SITE + ".environments[1].short-name", () -> "REF");
        registry.add("jeap.doc.sites." + SITE + ".environments[1].label", () -> "Reference");
        registry.add("jeap.doc.sites." + SITE + ".environments[1].order", () -> "1");
        registry.add("jeap.doc.sites." + SITE + ".environments[1].latest", () -> "true");

        // The governance site: read from a Governance Service nothing here calls - the data is stored as its
        // import stores it - and generated from what was stored.
        registry.add("jeap.doc.sites." + GOVERNANCE_SITE + ".title", () -> "Governance");
        registry.add("jeap.doc.sites." + GOVERNANCE_SITE + ".per-environment", () -> "false");
        registry.add("jeap.doc.sites." + GOVERNANCE_SITE + ".content", () -> "governance");
        registry.add("jeap.doc.sites." + GOVERNANCE_SITE + ".publish-on-upload", () -> "false");
        registry.add("jeap.doc.governance.url", () -> ARCH_REPO.baseUrl() + "/governance");
        registry.add("jeap.doc.governance.client-registration", () -> "archrepo");
        registry.add("jeap.doc.governance.import.cron", () -> "-");
    }

    private static void stub(String path, String body) {
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** Both sites, generated once for the class. */
    @Override
    protected void prepareWhatIsServed() {
        if (built) {
            return;
        }
        storeTheScoringRun(72, "DOWN", 60);
        importJob.importEnvironment(MAIN);
        importJob.importEnvironment(OTHER);
        trigger.requestEveryPart(SITE);
        trigger.requestEveryPart(GOVERNANCE_SITE);
        buildWhatIsOwed();
        built = true;
    }

    @AfterAll
    static void forgetTheGovernanceData(@Autowired GovernanceResourceRepository governance) {
        List<GovernanceResourceRef> stored = new ArrayList<>();
        for (GovernanceResourceKind kind : GovernanceResourceKind.values()) {
            stored.addAll(governance.findRefs(kind));
        }
        governance.remove(stored);
    }

    // ------------------------------------------------------------------------------------------------------
    // The scenarios.
    // ------------------------------------------------------------------------------------------------------

    /**
     * The system page: the system's score with its marker, trend and history, the scoring time, and a row per
     * component - the weakest first - with the rules it fails. Read once, without a token.
     */
    @Test
    @Order(1)
    void theSystemPageShowsTheSystemsScoreAndItsComponents() {
        List<Request> excerptRequests = excerptRequests();

        open(SYSTEM_PAGE);

        Locator excerpt = governanceExcerpt();
        PlaywrightAssertions.assertThat(excerpt).isVisible();
        PlaywrightAssertions.assertThat(excerpt).containsText("🟡 72 %");
        PlaywrightAssertions.assertThat(excerpt).containsText("↓ down");
        PlaywrightAssertions.assertThat(excerpt).containsText("last 14 days");
        PlaywrightAssertions.assertThat(excerpt).containsText("scoring run of");
        PlaywrightAssertions.assertThat(excerpt.locator("svg polyline")).hasCount(1);

        Locator rows = excerpt.locator("tbody tr");
        PlaywrightAssertions.assertThat(rows).hasCount(2);
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText(WEAK);
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText("🟡 60");
        PlaywrightAssertions.assertThat(rows.nth(0))
                .containsText("2 · Component Consumes Signed Messages, Dependencies are kept current");
        PlaywrightAssertions.assertThat(rows.nth(1)).containsText(STRONG);
        PlaywrightAssertions.assertThat(rows.nth(1)).containsText("🟢 95");

        PlaywrightAssertions.assertThat(excerpt.locator("footer")).containsText("orders on the governance site →");
        PlaywrightAssertions.assertThat(excerpt.locator("footer")).containsText("Rules overview →");

        assertThat(excerptRequests).hasSize(1);
        assertThat(excerptRequests.getFirst().url()).endsWith("/ui-api/excerpts/governance/systems/" + SCORED);
        assertThat(excerptRequests.getFirst().headers()).doesNotContainKey("authorization");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The component page: its own score, and the rules it fails with the shortened comment and since when - the
     * full list of details is on the governance site.
     */
    @Test
    @Order(2)
    void theComponentPageShowsTheRulesTheComponentFails() {
        open(COMPONENT_PAGE);

        Locator excerpt = governanceExcerpt();
        PlaywrightAssertions.assertThat(excerpt).containsText("🟡 60 %");
        Locator rows = excerpt.locator("tbody tr");
        PlaywrightAssertions.assertThat(rows).hasCount(2);
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText("Component Consumes Signed Messages");
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText("❌ Non-compliant");
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText(COMMENT + ": topic orders-created + 1 more");
        PlaywrightAssertions.assertThat(rows.nth(0)).containsText("20.10.2026");
        PlaywrightAssertions.assertThat(excerpt).containsText("3 further rules compliant, 1 paused or disabled.");
        PlaywrightAssertions.assertThat(excerpt.locator("footer"))
                .containsText(WEAK + " on the governance site →");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * The root page's own list of the components leads to their pages on this site - the excerpt's lead to the
     * governance site - and comes before the excerpt.
     */
    @Test
    @Order(3)
    void theRootPageListsTheComponentsOnThisSite() {
        open(SYSTEM_PAGE);

        Locator components = page.locator("article table").filter(new Locator.FilterOptions()
                .setHas(page.locator("th", new Page.LocatorOptions().setHasText("Description"))));
        PlaywrightAssertions.assertThat(components.locator("tbody tr")).hasCount(2);
        components.locator("a", new Locator.LocatorOptions().setHasText(WEAK)).click();
        page.waitForURL(url -> url.endsWith(COMPONENT_PAGE));
        PlaywrightAssertions.assertThat(page.locator("article h1").first()).hasText(WEAK);
        assertNothingWentWrongInTheBrowser();
    }

    /** Every environment tree carries the excerpt, not only the one at the site root. */
    @Test
    @Order(4)
    void everyEnvironmentTreeShowsIt() {
        open(ROOT + "/" + OTHER + "/systems/" + SCORED + "/");

        PlaywrightAssertions.assertThat(governanceExcerpt()).containsText("🟡 72 %");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * Every link of the excerpt lands on a page of the governance site that was generated beside it - the
     * excerpt links by the same slugs the site is written with.
     */
    @Test
    @Order(5)
    void everyLinkLeadsToAPageOfTheGovernanceSite() {
        open(SYSTEM_PAGE);
        Locator excerpt = governanceExcerpt();
        PlaywrightAssertions.assertThat(excerpt).isVisible();
        Map<String, String> links = new LinkedHashMap<>();
        links.put("heading", excerpt.locator("header h3 a").getAttribute("href"));
        links.put("component", excerpt.locator("tbody tr").nth(0).locator("td a").first().getAttribute("href"));
        links.put("rule", excerpt.locator("tbody tr").nth(0).locator("td").nth(2).locator("a").first()
                .getAttribute("href"));
        links.put("rules overview", excerpt.locator("footer a", new Locator.LocatorOptions()
                .setHasText("Rules overview")).getAttribute("href"));

        assertThat(links).containsExactly(
                Map.entry("heading", GOVERNANCE_ROOT + "/systems/orders/"),
                Map.entry("component", GOVERNANCE_ROOT + "/systems/orders/orders-intake/"),
                Map.entry("rule", GOVERNANCE_ROOT + "/rules/signed-messages/"),
                Map.entry("rules overview", GOVERNANCE_ROOT + "/rules/"));

        for (Map.Entry<String, String> link : links.entrySet()) {
            Response served = open(link.getValue());
            assertThat(served.status()).describedAs(link.getKey()).isEqualTo(200);
            PlaywrightAssertions.assertThat(page.locator("article h1").first()).isVisible();
            PlaywrightAssertions.assertThat(page.getByText("Page Not Found")).hasCount(0);
        }

        // And following one from the page goes there, in the reader's tab.
        open(SYSTEM_PAGE);
        governanceExcerpt().locator("tbody tr").nth(0).locator("td a").first().click();
        page.waitForURL(url -> url.endsWith(GOVERNANCE_ROOT + "/systems/orders/orders-intake/"));
        PlaywrightAssertions.assertThat(page.locator("article h1").first()).hasText(WEAK);
        assertNothingWentWrongInTheBrowser();
    }

    /** It is not part of the published page, so the search never finds it. */
    @Test
    @Order(6)
    void theSearchIndexLeavesItOut() {
        // What the index is built from is the published HTML, and the excerpt is not in it.
        String publishedHtml = page.request().get(url(COMPONENT_PAGE)).text();
        assertThat(publishedHtml).contains("Database schema").doesNotContain(COMMENT);

        // And should it ever be rendered on the server, the index is told to leave it out.
        open(COMPONENT_PAGE);
        PlaywrightAssertions.assertThat(governanceExcerpt()).hasAttribute("data-pagefind-ignore", "all");
    }

    /** Light and dark mode both render from the theme's colours; the screenshots are for the eye. */
    @Test
    @Order(7)
    void bothColourModesRenderIt() throws Exception {
        java.nio.file.Files.createDirectories(SCREENSHOTS);
        for (ColorScheme scheme : List.of(ColorScheme.LIGHT, ColorScheme.DARK)) {
            page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(scheme));
            for (Map.Entry<String, String> shown : Map.of("system", SYSTEM_PAGE, "component", COMPONENT_PAGE)
                    .entrySet()) {
                open(shown.getValue());
                page.evaluate("mode => { localStorage.setItem('theme', mode);"
                              + " document.documentElement.dataset.theme = mode; }",
                        scheme == ColorScheme.LIGHT ? "light" : "dark");
                Locator excerpt = governanceExcerpt();
                PlaywrightAssertions.assertThat(excerpt).isVisible();
                excerpt.screenshot(new Locator.ScreenshotOptions().setPath(
                        SCREENSHOTS.resolve(shown.getKey() + "-" + scheme.name().toLowerCase() + ".png")));
            }
        }
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * A system the governance data does not know shows no excerpt at all - no frame, no message - and the page
     * is as it was.
     */
    @Test
    @Order(8)
    void aSystemWithoutGovernanceDataShowsNone() {
        Response answered = page.waitForResponse(
                response -> response.url().endsWith("/ui-api/excerpts/governance/systems/" + UNSCORED),
                () -> open(ROOT + "/systems/" + UNSCORED + "/"));

        assertThat(answered.status()).isEqualTo(204);
        PlaywrightAssertions.assertThat(page.getByText("Knows what things cost")).isVisible();
        PlaywrightAssertions.assertThat(page.locator("[data-jeap-excerpt]")).hasCount(0);
        PlaywrightAssertions.assertThat(page.locator("code.language-jeap-excerpts, pre.language-jeap-excerpts"))
                .hasCount(0);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>Live, without a rebuild.</b> A new scoring run is stored, nothing is generated, and the page shows the
     * new score the next time it is opened.
     */
    @Test
    @Order(9)
    void aNewScoringRunShowsWithoutRebuildingThePage() {
        Map<String, String> before = publishedParts(SITE);

        storeTheScoringRun(91, "UP", 88);
        open(SYSTEM_PAGE);

        PlaywrightAssertions.assertThat(governanceExcerpt()).containsText("🟢 91 %");
        PlaywrightAssertions.assertThat(governanceExcerpt()).containsText("↑ up");
        assertThat(publishedParts(SITE)).isEqualTo(before);
        assertThat(requests.pending()).noneMatch(request -> request.site().equals(SITE));
        assertNothingWentWrongInTheBrowser();
    }

    // ------------------------------------------------------------------------------------------------------

    private Locator governanceExcerpt() {
        return page.locator("[data-jeap-excerpt='governance']");
    }

    private List<Request> excerptRequests() {
        List<Request> seen = Collections.synchronizedList(new ArrayList<>());
        page.onRequest(request -> {
            if (request.url().contains("/ui-api/excerpts/")) {
                seen.add(request);
            }
        });
        return seen;
    }

    /**
     * A scoring run as the governance import stores it: the two indexes, the rules, and the system with its two
     * components. {@code orders-intake} fails two rules and complies with three; {@code orders-payment} fails
     * none.
     */
    private void storeTheScoringRun(int systemScore, String trend, int weakScore) {
        store(GovernanceResourceKind.SYSTEMS_INDEX, null, """
                {"scoredAt": "2026-10-22T06:45:21Z", "trendPeriodDays": 30,
                 "systems": [{"name": "orders", "score": %d, "trend": "%s"}]}""".formatted(systemScore, trend));
        store(GovernanceResourceKind.RULES_INDEX, null, """
                {"scoredAt": "2026-10-22T06:45:21Z",
                 "rules": [{"ruleId": "signed-messages", "name": "Component Consumes Signed Messages",
                            "active": true, "weight": 3, "share": 50.0, "conformanceRate": 50, "trend": "STABLE",
                            "exemptionCount": 0},
                           {"ruleId": "dependencies-current", "name": "Dependencies are kept current",
                            "active": true, "weight": 3, "share": 50.0, "conformanceRate": 50, "trend": "STABLE",
                            "exemptionCount": 0}]}""");
        for (String rule : List.of("signed-messages", "dependencies-current")) {
            store(GovernanceResourceKind.RULE, rule, """
                    {"ruleId": "%s", "name": "%s", "active": true, "explanation": "Why it matters.",
                     "weight": 3, "share": 50.0, "parameters": [], "exemptions": [], "systemRates": [],
                     "nonCompliantComponents": [], "gracePeriodComponents": []}""".formatted(rule,
                    rule.equals("signed-messages") ? "Component Consumes Signed Messages"
                            : "Dependencies are kept current"));
        }
        store(GovernanceResourceKind.SYSTEM, SCORED, """
                {"name": "orders", "score": %d, "trend": "%s", "history": %s,
                 "components": [
                   {"name": "orders-intake", "score": %d, "trend": "DOWN", "history": %s,
                    "ruleStates": [
                      {"ruleId": "signed-messages", "name": "Component Consumes Signed Messages", "state": "FAIL",
                       "comment": "%s", "details": ["topic orders-created", "topic orders-cancelled"],
                       "lastStatusChange": "2026-10-20T06:00:00Z"},
                      {"ruleId": "dependencies-current", "name": "Dependencies are kept current", "state": "FAIL",
                       "comment": "Three dependencies are older than 90 days", "details": [],
                       "lastStatusChange": "2026-10-01T06:00:00Z"},
                      {"ruleId": "a", "name": "A", "state": "OK"},
                      {"ruleId": "b", "name": "B", "state": "OK"},
                      {"ruleId": "c", "name": "C", "state": "OK"},
                      {"ruleId": "d", "name": "D", "state": "PAUSED"}]},
                   {"name": "orders-payment", "score": 95, "trend": "STABLE", "history": [],
                    "ruleStates": [{"ruleId": "a", "name": "A", "state": "OK"}]}]}""".formatted(
                systemScore, trend, history(systemScore), weakScore, history(weakScore), COMMENT));
    }

    private static String history(int today) {
        List<String> days = new ArrayList<>();
        for (int back = 19; back >= 0; back--) {
            days.add("{\"day\": \"%s\", \"score\": %d}".formatted(TODAY.minusDays(back),
                    back == 0 ? today : today + (back % 4)));
        }
        return "[" + String.join(", ", days) + "]";
    }

    private void store(GovernanceResourceKind kind, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        governance.store(new GovernanceResource(kind, key, "\"" + kind + "-" + json.hashCode() + "\"", bytes,
                bytes.length, Instant.now()));
    }

    private void buildWhatIsOwed() {
        for (int tick = 0; tick < TICKS_UNTIL_BUILT; tick++) {
            List<String> owed = owed();
            if (owed.isEmpty()) {
                return;
            }
            runner.runOnce();
        }
        throw new AssertionError("Still owed a build after %d ticks: %s".formatted(TICKS_UNTIL_BUILT, owed()));
    }

    private List<String> owed() {
        return requests.pending().stream()
                .filter(request -> request.site().equals(SITE) || request.site().equals(GOVERNANCE_SITE))
                .map(request -> request.site() + "/" + request.part().part())
                .toList();
    }

    private Map<String, String> publishedParts(String site) {
        Map<String, String> byPart = new LinkedHashMap<>();
        for (PublishedPart published : builds.publishedPartsOf(site)) {
            byPart.put(published.part(), published.objectPrefix());
        }
        return byPart;
    }
}
