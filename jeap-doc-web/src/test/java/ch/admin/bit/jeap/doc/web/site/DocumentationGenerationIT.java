package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildRunner;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationParts;
import ch.admin.bit.jeap.doc.domain.PartKey;
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

import java.util.List;
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
    /**
     * How many ticks a site is given to be served. It is per <b>part</b>: a tick builds at most one part, a
     * site of a shell and two systems is three of them, and the classes of this module share a database - so a
     * tick may serve another class's request before it reaches this one.
     */
    private static final int ROUNDS_UNTIL_SERVED = 12;

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

    @org.springframework.beans.factory.annotation.Autowired
    private DocumentationParts parts;

    @org.springframework.beans.factory.annotation.Autowired
    private ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository requests;

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
        // The two artifact indexes, each offering the one artifact of orders-intake, and the content
        // resources they point at. This is the only test that runs the whole path of an artifact: index,
        // conditional fetch, storage, join, render, serve.
        stub("/docs-api/openapi-specs", """
                {"artifacts": [
                  {"system": "orders", "component": "orders-intake", "version": "2.4.0",
                   "etag": "\\"sha256:spec\\"", "lastModifiedAt": "2026-08-12T05:31:00Z",
                   "contentUrl": "/docs-api/systems/orders/components/orders-intake/openapi"}
                ]}""");
        stub("/docs-api/database-schemas", """
                {"artifacts": [
                  {"system": "orders", "component": "orders-intake", "version": "1.2.3",
                   "etag": "\\"sha256:schema\\"", "lastModifiedAt": "2026-08-12T05:31:00Z",
                   "contentUrl": "/docs-api/systems/orders/components/orders-intake/database-schema"}
                ]}""");
        // With the entity tag the index announced. What is stored is addressed by it, and an artifact
        // arriving without one is not replicated at all.
        tagged("/docs-api/systems/orders/components/orders-intake/openapi", "\"sha256:spec\"", OPENAPI_SPEC);
        tagged("/docs-api/systems/orders/components/orders-intake/database-schema", "\"sha256:schema\"",
                DATABASE_SCHEMA);
        stub("/docs-api/systems", """
                {"systems": [
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"},
                  {"name": "tariffs", "description": "Knows what things cost"}
                ]}""");
        // Two components: one the architecture repository knows everything about, and one it knows only the
        // name of.
        stub("/docs-api/systems/orders", """
                {"name": "orders", "description": "Takes orders and follows them through",
                 "team": {"name": "Team Blue", "contactAddress": "blue@example.com"},
                 "components": [
                   {"name": "orders-intake", "description": "Takes payments in",
                    "type": "BACKEND_SERVICE", "importer": "DEPLOYMENT_LOG",
                    "restApis": [{"method": "GET", "path": "/api/orders"}],
                    "openApi": {"version": "2.4.0", "serverUrl": "https://orders.example.ch/api",
                                "contentUrl": "/docs-api/systems/orders/components/orders-intake/openapi",
                                "swaggerUrl": "https://archrepo.example.com/swagger-ui/index.html"},
                    "databaseSchema": {"schemaVersion": "1.2.3",
                                       "contentUrl": "/docs-api/systems/orders/components/orders-intake/database-schema"}},
                   {"name": "orders-quiet", "type": "BACKEND_SERVICE", "importer": "DEPLOYMENT_LOG"}
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
        // A system that exchanges nothing with anything: it is what says that a landscape change is not
        // republished across the whole site - see the import tests below.
        stub("/docs-api/systems/tariffs", """
                {"name": "tariffs", "description": "Knows what things cost",
                 "components": [{"name": "tariffs-table", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        stub("/docs-api/systems/tariffs/messages", "{\"messages\": []}");
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
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/                                    | Component Architecture
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/intro/                              | Introduction and Goals
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/context-and-scope/                  | Context and Scope
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/context-and-scope/context-view/     | Component Context View
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/building-block-view/                | Building Block View
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/building-block-view/database-schema/| Database Schema
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/building-block-view/rest-api/       | REST API
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/building-block-view/messages/       | Messages
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/runtime-view/                       | Runtime View
            /systems/orders/system-architecture/building-block-view/components/orders-intake/component-architecture/runtime-view/component-reactions/   | Component Reactions
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

    /** Where a component's own tree hangs, and the link on its page that a reader follows to reach it. */
    private static final String COMPONENT_TREE =
            "/systems/orders/system-architecture/building-block-view/components/orders-intake/"
            + "component-architecture/";

    /**
     * The component's page links into its own tree. The link is written only because the subtree exists; one
     * to a page nothing wrote would have failed this build instead of serving it.
     */
    @Test
    void theComponentPageLinksIntoItsOwnDocumentation() throws Exception {
        build();

        assertThat(page("/systems/orders/system-architecture/building-block-view/components/orders-intake/"))
                .contains("Component Architecture")
                .contains(COMPONENT_TREE);
    }

    /**
     * The entity relationship diagram of a really replicated schema. A fence that reaches the page but not
     * the plugin renders as text and the build is green either way, so only a fetch of the built page shows
     * which happened.
     */
    @Test
    void theDatabaseSchemaIsReplicatedAndRenderedAsADiagram() throws Exception {
        build();

        String view = page(COMPONENT_TREE + "building-block-view/database-schema/");
        assertThat(view).containsPattern("data-plantuml-diagram=\"?plantuml");
        // Only .png: the site's own logo is an .svg on every page. That the diagram figure itself carries no
        // image at all is asserted in DocusaurusSiteBuilderIT, where the figure is.
        assertThat(view).doesNotContain(".png");
        assertThat(view).describedAs("the schema the architecture repository served, not a placeholder")
                .contains("orders_order")
                .contains("orders_party")
                .contains("1.2.3");
        assertThat(view).describedAs("and the machinery of the schema is named rather than silently dropped")
                .contains("flyway_schema_history")
                .doesNotContain("%d");
    }

    /** The component's own context diagram, which is drawn from different data than the system's two. */
    @Test
    void theComponentContextDiagramIsRenderedByThePluginRatherThanAsAnImage() throws Exception {
        build();

        String view = page(COMPONENT_TREE + "context-and-scope/context-view/");
        assertThat(view).containsPattern("data-plantuml-diagram=\"?plantuml");
        assertThat(view).doesNotContain(".png");
        assertThat(view).contains("orders-intake").contains("shipping");
    }

    /**
     * The REST API page is an overview and a link, not a rendered specification: a table per group, and the
     * deep link into the architecture repository's own Swagger UI.
     */
    @Test
    void theRestApiOverviewIsGroupedByTagAndLinksToTheSwaggerUi() throws Exception {
        build();

        String view = page(COMPONENT_TREE + "building-block-view/rest-api/");
        assertThat(view).contains("Orders").contains("Everything about an order")
                .contains("/api/orders").contains("List the orders");
        assertThat(view).describedAs("a tag the specification declares and nothing uses is not a group")
                .doesNotContain("Nobody uses this tag");
        assertThat(view).contains("https://archrepo.example.com/swagger-ui/index.html");
    }

    /**
     * A message is documented once, below the system that defines it. The component's page links to it
     * instead of repeating it.
     */
    @Test
    void theComponentsMessagesLinkIntoTheSystemsOwnMessagePages() throws Exception {
        build();

        assertThat(page(COMPONENT_TREE + "building-block-view/messages/"))
                .contains("OrdersPaymentAcceptedEvent")
                .contains("/systems/orders/system-architecture/building-block-view/events/"
                          + "orders-payment-accepted-event/");
    }

    /**
     * A component the architecture repository knows only the name of gets three chapters and no empty
     * folder.
     */
    @Test
    void aComponentWithNothingToDecomposeHasNoBuildingBlockView() throws Exception {
        build();

        String quiet = "/systems/orders/system-architecture/building-block-view/components/orders-quiet/"
                       + "component-architecture/";
        assertThat(page(quiet)).contains("Component Architecture");
        assertThat(page(quiet + "context-and-scope/context-view/"))
                .describedAs("a component that exchanges nothing says so rather than drawing an empty box")
                .contains("records no relation");
        mockMvc.perform(get(quiet + "building-block-view/")).andExpect(status().isNotFound());
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
     * <b>The import is a trigger, and it asks for the whole site.</b>
     * <p>
     * Which of its systems moved is not asked: a part is one system, and a part whose content has not moved is
     * not generated - so what an import costs is the content of every part rather than a site rebuilt. This is
     * the trigger half of that; that an unchanged part is skipped is asserted where a build really runs.
     */
    @Test
    void whenTheLandscapeChanges_thenEveryPartOfTheSiteIsAskedFor() throws Exception {
        build();
        landscapeIsUpToDate();

        // A component appears in tariffs, which exchanges nothing with anything - and the whole site is asked
        // for all the same.
        stub("/docs-api/systems/tariffs", """
                {"name": "tariffs", "description": "Knows what things cost",
                 "components": [{"name": "tariffs-table", "type": "BACKEND_SERVICE"},
                                {"name": "tariffs-import", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        importJob.importEnvironment("prod");

        assertThat(owedParts())
                .containsExactlyInAnyOrder("shell", "system-orders", "system-shipping", "system-tariffs");
    }

    /**
     * A landscape that gains a system asks for that system's part too - it did not exist before the import,
     * and the parts of a site are read from the landscape it stored.
     */
    @Test
    void whenTheLandscapeGainsASystem_thenItsPartIsAskedForAsWell() throws Exception {
        build();
        landscapeIsUpToDate();

        stub("/docs-api/systems", """
                {"systems": [
                  {"name": "orders", "description": "Takes orders and follows them through",
                   "team": {"name": "Team Blue", "contactAddress": "blue@example.com"}},
                  {"name": "shipping", "description": "Sends the goods out"},
                  {"name": "tariffs", "description": "Knows what things cost"},
                  {"name": "returns", "description": "Takes the goods back"}
                ]}""");
        stub("/docs-api/systems/returns", """
                {"name": "returns", "description": "Takes the goods back",
                 "components": [{"name": "returns-desk", "type": "BACKEND_SERVICE"}],
                 "relations": []}""");
        stub("/docs-api/systems/returns/messages", "{\"messages\": []}");
        importJob.importEnvironment("prod");

        assertThat(owedParts()).contains("system-returns");
    }

    /**
     * An import that finds the landscape it already had asks for nothing at all. It is what keeps a site from
     * being rebuilt hourly for no reason, which is what publishing it in parts is for.
     */
    @Test
    void whenTheImportedLandscapeIsUnchanged_thenNothingIsAskedFor() throws Exception {
        build();
        landscapeIsUpToDate();

        importJob.importEnvironment("prod");

        assertThat(owedParts()).isEmpty();
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
        long publishedBefore = publishedShell().map(DocumentationBuild::id).orElse(-1L);
        tickUntilTheSiteIsPublishedAgain();

        // The newest build of any state, not builds.published(...) - that one *is* "the newest SUCCEEDED
        // build", so asserting it succeeded says nothing at all, on the one test that is about a build not
        // failing. A build that failed would leave the older publication in place and go unnoticed here.
        assertThat(builds.recentOf(PartKey.shellOf(Site.DEFAULT_SITE), 1)).singleElement()
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
        long before = publishedShell().map(DocumentationBuild::id).orElse(-1L);
        for (int tick = 0; tick < 5; tick++) {
            trigger.requestBecauseAnOperatorAsked(PartKey.shellOf(Site.DEFAULT_SITE));
            runner.runOnce();
            Optional<DocumentationBuild> published = publishedShell();
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
            // Every part, because a site is published as several builds now: the shell carries the site's own
            // pages and one part carries each system, and a tick builds at most one of them.
            trigger.requestEveryPart(site);
            runner.runOnce();
            status = mockMvc.perform(get(probe)).andReturn().getResponse().getStatus();
            if (status == 200 && everyPartIsPublished(site)) {
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
                        site, builds.publishedPartsOf(site)));
    }

    /**
     * Whether every part of the site has a publication. A site whose shell is published answers its front page
     * while a system's part is still owed a build, so the probe alone is not enough to say the documentation is
     * there.
     * <p>
     * <b>Asked of the parts this context has, and not by counting published rows.</b> The test classes of
     * this module share a database, and one of them writes a succeeded build row for a part of a site this
     * context configures without it - so a count of rows never equals the number of parts, and the loop above
     * spent its twelve rounds and failed with everything actually in place.
     */
    private boolean everyPartIsPublished(String site) {
        List<DocumentationParts.PartState> expected = parts.of(site).orElseThrow();
        return !expected.isEmpty() && expected.stream().allMatch(part -> part.published() != null);
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

    /** What is published for the site's shell part - its front page, its systems index, its own pages. */
    private Optional<DocumentationBuild> publishedShell() {
        return builds.published(PartKey.shellOf(Site.DEFAULT_SITE));
    }

    /** Which parts of the default site are owed a build right now. */
    private java.util.List<String> owedParts() {
        return requests.pending().stream()
                .filter(request -> request.site().equals(Site.DEFAULT_SITE))
                .map(request -> request.part().part())
                .toList();
    }

    /**
     * Imports whatever the architecture repository answers now, and forgets what that asked for.
     * <p>
     * The cases above then change one system and import again, so what is owed afterwards is that change
     * alone - whichever order the cases run in, and whatever the case before it left in the stubs.
     */
    private void landscapeIsUpToDate() {
        importJob.importEnvironment("prod");
        drainRequests();
    }

    /**
     * Takes whatever is pending, so that what a case asks for afterwards is what that case set off. The
     * classes of this module share a database, and a request another one left behind would read as this one's.
     */
    private void drainRequests() {
        requests.pending().forEach(request -> requests.claim(request.part()));
    }

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    /**
     * The specification of {@code orders-intake}, as a build pushed it and the architecture repository serves
     * it. Two tags, one of which nothing uses, and a deprecated operation - so that what the page does with
     * each of them is visible on a real build.
     */
    private static final String OPENAPI_SPEC = """
            { "openapi": "3.0.1",
              "info": { "title": "Orders API", "version": "2.4.0" },
              "servers": [ { "url": "https://orders.example.ch/api" } ],
              "tags": [ { "name": "Orders", "description": "Everything about an order" },
                        { "name": "Reports", "description": "Nobody uses this tag" } ],
              "paths": {
                "/api/orders": { "get": { "summary": "List the orders", "tags": ["Orders"] } },
                "/api/orders/{id}": { "get": { "summary": "One order", "tags": ["Orders"],
                                               "deprecated": true } },
                "/api/health": { "get": { "summary": "Is it up" } }
              } }""";

    /** And its database schema, including the two machinery tables that are documented nowhere. */
    private static final String DATABASE_SCHEMA = """
            { "name": "orders_db", "version": "1.2.3",
              "tables": [
                { "name": "orders_order",
                  "columns": [ { "name": "id", "type": "uuid", "nullable": false },
                               { "name": "party_id", "type": "uuid", "nullable": true } ],
                  "primaryKey": { "name": "pk_orders_order", "columnNames": ["id"] },
                  "foreignKeys": [ { "name": "fk_order_party", "columnNames": ["party_id"],
                                     "referencedTableName": "orders_party",
                                     "referencedColumnNames": ["id"] } ] },
                { "name": "orders_party",
                  "columns": [ { "name": "id", "type": "uuid", "nullable": false } ],
                  "primaryKey": { "name": "pk_orders_party", "columnNames": ["id"] } },
                { "name": "flyway_schema_history",
                  "columns": [ { "name": "installed_rank", "type": "integer", "nullable": false } ] }
              ] }""";

    /** A content resource with the entity tag its index announced, which is what makes it replicable. */
    private static void tagged(String path, String etag, String body) {
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("ETag", etag).withBody(body)));
    }

    private static void stub(String path, String body) {
        ARCH_REPO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }
}
