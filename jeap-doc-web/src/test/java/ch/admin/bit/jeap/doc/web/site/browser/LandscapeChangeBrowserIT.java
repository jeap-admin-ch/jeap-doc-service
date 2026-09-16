package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.security.test.client.configuration.JeapOAuth2IntegrationTestClientConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A landscape that changes, all the way to what the reader sees.</b>
 * <p>
 * Everything else about the split stops at a seam: which parts a change asks for is asserted against the
 * request rows, and what a build produces is asserted against the markup. Neither says that a system whose
 * model moved is <i>republished</i> - and that is the whole promise of publishing a site in parts. So this
 * suite runs the chain: the architecture repository answers over HTTP, the import stores the landscape and
 * asks for every part of the site, the runner really generates the ones whose content moved with Docusaurus,
 * the result is published to the object storage, and Chrome opens the pages afterwards.
 * <p>
 * <b>The import asks for everything and the digest decides.</b> So what the assertions below read is not what
 * was asked for but what was <i>published</i>: a part whose content is what is already published is skipped,
 * and the objects it is served from do not change.
 * <p>
 * <b>The tests are ordered on purpose.</b> The subject is a landscape evolving - it changes, gains a system
 * and loses one - and each step starts from what the one before it published. That is also what makes the
 * suite affordable: the parts nothing changed are never rebuilt, which the build identifiers below assert.
 * <p>
 * It has a site and an environment of its own. The classes of this module share one database, an import
 * replaces a whole environment, and the newest successful build of a part is what is served for it - so a
 * suite that used {@code default} and {@code prod} would be driving another class's landscape as soon as one
 * happened to run in between.
 */
@Import(JeapOAuth2IntegrationTestClientConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LandscapeChangeBrowserIT extends BrowserTestBase {

    private static final String SITE = "landscape";

    /**
     * An environment id that is none of the four a site defaults to.
     * <p>
     * A site that configures no environments carries dev, ref, abn and prod - so an import of any of those
     * would ask for the parts of every other site this instance knows, and this suite would spend its ticks
     * generating documentation nobody here is looking at.
     */
    private static final String ENVIRONMENT = "lab";

    /** Where this site is served: it is not the one at the root. */
    private static final String ROOT = "/site/" + SITE;

    /** How many ticks the parts of this site are given before the suite gives up. */
    private static final int TICKS_UNTIL_BUILT = 20;

    private static final WireMockServer ARCH_REPO = new WireMockServer(options().dynamicPort());

    @Autowired
    private ArchitectureImportJob importJob;

    @Autowired
    private DocumentationBuildRunner runner;

    @Autowired
    private ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private DocumentationBuildRequestRepository requests;

    @BeforeAll
    static void startArchRepo() {
        ARCH_REPO.start();
        theLandscapeAsItStarts();
    }

    @AfterAll
    static void stopArchRepo() {
        ARCH_REPO.stop();
    }

    @DynamicPropertySource
    static void siteProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".url", ARCH_REPO::baseUrl);
        registry.add("jeap.doc.archrepo.environments." + ENVIRONMENT + ".client-registration", () -> "archrepo");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-id", () -> "jme-doc-service");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.archrepo.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.archrepo.provider", () -> "archrepo");
        registry.add("spring.security.oauth2.client.provider.archrepo.token-uri",
                () -> "http://localhost/auth/realms/test/protocol/openid-connect/token");
        registry.add("jeap.doc.sites." + SITE + ".title", () -> "The Landscape");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].id", () -> ENVIRONMENT);
        registry.add("jeap.doc.sites." + SITE + ".environments[0].short-name", () -> "LAB");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].label", () -> "The Laboratory");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].main", () -> "true");
        registry.add("jeap.doc.sites." + SITE + ".environments[0].latest", () -> "true");
    }

    /**
     * Three systems: two that exchange an event, and one that exchanges nothing with anything. The third is
     * what makes the diff falsifiable - without it, "only what changed" and "everything" look the same.
     */
    private static void theLandscapeAsItStarts() {
        systemsAre("""
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"},
                  {"name": "tariffs", "description": "Knows what things cost"}""");
        ordersRelatesTo("shipping");
        stub("/docs-api/systems/shipping", """
                {"name": "shipping", "description": "Sends the goods out",
                 "components": [{"name": "shipping-gateway", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        tariffsHas("""
                {"name": "tariffs-table", "type": "BACKEND_SERVICE"}""");
        stub("/docs-api/openapi-specs", "{\"artifacts\": []}");
        stub("/docs-api/database-schemas", "{\"artifacts\": []}");
        for (String system : List.of("orders", "shipping", "tariffs", "returns")) {
            stub("/docs-api/systems/" + system + "/messages", "{\"messages\": []}");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // The landscape, as the architecture repository answers it. Each of these replaces one resource.
    // ------------------------------------------------------------------------------------------------------

    private static void systemsAre(String systems) {
        stub("/docs-api/systems", "{\"systems\": [\n" + systems + "\n]}");
    }

    /** The relations orders declares, which is what makes the systems it names its neighbours. */
    private static void ordersRelatesTo(String... systems) {
        StringBuilder relations = new StringBuilder();
        for (String system : systems) {
            if (!relations.isEmpty()) {
                relations.append(",\n");
            }
            relations.append("""
                    {"type": "EVENT_RELATION", "consumerSystem": "%s", "consumer": "%s-gateway",
                     "providerSystem": "orders", "provider": "orders-intake",
                     "messageType": "OrdersPaymentAcceptedEvent"}""".formatted(system, system));
        }
        stub("/docs-api/systems/orders", """
                {"name": "orders", "description": "Takes orders and follows them through",
                 "team": {"name": "Team Blue", "contactAddress": "blue@example.com"},
                 "components": [{"name": "orders-intake", "type": "BACKEND_SERVICE"}],
                 "relations": [%s]}""".formatted(relations));
    }

    private static void tariffsHas(String components) {
        stub("/docs-api/systems/tariffs", """
                {"name": "tariffs", "description": "Knows what things cost",
                 "components": [%s],
                 "relations": []}""".formatted(components));
    }

    private static void stub(String path, String body) {
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ------------------------------------------------------------------------------------------------------
    // The scenarios.
    // ------------------------------------------------------------------------------------------------------

    /**
     * The landscape as it starts, published: three systems, each with its own part, and the shell carrying the
     * index that lists them.
     */
    @Test
    @Order(1)
    void theLandscapeIsPublished() {
        publishEverything();

        assertThat(publishedParts().keySet())
                .containsExactlyInAnyOrder("shell", "system-orders", "system-shipping", "system-tariffs");

        open(ROOT + "/systems/");
        PlaywrightAssertions.assertThat(page.getByText("Takes orders and follows them through")).isVisible();
        PlaywrightAssertions.assertThat(page.getByText("Sends the goods out")).isVisible();
        PlaywrightAssertions.assertThat(page.getByText("Knows what things cost")).isVisible();
        assertNothingWentWrongInTheBrowser();

        // The shell's sidebar names every system, and that it can is not obvious: each of them is built as a
        // part of its own, so their pages are in no tree this build ever sees. What names them is the list the
        // generator wrote into environments.json - so a reader on the root page can reach a system without
        // going through the index first.
        open(ROOT + "/");
        com.microsoft.playwright.Locator sidebar = page.locator("nav.menu");
        // By href rather than by label: the sidebar is upper-cased by the stylesheet, and what has to be
        // right here is where each link goes.
        for (String system : List.of("orders", "shipping", "tariffs")) {
            PlaywrightAssertions.assertThat(sidebar.locator("a[href$='/systems/" + system + "/']"))
                    .isVisible();
        }

        // And following one goes there, in the tab the reader is in.
        int tabsBefore = page.context().pages().size();
        sidebar.locator("a[href$='/systems/orders/']").click();
        page.waitForURL(url -> url.contains("/systems/orders/"));

        assertThat(page.context().pages()).hasSize(tabsBefore);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>The three directions a reader crosses between builds, each in the tab they are in.</b>
     * <p>
     * A site is one build per system plus a shell, and a link between two of them is a plain anchor rather
     * than a route of the build it sits in. Docusaurus read every one of those as external and opened a new
     * tab - which was reported as the links being broken. The fix is in three places, and one test that walks
     * all three directions is what says the three agree: a Markdown link on a page, the way out of a part in
     * its sidebar, and the footer and logo the configuration sets.
     */
    @Test
    @Order(2)
    void crossingBetweenPartsStaysInTheReadersTab() {
        int tabs = page.context().pages().size();

        // Shell -> system: a Markdown link on the systems index, a page the shell writes into another part.
        open(ROOT + "/systems/");
        Locator orders = page.locator("article a[href$='/systems/orders/']").first();
        PlaywrightAssertions.assertThat(orders).isVisible();
        PlaywrightAssertions.assertThat(orders).not().hasAttribute("target", "_blank");
        orders.click();
        page.waitForURL(url -> url.endsWith("/systems/orders/"));
        assertThat(page.context().pages()).describedAs("shell to system").hasSize(tabs);

        // System -> shell: the way out of a part, which is a sidebar link and a different component.
        Locator allSystems = page.locator("nav.menu a[href$='/systems/']").first();
        PlaywrightAssertions.assertThat(allSystems).isVisible();
        allSystems.click();
        page.waitForURL(url -> url.endsWith("/systems/"));
        assertThat(page.context().pages()).describedAs("system to shell").hasSize(tabs);

        // System -> system: the neighbour a context view links, which is the case that was reported.
        open(ROOT + "/systems/orders/system-architecture/context-and-scope/system-context-view/");
        Locator shipping = page.locator("article a[href$='/systems/shipping/']").first();
        PlaywrightAssertions.assertThat(shipping).isVisible();
        shipping.click();
        page.waitForURL(url -> url.endsWith("/systems/shipping/"));
        assertThat(page.context().pages()).describedAs("system to system").hasSize(tabs);

        // And what the configuration carries rather than a wrapper: the footer, and the logo of a part.
        PlaywrightAssertions.assertThat(page.locator("footer a[href$='/about-this-documentation/']").first())
                .hasAttribute("target", "_self");
        PlaywrightAssertions.assertThat(page.locator("a.navbar__brand").first())
                .hasAttribute("target", "_self");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A system nobody exchanges anything with is republished on its own.</b> The component appears on its
     * page, and every other part of the site keeps the build it was published from - the import asked for them
     * too, and their content hashed to what was already there.
     * <p>
     * The shell is republished as well, and that is not slack either: the page describing the documentation
     * counts the components of every environment, so a component appearing anywhere rewrites it. Which parts
     * move is decided by a hash over what was generated rather than by a rule about what a landscape change
     * touches - so it is exactly the parts whose pages differ, and never a part more.
     */
    @Test
    @Order(3)
    void whenAnIndependentSystemChanges_thenOnlyItsOwnPagesAreRepublished() {
        Map<String, String> before = publishedParts();

        tariffsHas("""
                {"name": "tariffs-table", "type": "BACKEND_SERVICE"},
                {"name": "tariffs-registry", "type": "BACKEND_SERVICE"}""");
        importAndBuild();

        assertThat(rebuiltSince(before))
                .describedAs("the system that changed, and the shell - whose page about the documentation "
                             + "counts the components of every environment")
                .containsExactlyInAnyOrder("system-tariffs", "shell");

        // The whitebox view draws the components of the system, and each of them has a page of its own -
        // which is a route that did not exist until this import.
        open(ROOT + "/systems/tariffs/system-architecture/building-block-view/whitebox-view/");
        assertThat(page.content()).contains("tariffs-registry");

        Response served = open(ROOT
                + "/systems/tariffs/system-architecture/building-block-view/components/tariffs-registry/");
        assertThat(served.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByText("Backend Service")).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>And a relation has two ends.</b> {@code orders} declares one to {@code tariffs}, so what changed is
     * the model of orders - but the pages of tariffs draw that relation too, and a reader opening tariffs sees
     * it. Both parts are republished because both their contents moved, which is the thing a digest over the
     * generated content catches and a diff of the landscape would have had to be told.
     */
    @Test
    @Order(4)
    void whenARelationAppears_thenTheSystemAtItsOtherEndIsRepublishedToo() {
        Map<String, String> before = publishedParts();

        ordersRelatesTo("shipping", "tariffs");
        importAndBuild();

        assertThat(rebuiltSince(before))
                .describedAs("the system that declares the relation, and the one at its other end")
                .contains("system-orders", "system-tariffs");

        open(ROOT + "/systems/tariffs/system-architecture/context-and-scope/system-context-view/");
        assertThat(page.content())
                .describedAs("the neighbour it gained is drawn on its own context view")
                .contains("orders");
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * A system that appears is a part that did not exist. Nothing had ever been published for it, so this is
     * also the case that says a new part is built rather than waiting for a full rebuild.
     */
    @Test
    @Order(5)
    void whenTheLandscapeGainsASystem_thenItIsPublishedAndListed() {
        Map<String, String> before = publishedParts();

        systemsAre("""
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"},
                  {"name": "tariffs", "description": "Knows what things cost"},
                  {"name": "returns", "description": "Takes the goods back"}""");
        stub("/docs-api/systems/returns", """
                {"name": "returns", "description": "Takes the goods back",
                 "components": [{"name": "returns-desk", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        importAndBuild();

        assertThat(publishedParts()).containsKey("system-returns");
        assertThat(rebuiltSince(before)).contains("system-returns", "shell");

        open(ROOT + "/systems/");
        PlaywrightAssertions.assertThat(page.getByText("Takes the goods back")).isVisible();

        Response served = open(ROOT + "/systems/returns/");
        assertThat(served.status()).isEqualTo(200);
        PlaywrightAssertions.assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("returns").setLevel(1))).isVisible();
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * <b>A system that leaves the landscape is taken off the index, and its pages are left where they are.</b>
     * <p>
     * Deliberately: a landscape that briefly lost a system over one bad import must not lose that system's
     * documentation with it. The pages become unreachable from any index or link and the nightly clean-up is
     * what eventually removes them - so what a reader sees is a system that is gone from the documentation,
     * while a link somebody saved still resolves.
     */
    @Test
    @Order(6)
    void whenASystemLeavesTheLandscape_thenItIsGoneFromTheIndexAndItsPartIsNotRebuilt() {
        Map<String, String> before = publishedParts();

        systemsAre("""
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"},
                  {"name": "returns", "description": "Takes the goods back"}""");
        ordersRelatesTo("shipping");
        importAndBuild();

        assertThat(rebuiltSince(before))
                .describedAs("the index, and orders - which is what its relation pointed at")
                .contains("shell", "system-orders")
                .doesNotContain("system-tariffs");

        open(ROOT + "/systems/");
        PlaywrightAssertions.assertThat(page.getByText("Knows what things cost")).not().isVisible();
        PlaywrightAssertions.assertThat(page.getByText("Takes the goods back")).isVisible();

        assertThat(open(ROOT + "/systems/tariffs/").status())
                .describedAs("still served, and unreachable: the clean-up is what removes it")
                .isEqualTo(200);
        assertNothingWentWrongInTheBrowser();
    }

    /**
     * An import that finds the landscape it already had asks for nothing at all - the whole landscape is
     * compared by one hash before anything is written - so an hour in which nothing changed costs no build.
     */
    @Test
    @Order(7)
    void whenNothingChanges_thenNothingIsRebuilt() {
        Map<String, String> before = publishedParts();

        importAndBuild();

        assertThat(rebuiltSince(before)).isEmpty();
    }

    /**
     * <b>What the page cannot carry, fetched and filled in.</b>
     * <p>
     * When a schedule fires next moves with the clock, so it is not written into the page at all - a part
     * whose documentation has not moved is not generated again, and the case above is what proves that
     * happens. The page leaves the cell empty, links the resource under the table, and a client module of the
     * template fills the cell in.
     * <p>
     * <b>This is the only thing checking that the two sides agree.</b> The column headings are constants in
     * {@code AboutThisDocumentation} and in {@code liveStatus.js}, and nothing compares them at compile time:
     * a rename on one side leaves the cells as the generator wrote them, which is what this would catch.
     */
    @Test
    @Order(8)
    void theAboutPageFillsInWhatIsTrueRightNow() {
        Response served = open(ROOT + "/about-this-documentation/");

        assertThat(served.status()).isEqualTo(200);
        // The source line the page carries whether or not anything is fetched, so that a reader with no
        // scripts can follow it themselves.
        PlaywrightAssertions.assertThat(page.getByText("live-status.json")).isVisible();

        // One cell per scheduled job of the service, all filled from a single fetch.
        Locator filled = page.locator("span[data-jeap-doc-live]");
        PlaywrightAssertions.assertThat(filled).hasCount(5);
        assertThat(filled.nth(0).textContent())
                .describedAs("when the import fires next, spelled out from now")
                .contains("(in ");
        assertNothingWentWrongInTheBrowser();
    }

    // ------------------------------------------------------------------------------------------------------
    // Driving the service.
    // ------------------------------------------------------------------------------------------------------

    /**
     * The landscape as it stands, published whole. It is the baseline the cases after it start from.
     */
    private void publishEverything() {
        importJob.importEnvironment(ENVIRONMENT);
        // Asked for outright rather than left to the import: the catch-up import at startup may already have
        // stored this landscape, and an import that finds the one it has asks for nothing. What the import
        // asks for is what the cases after this one are about.
        trigger.requestEveryPart(SITE);
        buildWhatIsOwed();
    }

    /** Imports the landscape and builds whatever <b>the import</b> asked for. */
    private void importAndBuild() {
        importJob.importEnvironment(ENVIRONMENT);
        buildWhatIsOwed();
    }

    /**
     * Builds until nothing is owed. The runner is called rather than waited for: the poll interval of this
     * suite's configuration is longer than the suite, so no scheduled tick starts a build in the middle of a
     * case.
     */
    private void buildWhatIsOwed() {
        for (int tick = 0; tick < TICKS_UNTIL_BUILT; tick++) {
            if (nothingIsOwed()) {
                return;
            }
            runner.runOnce();
        }
        throw new AssertionError("The parts of %s were still owed a build after %d ticks: %s"
                .formatted(SITE, TICKS_UNTIL_BUILT, owedParts()));
    }

    private boolean nothingIsOwed() {
        return owedParts().isEmpty();
    }

    private List<String> owedParts() {
        return requests.pending().stream().filter(request -> request.site().equals(SITE))
                .map(request -> request.part().part()).toList();
    }

    /**
     * What is published for each part of this site, by the objects it is served from. The prefix carries the
     * identifier of the build that wrote it, so it changes exactly when a part is republished.
     */
    private Map<String, String> publishedParts() {
        Map<String, String> byPart = new LinkedHashMap<>();
        for (PublishedPart published : builds.publishedPartsOf(SITE)) {
            byPart.put(published.part(), published.objectPrefix());
        }
        return byPart;
    }

    /**
     * The parts whose publication is not the one it was: a part built again, or one published for the first
     * time. It is what says <i>this</i> was republished and the rest was not.
     */
    private List<String> rebuiltSince(Map<String, String> before) {
        return publishedParts().entrySet().stream()
                .filter(published -> !published.getValue().equals(before.get(published.getKey())))
                .map(Map.Entry::getKey)
                .toList();
    }
}
