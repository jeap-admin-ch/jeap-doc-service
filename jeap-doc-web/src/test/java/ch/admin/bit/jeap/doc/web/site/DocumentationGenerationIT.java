package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportJob;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import ch.admin.bit.jeap.security.test.client.configuration.JeapOAuth2IntegrationTestClientConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path, in one test: a build is asked for, the architecture model is read over HTTP, the pages are
 * written, the site generator runs, the result is published to the object storage, and a reader fetches it from
 * the service.
 * <p>
 * <b>Every other test in this repository proves one seam.</b> This is the one that proves they join up, and it
 * exists because this story can be green in every unit and still produce an empty site: a mapper that drops the
 * systems, a writer that puts the tree one directory too deep, a {@code _category_.json} the site generator
 * ignores, a link that resolves in a fixture and not in a real tree. None of those is visible until all of it
 * runs together.
 * <p>
 * What it deliberately does <b>not</b> prove is the OAuth2 client-credentials flow: the token is stubbed, so
 * everything above it is exercised and the flow itself is covered by the architecture repository's own security
 * tests and by the smoke test after a deployment.
 */
@Import(JeapOAuth2IntegrationTestClientConfiguration.class)
class DocumentationGenerationIT extends DocServiceIntegrationTestBase {

    private static final WireMockServer ARCH_REPO = new WireMockServer(options().dynamicPort());

    /**
     * How many times {@link #buildUntilServed} asks for a build before it gives up. More than one because the
     * runner builds at most one site per tick and the classes of this module share a database, so a round can
     * be spent on somebody else's site.
     */
    private static final int ROUNDS_UNTIL_SERVED = 5;

    /** How long the model is asked for, and how long between two attempts - see the method below. */
    private static final java.time.Duration IMPORT_BUDGET = java.time.Duration.ofSeconds(60);

    private static final java.time.Duration IMPORT_RETRY_DELAY = java.time.Duration.ofMillis(500);

    /** Whether the default site has been built in this class already - see {@link #build()}. */
    private static boolean defaultSiteBuilt;

    @Autowired
    private DocumentationBuildTrigger trigger;

    @Autowired
    private DocumentationBuildRunner runner;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentationBuildRepository builds;

    @Autowired
    private ArchitectureImportJob importJob;

    @Autowired
    private ArchitectureImportRepository imports;

    @Autowired
    private ArchitectureModelSource architectureModel;

    @BeforeAll
    static void startArchRepo() {
        ARCH_REPO.start();
    }

    @AfterAll
    static void stopArchRepo() {
        ARCH_REPO.stop();
    }

    @DynamicPropertySource
    static void archRepoProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.doc.archrepo.environments.prod.url", ARCH_REPO::baseUrl);
        registry.add("jeap.doc.archrepo.environments.prod.client-registration", () -> "archrepo");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-id", () -> "jme-doc-service");
        registry.add("spring.security.oauth2.client.registration.archrepo.client-secret", () -> "secret");
        registry.add("spring.security.oauth2.client.registration.archrepo.authorization-grant-type",
                () -> "client_credentials");
        registry.add("spring.security.oauth2.client.registration.archrepo.provider", () -> "archrepo");
        registry.add("spring.security.oauth2.client.provider.archrepo.token-uri",
                () -> "http://localhost/auth/realms/test/protocol/openid-connect/token");
        // One environment per site, so a build stays short.
        registry.add("jeap.doc.sites.default.environments[0].id", () -> "prod");
        registry.add("jeap.doc.sites.default.environments[0].short-name", () -> "PROD");
        registry.add("jeap.doc.sites.default.environments[0].label", () -> "Production");
        registry.add("jeap.doc.sites.default.environments[0].main", () -> "true");
        registry.add("jeap.doc.sites.default.environments[0].latest", () -> "true");
        // The second site's environment is 'ref', which no architecture repository is configured for. The map
        // is instance-wide and keyed by environment id rather than by site - an environment names a stage, and
        // two sites with a 'prod' environment mean the same stage of the same landscape - so this is what an
        // environment without a model looks like.
        registry.add("jeap.doc.sites.governance.environments[0].id", () -> "ref");
        registry.add("jeap.doc.sites.governance.environments[0].main", () -> "true");
        registry.add("jeap.doc.sites.governance.environments[0].latest", () -> "true");
    }

    @BeforeEach
    void stubTheLandscape() {
        ARCH_REPO.resetAll();
        // The two artifact indexes, empty: this test is about the pages generated from the model, and nothing
        // renders a specification or a schema yet. They are stubbed all the same, because an import runs every
        // kind and a missing index is a failure rather than an empty one - an architecture repository too old
        // to serve it must not look like one that publishes nothing.
        stub("/docs-api/openapi-specs", "{\"artifacts\": []}");
        stub("/docs-api/database-schemas", "{\"artifacts\": []}");
        stub("/docs-api/systems", """
                {"systems": [
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"}
                ]}""");
        stub("/docs-api/systems/orders", """
                {"name": "orders", "description": "Takes orders and follows them through",
                 "team": {"name": "Team Blue", "contactAddress": "blue@example.com"},
                 "components": [
                   {"name": "orders-intake", "description": "Takes payments in",
                    "type": "BACKEND_SERVICE", "importer": "DEPLOYMENT_LOG"}
                 ],
                 "relations": [
                   {"type": "EVENT_RELATION", "consumerSystem": "shipping", "consumer": "shipping-gateway",
                    "providerSystem": "orders", "provider": "orders-intake",
                    "messageType": "OrdersPaymentAcceptedEvent"}
                 ]}""");
        stub("/docs-api/systems/orders/messages", """
                {"messages": [
                  {"name": "OrdersPaymentAcceptedEvent", "kind": "EVENT", "scope": "internal",
                   "topic": "orders-payment", "description": "The payment was accepted.",
                   "versions": ["1.0.0"],
                   "contracts": [{"role": "PUBLISHER", "component": "orders-intake", "system": "orders",
                                  "topic": "orders-payment", "versions": ["1.0.0"]}]}
                ]}""");
        stub("/docs-api/systems/shipping", """
                {"name": "shipping", "description": "Sends the goods out",
                 "components": [{"name": "shipping-gateway", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        stub("/docs-api/systems/shipping/messages", "{\"messages\": []}");
    }

    /**
     * The whole path, and the assertions are made on HTML fetched from the service rather than on files in the
     * workspace: the security headers and the resolution of a directory to its {@code index.html} apply only
     * there, and a suite that serves the files itself would pass whatever the service does.
     */
    @Test
    void aBuild_readsTheModelGeneratesTheSiteAndServesIt() throws Exception {
        build();

        assertThat(page("/systems/"))
                .describedAs("the model got all the way through to a served page")
                .contains("Systems")
                .contains("Takes orders and follows them through")
                .contains("Sends the goods out");
    }

    /**
     * The URL layout of the plan, in a real tree rather than in a fixture - including that the site generator
     * strips the chapter's number prefix from the path and puts it back in the navigation.
     */
    @Test
    void theChapterNumbersOrderTheNavigationAndAreNotInTheUrl() throws Exception {
        build();

        String chapter = page("/systems/orders/system-architecture/building-block-view/");
        assertThat(chapter).contains("5. Building Block View");
        mockMvc.perform(get("/systems/orders/system-architecture/5-building-block-view/"))
                .andExpect(status().isNotFound());
    }

    /**
     * A fence that reaches the page but not the plugin renders as text, and a build is green either way. Only a
     * fetch of the built page shows which happened.
     */
    @Test
    void theContextDiagramIsRenderedByThePluginRatherThanAsAnImage() throws Exception {
        build();

        String view = page("/systems/orders/system-architecture/context-and-scope/system-context-view/");
        assertThat(view).containsPattern("data-plantuml-diagram=\"?plantuml");
        assertThat(view).doesNotContain(".png");
    }

    @Test
    void aMessagePageIsServedUnderItsKebabCasedName() throws Exception {
        build();

        assertThat(page("/systems/orders/system-architecture/building-block-view/events/"
                        + "orders-payment-accepted-event/"))
                .contains("OrdersPaymentAcceptedEvent")
                .contains("Publisher Contracts");
    }

    @Test
    void arc42IsCreditedOnChapterOne() throws Exception {
        build();

        assertThat(page("/systems/orders/system-architecture/intro/"))
                .contains("Gernot Starke")
                .contains("CC BY-SA 4.0");
    }

    /**
     * Every page the template generates, fetched from the service.
     * <p>
     * The unit tests assert what a page says; this asserts that the site generator routed it and the service
     * serves it. A page written into the wrong folder, or one Docusaurus dropped, shows up here and nowhere
     * else.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            /systems/                                                                                     | Takes orders and follows them through
            /systems/orders/                                                                              | Documentation
            /systems/orders/system-architecture/                                                          | System Architecture
            /systems/orders/system-architecture/intro/                                                    | Introduction and Goals
            /systems/orders/system-architecture/context-and-scope/                                        | Context and Scope
            /systems/orders/system-architecture/context-and-scope/system-context-view/                    | System Context View
            /systems/orders/system-architecture/building-block-view/                                      | Building Block View
            /systems/orders/system-architecture/building-block-view/whitebox-view/                        | Whitebox View
            /systems/orders/system-architecture/building-block-view/components/orders-intake/             | Takes payments in
            /systems/orders/system-architecture/building-block-view/events/                               | Events
            /systems/orders/system-architecture/building-block-view/events/orders-payment-accepted-event/ | OrdersPaymentAcceptedEvent
            /systems/orders/system-architecture/runtime-view/                                             | Runtime View
            /systems/orders/system-architecture/runtime-view/system-reactions/                            | System Reactions
            /systems/shipping/                                                                            | Sends the goods out
            """)
    void everyGeneratedPageIsServed(String path, String marker) throws Exception {
        build();

        assertThat(page(path.strip())).contains(marker.strip());
    }

    /**
     * The chapters with nothing to generate are not created, so a gap in the numbering is what a reader sees.
     */
    @ParameterizedTest
    @ValueSource(strings = {"constraints", "solution-strategy", "deployment-view", "crosscutting-concepts",
            "architecture-decision-records", "quality-requirements", "risks", "glossary"})
    void theChaptersWithNothingInThemAreNotServed(String chapter) throws Exception {
        build();

        mockMvc.perform(get("/systems/orders/system-architecture/" + chapter + "/"))
                .andExpect(status().isNotFound());
    }

    /**
     * The second diagram of the story. It is drawn from different data than the context view, so a fence that
     * reaches the page but not the plugin would show up only here.
     */
    @Test
    void theWhiteboxDiagramIsRenderedByThePluginRatherThanAsAnImage() throws Exception {
        build();

        String view = page("/systems/orders/system-architecture/building-block-view/whitebox-view/");
        assertThat(view).containsPattern("data-plantuml-diagram=\"?plantuml");
        assertThat(view).doesNotContain(".png");
        assertThat(view).contains("orders-intake");
    }

    /**
     * A component page is what the story leaves ready for the component documentation to hang from.
     */
    @Test
    void theComponentPageCarriesWhatTheModelKnows() throws Exception {
        build();

        assertThat(page("/systems/orders/system-architecture/building-block-view/components/orders-intake/"))
                .contains("Backend Service")
                .contains("DEPLOYMENT_LOG");
    }

    /**
     * The runtime view is the one page generated empty on purpose, so it has to say what it is waiting for.
     */
    @Test
    void theRuntimeViewSaysWhatItIsWaitingFor() throws Exception {
        build();

        assertThat(page("/systems/orders/system-architecture/runtime-view/system-reactions/"))
                .contains("reaction observer");
    }

    /**
     * A commands group is only written when the system defines one, so a system with none has no folder.
     */
    @Test
    void aSystemWithoutCommandsHasNoCommandsGroup() throws Exception {
        build();

        mockMvc.perform(get("/systems/orders/system-architecture/building-block-view/commands/"))
                .andExpect(status().isNotFound());
    }

    /**
     * An environment with no architecture repository is a legitimate configuration: its tree carries the root
     * page and nothing model-derived, and that must not fail a build.
     */
    @Test
    void theEnvironmentWithoutAnArchitectureRepository_isPublishedWithNoSystems() throws Exception {
        buildUntilServed("governance", "/site/governance/");

        mockMvc.perform(get("/site/governance/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Governance")));
        mockMvc.perform(get("/site/governance/systems/")).andExpect(status().isNotFound());
    }

    /**
     * The point of importing the architecture model rather than reading it during a build: an architecture
     * repository that is down or being deployed cannot stop a site from being published. What the site then
     * shows is the model as of the last successful import.
     */
    @Test
    void whenTheArchitectureRepositoryIsBroken_thenTheSiteIsStillPublishedFromWhatWasImported() throws Exception {
        build();

        ARCH_REPO.resetAll();
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/docs-api/systems"))
                .willReturn(aResponse().withStatus(503)));
        // The import fails and leaves the stored landscape alone; the build that follows reads that landscape.
        importJob.importEnvironment("prod");
        long publishedBefore = builds.published(Site.DEFAULT_SITE).map(DocumentationBuild::id).orElse(-1L);
        tickUntilTheSiteIsPublishedAgain();

        // The newest build of any state, not builds.published(...) - that one *is* "the newest SUCCEEDED
        // build", so asserting it succeeded says nothing at all, on the one test that is about a build not
        // failing. A build that failed would leave the older publication in place and go unnoticed here.
        assertThat(builds.recent(Site.DEFAULT_SITE, 1)).singleElement()
                .describedAs("a broken architecture repository does not fail a build any more")
                .satisfies(newest -> {
                    assertThat(newest.state()).isEqualTo(BuildState.SUCCEEDED);
                    assertThat(newest.id()).isNotEqualTo(publishedBefore);
                });
        // Not the same bytes: the page says when it was built, and this is a new build. What has to survive is
        // the landscape, which the failed import left exactly as it was.
        assertThat(page("/systems/")).describedAs("the systems imported before are still documented")
                .contains("orders").contains("shipping");
    }

    /**
     * Ticks until a build of the default site has succeeded, and reports which one it was.
     * <p>
     * <b>The request is made on every round</b>, as {@link #buildUntilServed} explains at length: one tick
     * builds at most one site, the classes of this module share a database, and a tick of another class's
     * runner may claim the request before this one gets to it - after which asking once would leave every
     * remaining tick with nothing to build.
     */
    private long tickUntilTheSiteIsPublishedAgain() {
        long before = builds.published(Site.DEFAULT_SITE).map(DocumentationBuild::id).orElse(-1L);
        for (int tick = 0; tick < 5; tick++) {
            trigger.requestBecauseAnOperatorAsked(Site.DEFAULT_SITE);
            runner.runOnce();
            Optional<DocumentationBuild> published = builds.published(Site.DEFAULT_SITE);
            if (published.isPresent() && published.get().id() != before) {
                return published.get().id();
            }
        }
        throw new AssertionError("The default site was not published again after five ticks. The newest build "
                                 + "is " + builds.recent(Site.DEFAULT_SITE, 1) + ".");
    }

    /**
     * Asks for a site and ticks until it is served.
     * <p>
     * One tick builds <b>at most one site</b>, and the test classes of this module share a database and a
     * bucket - so a tick may pick up a request another class left behind before it reaches this one, and the
     * request this method makes may be served by another class's runner, whose context configures other sites
     * and no architecture repository. <b>So the request is made again on every round</b>, rather than once
     * before the first: a request that somebody else consumed is not a request this method has to do without.
     * <p>
     * The rounds are what makes this independent of what ran before it, rather than of the order things happen
     * to run in - and when they run out, the message says what the state actually was. A bare "not published"
     * is what sent somebody log-diving for half an hour.
     */
    private void buildUntilServed(String site, String probe) throws Exception {
        // A build reads what was imported and calls the architecture repository not at all, so the landscape
        // has to be in the database before one is asked for.
        importUntilTheModelIsStored();
        int status = 0;
        for (int round = 0; round < ROUNDS_UNTIL_SERVED; round++) {
            trigger.requestBecauseAnOperatorAsked(site);
            runner.runOnce();
            status = mockMvc.perform(get(probe)).andReturn().getResponse().getStatus();
            if (status == 200) {
                return;
            }
        }
        throw new AssertionError(("The site %s was not published after %d rounds; %s answers %d.%n"
                                  + "  the import of prod: %s%n"
                                  + "  the model of prod: configured=%s, imported=%s%n"
                                  + "  the published build of %s: %s")
                .formatted(site, ROUNDS_UNTIL_SERVED, probe, status,
                        imports.state("prod", ArchitectureImportKind.MODEL),
                        architectureModel.isConfiguredFor("prod"),
                        architectureModel.lastSuccessfulImportAt("prod"),
                        site, builds.published(site)));
    }

    /**
     * Imports the model of {@code prod} until the state row says a run of it has succeeded.
     * <p>
     * <b>One import lock is shared by every context of this module</b>, because they share a database - and
     * the catch-up import at a context's startup takes it before any test runs, against stubs that are only
     * set up per test. So the first attempt here can find the lock held and do nothing at all, silently,
     * which then leaves the site unpublishable because it waits for a model that was never imported. The
     * import is idempotent, so the answer is simply to ask again until it has run.
     */
    private void importUntilTheModelIsStored() {
        try {
            // Between two attempts the lock is held by another context, or by this one's own catch-up; it is
            // released when that run ends.
            await().atMost(IMPORT_BUDGET)
                    .pollDelay(java.time.Duration.ZERO)
                    .pollInterval(IMPORT_RETRY_DELAY)
                    .until(this::importOnceMore);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(("The architecture model of prod was not imported within %s, so no "
                                      + "site that requires it can be published.%n  the import of prod: %s")
                    .formatted(IMPORT_BUDGET, imports.state("prod", ArchitectureImportKind.MODEL)));
        }
    }

    /** One attempt of the above: has it already succeeded, and if not, does asking once more make it. */
    private boolean importOnceMore() {
        if (imports.state("prod", ArchitectureImportKind.MODEL).hasEverSucceeded()) {
            return true;
        }
        importJob.importEnvironment("prod");
        return imports.state("prod", ArchitectureImportKind.MODEL).hasEverSucceeded();
    }

    /**
     * Builds the default site once for the whole class.
     * <p>
     * Every read-only test below asks the same questions of the same stubbed landscape, so building per test
     * would run the site generator seven times for one answer - a minute of CPU each, in a module whose other
     * integration tests drive a browser and time out when they are starved of it. The two tests that need a
     * different state say so themselves.
     */
    private void build() throws Exception {
        if (defaultSiteBuilt) {
            return;
        }
        buildUntilServed(Site.DEFAULT_SITE, "/systems/");
        defaultSiteBuilt = true;
    }

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private static void stub(String path, String body) {
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }
}
