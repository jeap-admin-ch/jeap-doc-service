package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.sitegenerator.GeneratorProperties;
import ch.admin.bit.jeap.doc.sitegenerator.SystemPages;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.sitegenerator.BuildWorkspaces;
import ch.admin.bit.jeap.doc.sitegenerator.DocusaurusSiteBuilder;
import ch.admin.bit.jeap.doc.sitegenerator.NodeProcess;
import ch.admin.bit.jeap.doc.sitegenerator.SiteSources;
import ch.admin.bit.jeap.doc.sitegenerator.SiteTemplate;
import ch.admin.bit.jeap.doc.sitegenerator.SiteUrls;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.ColorScheme;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real browser over the documentation site, as it is served by the running service.
 * <p>
 * The site template is a React application, and everything else in this repository asserts the markup a build
 * produced rather than what that application does with it. These tests execute it - and they do so against the
 * service rather than against the build output directly, because the two are only the same until a header gets
 * in the way. The Content-Security-Policy the service sends applies to every path of a site and is what would
 * silently stop the diagrams, the search or the colour mode from working; a suite serving the files itself
 * would send none and be green regardless.
 * <p>
 * <b>Chrome is a precondition of this build</b>, in the way Node and Docker are: the browser comes from the
 * machine, as it does in {@code jeap-error-handling-service}, and the CI image is the one with the browsers in
 * it. It takes both settings below to mean that - the channel says which browser to launch, and the driver
 * would still fetch its own bundles without being told not to. A suite that skipped itself where no browser is
 * present would be green because it ran nothing, on exactly the pipeline that is meant to catch this.
 */
@Slf4j
public abstract class SiteBrowserTestBase extends DocServiceIntegrationTestBase {

    /** A page that exists in every environment, so that switching environment has somewhere to land. */
    protected static final String GUIDE_ROUTE = "guide";

    /** A word that appears on that page and nowhere else, so a search hit can only have come from it. */
    protected static final String SEARCHABLE_TERM = "Streamlined";

    private static final Instant GENERATED_AT = Instant.parse("2026-08-26T10:15:30Z");

    /** What the {@code instance} column of these builds says, so that a row is recognisable in the database. */
    private static final String INSTANCE = "browser-test";

    /**
     * Where the site generator's dependencies are installed by this module's build - the same {@code npm ci}
     * over the same lockfile an instance's image runs. See the pom.
     */
    private static final Path NODE_MODULES = Path.of("target/site-install/node_modules").toAbsolutePath();

    private static Playwright playwright;
    private static Browser browser;

    /**
     * The site is built and uploaded once for the whole test JVM - a Docusaurus build is around three quarters
     * of a minute, and it is read-only for every test here. What is recorded per test is only the row that
     * makes it the current one; see {@link #serveThisSuitesSite()}.
     */
    private static String publishedPrefix;

    /** What the build produced, kept so that the row can be written again without building anything. */
    private static BuiltSite builtSite;

    /** Where that build ran, so that it can be removed once its output has reached the object storage. */
    private static Path workspaceRoot;

    /** Static, because what it guards is - two test classes are two instances sharing these fields. */
    private static final Object BUILDING = new Object();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SiteUrls urls;

    @Autowired
    private DocumentationSites sites;

    @Autowired
    private SitePublicationStorage publication;

    @Autowired
    private DocumentationBuildRepository builds;

    protected BrowserContext context;
    protected Page page;

    /** What the browser logged as an error while the current test ran - including the status of a failed load. */
    protected final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());

    /**
     * Uncaught exceptions of the page itself. Kept apart from the console, because a console error can be the
     * browser reporting a status a test asked for, while an uncaught exception never is.
     */
    protected final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startBrowser() {
        // Nothing is downloaded: the channel below takes the Chrome that is installed, and without this the
        // driver would still fetch its own browser bundles on the first call - hundreds of megabytes on every
        // fresh CI container, and a hard failure where the network does not allow it.
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"));
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    @BeforeEach
    void openPage() {
        serveThisSuitesSite();
        consoleErrors.clear();
        pageErrors.clear();
        // The reader prefers dark. Nothing here is about the palette, but it makes the colour-mode test
        // meaningful - a chosen mode has to outlast a system preference that disagrees with it - and it means
        // every other test runs over the dark tokens rather than never over them.
        context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setColorScheme(ColorScheme.DARK));
        page = context.newPage();
        page.setDefaultTimeout(20_000);
        PlaywrightAssertions.setDefaultAssertionTimeout(15_000);
        page.onConsoleMessage(message -> {
            log.info("Browser console: {}: {}", message.type(), message.text());
            if ("error".equals(message.type())) {
                consoleErrors.add(message.text());
            }
        });
        page.onPageError(error -> {
            log.warn("Browser page error: {}", error);
            pageErrors.add(error);
        });
    }

    @AfterEach
    void closePage() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * The site as the reader reaches it: through the service, not from a directory.
     */
    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Opens a path of the site and waits until the React application has taken over - every assertion here is
     * about what the application does, and the server-rendered markup would answer some of them wrongly.
     *
     * @return what the service answered for the document itself, after any redirect it was sent through
     */
    protected Response open(String path) {
        Response response = page.navigate(url(path));
        page.waitForFunction("() => document.documentElement.dataset.hasHydrated === 'true'");
        return response;
    }

    /**
     * That the browser reported nothing while the test ran. Cheap, and the only thing that would catch a
     * Content-Security-Policy violation, a chunk that failed to load or a React error boundary.
     */
    protected void assertNothingWentWrongInTheBrowser() {
        assertThat(pageErrors).describedAs("what the page threw while the test ran").isEmpty();
        assertThat(consoleErrors).describedAs("what the browser logged while the test ran").isEmpty();
    }

    /**
     * The environments of the default site, as the service is configured for these tests.
     */
    protected List<SiteEnvironment> environments() {
        return defaultSite().environments();
    }

    protected Site defaultSite() {
        return sites.find(Site.DEFAULT_SITE).orElseThrow();
    }

    /**
     * Makes the site this suite generated the one the service serves, building and uploading it the first time.
     * <p>
     * The row is written per test rather than once, because <b>the newest successful build is the published
     * one</b> and the other integration tests of this module publish sites of their own for the same site id.
     * Nothing orders the test classes, so a suite that published once would be driving another test's four
     * fixture files as soon as one of them happened to run in between. Only the row is new - the objects are
     * already in the storage under the same prefix.
     * <p>
     * The service's own {@link SiteUrls} is used to build rather than a second one assembled here: the site
     * carries the base URL it was built for into every asset path it emits, and a copy of that reasoning in the
     * test is how the two drift apart without anything saying so.
     */
    private void serveThisSuitesSite() {
        Site site = defaultSite();
        synchronized (BUILDING) {
            if (publishedPrefix == null) {
                builtSite = buildTheSite(site);
                DocumentationBuild first =
                        builds.start(site.id(), BuildTrigger.SCHEDULE, INSTANCE, Instant.now());
                publishedPrefix = site.id() + "/" + first.id();
                publication.publish(publishedPrefix, builtSite.directory());
                recordAsPublished(first);
                discard(workspaceRoot);
                return;
            }
            recordAsPublished(builds.start(site.id(), BuildTrigger.SCHEDULE, INSTANCE, Instant.now()));
        }
    }

    private void recordAsPublished(DocumentationBuild build) {
        builds.succeeded(build.id(), publishedPrefix, builtSite.pageCount(), builtSite.sizeInBytes(),
                builtSite.docusaurusMillis(), null, Instant.now());
    }

    /**
     * The workspace has been read into the object storage and nothing needs it again. It is tens of megabytes
     * of generated site beside a linked {@code node_modules}, and this suite does not go through the runner
     * that would otherwise sweep it.
     */
    private static void discard(Path root) {
        List<Path> entries;
        // Walked into a list first, so that the directory is not being read while it is being removed. Links
        // are not followed: node_modules is one, and its target is this module's own install.
        try (Stream<Path> walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            log.warn("The build workspace of the browser tests could not be walked: {}", root, e);
            return;
        }
        for (Path path : entries) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("The build workspace of the browser tests could not be removed entirely: {}", path, e);
            }
        }
    }

    /**
     * These tests drive the site template in a browser, not the documentation in it: they build a site with the
     * pages they need and nothing else. What a real build reads from the architecture repository is covered by
     * {@code DocumentationGenerationIT}.
     */
    private SystemPages withoutSystemPages() {
        return new SystemPages(withoutArchitectureModel(), withoutMessageSchemas(), new StructureTemplates(List.of()), new GeneratorProperties(),
                new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, urls);
    }

    /** An instance whose message schemas have never been replicated - these tests are not about them. */
    private static ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository withoutMessageSchemas() {
        return new ch.admin.bit.jeap.doc.domain.port.MessageSchemaRepository() {

            // Throwing, not answering "none": this site configures no architecture repository, so
            // SystemPages.write returns before it asks - and a stub that answers quietly would let a change
            // that made it ask look like a landscape with no schemas rather than like a test to rewrite.
            @Override
            public java.util.List<ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef> findRefs(
                    String environment) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.List<ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas> findAll(
                    String environment, String system) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void store(ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas schemas) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void confirm(String environment, String system, String message, String version,
                                java.time.Instant checkedAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void remove(java.util.Collection<
                    ch.admin.bit.jeap.doc.domain.architecture.imports.MessageVersionRef> versions) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** An instance with no architecture repository configured, which is a legitimate one. */
    private static ArchitectureModelSource withoutArchitectureModel() {
        return new ArchitectureModelSource() {

            @Override
            public boolean isConfiguredFor(String environment) {
                return false;
            }

            @Override
            public Optional<String> sourceUrlOf(String environment) {
                return Optional.empty();
            }

            @Override
            public Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
                return Optional.empty();
            }

            @Override
            public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(String environment) {
                return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot.empty();
            }
        };
    }

    /**
     * The publishable facts, for a suite that builds one real site per JVM. The imports have never run here,
     * which is what a fresh instance looks like, and the page describing the documentation is written all the
     * same - these tests are what prove it renders.
     */
    private static ch.admin.bit.jeap.doc.domain.DocumentationProvenance provenance() {
        return new ch.admin.bit.jeap.doc.domain.DocumentationProvenance(
                new DocumentationSites(new SiteProperties()), new NeverImported(),
                withoutArchitectureModel(),
                new ch.admin.bit.jeap.doc.domain.template.StructureTemplates(java.util.List.of()),
                new BuildProperties(), new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                java.time.Clock.systemDefaultZone());
    }

    /** An instance whose architecture imports have never run. */
    private static final class NeverImported
            implements ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository {

        @Override
        public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState state(
                String environment, ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind kind) {
            return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState.none(environment, kind);
        }

        @Override
        public java.util.List<ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState> states() {
            return java.util.List.of();
        }

        @Override
        public void save(ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState state) {
            // nothing: this suite never imports
        }
    }

    private BuiltSite buildTheSite(Site site) {
        BuildProperties properties = new BuildProperties();
        properties.setNodeModulesDirectory(NODE_MODULES);
        try {
            workspaceRoot = Files.createTempDirectory("jeap-doc-browser-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        properties.setWorkspaceDirectory(workspaceRoot);
        SiteSources sources = new SiteSources(urls, new DefaultResourceLoader(), withoutSystemPages(),
                new DocumentationSites(new SiteProperties()), properties, provenance(),
                new ch.admin.bit.jeap.doc.sitegenerator.AboutThisDocumentation()) {
            @Override
            public Map<String, ch.admin.bit.jeap.doc.sitegenerator.EnvironmentModel> write(
                    long buildId, Site written, Path content, Instant generatedAt) throws IOException {
                Map<String, ch.admin.bit.jeap.doc.sitegenerator.EnvironmentModel> models =
                        super.write(buildId, written, content, generatedAt);
                for (SiteEnvironment environment : written.environments()) {
                    writeGuidePage(content.resolve(environment.id()), environment);
                }
                return models;
            }
        };
        DocusaurusSiteBuilder builder = new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties),
                new SiteTemplate(), new NodeProcess(properties), sources);
        BuiltSite built = builder.generate(1, site, GENERATED_AT);
        // What a run cost, as the runner writes it at the seam between the generator and the upload. Without
        // it the page describing the documentation has nothing to fetch, and these tests are what prove the
        // fetching works at all - under the site's own Content-Security-Policy, in a real browser.
        // Through of(...), which is what DocumentationBuildRunner calls: built positionally, this fixture
        // would keep compiling through a reordering of two same-typed components and quietly change what
        // SiteServingBrowserIT asserts the page shows.
        builder.describeRun(built, ch.admin.bit.jeap.doc.domain.port.DocumentationStatus.of(1L, GENERATED_AT,
                92_000L, built, new ch.admin.bit.jeap.doc.domain.port.ContainerMemory.Peak(
                        11L * 1024 * 1024 * 1024, 16L * 1024 * 1024 * 1024, true)));
        return built;
    }

    /**
     * What the guide page of one environment says about itself.
     * <p>
     * The four copies are otherwise the same page, and the search index is split one bucket per environment -
     * so a test that only counted hits could not tell a correctly scoped search from a search of the wrong
     * tree. This is how a hit names the tree it came out of.
     */
    protected static String guideMarkerOf(SiteEnvironment environment) {
        return "This copy is the one in the %s tree.".formatted(environment.id());
    }

    /**
     * A page in every environment, holding the three things the generated root pages do not: a term that
     * appears nowhere else, so a search hit is unambiguous, a line naming the environment it belongs to, so a
     * hit can be told from its neighbours, and a diagram fence, so that the plugin rendering it has something
     * to render.
     */
    /**
     * The PlantUML the generator writes, in the syntax it writes it.
     * <p>
     * The generator's own tests assert that it emits these constructs; nothing there says PlantUML accepts
     * them. A diagram that does not parse renders as an error box in the reader's browser and the site build
     * does not notice, so the constructs are rendered here for real: the package block of a whitebox view, a
     * box that is bolded and linked at once, the dotted arrow of a REST call, and a label carrying the escaped
     * line break that several messages on one arrow produce.
     */
    private static final String GENERATED_DIAGRAM = """
            @startuml
            left to right direction
            package "orders" {
              component "orders-intake" as c_orders_intake [[/guide/]]
              component "orders-risk" as c_orders_risk [[/guide/]]
            }
            component "shipping" as c_shipping [[/guide/]] #line.bold
            c_orders_intake --> c_orders_risk : OrdersPaymentAcceptedEvent\\nOrdersPaymentRejectedEvent
            c_orders_intake ..> c_shipping : GET /api/shipments
            @enduml""";

    private static void writeGuidePage(Path environmentTree, SiteEnvironment environment) throws IOException {
        Files.writeString(environmentTree.resolve(GUIDE_ROUTE + ".md"), """
                # The upload guide

                %s documentation reaches the doc service through the upload API of its pipeline.

                %s

                ```plantuml
                %s
                ```
                """.formatted(SEARCHABLE_TERM, guideMarkerOf(environment), GENERATED_DIAGRAM),
                StandardCharsets.UTF_8);
    }
}
