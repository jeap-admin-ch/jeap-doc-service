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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
 * silently stop the diagrams or the colour mode from working; a suite serving the files itself
 * would send none and be green regardless.
 * <p>
 * <p>
 * The site is a fixture: it is built once for the whole test JVM, with the pages these tests need and no
 * architecture model behind it. A landscape that changes is {@link LandscapeChangeBrowserIT}.
 */
@Slf4j
public abstract class SiteBrowserTestBase extends BrowserTestBase {

    /** A page that exists in every environment, so that switching environment has somewhere to land. */
    protected static final String GUIDE_ROUTE = "guide";

    private static final Instant GENERATED_AT = Instant.parse("2026-08-26T10:15:30Z");

    /** What the {@code instance} column of these builds says, so that a row is recognisable in the database. */
    private static final String INSTANCE = "browser-test";

    /**
     * Where the site generator's dependencies are installed by this module's build - the same {@code npm ci}
     * over the same lockfile an instance's image runs. See the pom.
     */
    private static final Path NODE_MODULES = Path.of("target/site-install/node_modules").toAbsolutePath();

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

    @Autowired
    private SiteUrls urls;

    @Autowired
    private DocumentationSites sites;

    @Autowired
    private SitePublicationStorage publication;

    @Autowired
    private DocumentationBuildRepository builds;

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
    @Override
    protected void prepareWhatIsServed() {
        serveThisSuitesSite();
    }

    private void serveThisSuitesSite() {
        Site site = defaultSite();
        synchronized (BUILDING) {
            if (publishedPrefix == null) {
                builtSite = buildTheSite(site);
                DocumentationBuild first = builds.start(ch.admin.bit.jeap.doc.domain.PartKey.shellOf(site.id()),
                        BuildTrigger.IMPORT, INSTANCE, Instant.now(), null);
                publishedPrefix = site.id() + "/" + first.id();
                publication.publish(new ch.admin.bit.jeap.doc.domain.port.PartPublication(publishedPrefix,
                        ch.admin.bit.jeap.doc.domain.SharedAssets.prefixOf(site.id())),
                        builtSite.directory());
                recordAsPublished(first);
                discard(workspaceRoot);
                return;
            }
            recordAsPublished(builds.start(ch.admin.bit.jeap.doc.domain.PartKey.shellOf(site.id()),
                    BuildTrigger.IMPORT, INSTANCE, Instant.now(), null));
        }
    }

    private void recordAsPublished(DocumentationBuild build) {
        builds.succeeded(build.id(), publishedPrefix, builtSite.pageCount(), builtSite.sizeInBytes(),
                builtSite.docusaurusMillis(), "digest-of-the-suites-site", Instant.now());
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
        return new SystemPages(withoutArchitectureModel(), withoutMessageSchemas(), withoutArtifacts(),
                withoutArtifacts(), new StructureTemplates(List.of()), new GeneratorProperties(),
                new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, urls);
    }

    /**
     * An instance whose artifacts have never been replicated. Throwing rather than answering "none", like
     * the message schemas next to it: this site configures no architecture repository, so nothing asks, and a
     * change that made it ask should fail here rather than look like a landscape without artifacts.
     */
    private static Artifacts withoutArtifacts() {
        return new Artifacts();
    }

    /** Both halves of the artifact reading, neither of which this suite reaches. */
    private static final class Artifacts
            implements ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository,
            ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent {

        @Override
        public java.util.List<ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef>
                findRefs(String environment,
                         ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact> find(
                String environment,
                ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind kind, String system,
                String component) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void store(ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact artifact) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirm(String environment,
                            ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind kind,
                            String system, String component, java.time.Instant checkedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(java.util.Collection<
                ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef> artifacts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int removeOrphans(String environment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema> databaseSchema(
                ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact artifact) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview> restApi(
                ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact artifact) {
            throw new UnsupportedOperationException();
        }
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

            @Override
            public java.util.List<String> systemSlugsOf(String environment) {
                return java.util.List.of();
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
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL), properties, provenance(),
                new ch.admin.bit.jeap.doc.sitegenerator.AboutThisDocumentation()) {
            @Override
            public ch.admin.bit.jeap.doc.sitegenerator.WrittenContent write(
                    long buildId, Site written, ch.admin.bit.jeap.doc.domain.SitePart part, Path content,
                    Instant generatedAt) throws IOException {
                ch.admin.bit.jeap.doc.sitegenerator.WrittenContent sources =
                        super.write(buildId, written, part, content, generatedAt);
                for (SiteEnvironment environment : written.environments()) {
                    writeGuidePage(content.resolve(environment.id()), environment);
                }
                return sources;
            }
        };
        DocusaurusSiteBuilder builder = new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties),
                new SiteTemplate(), new NodeProcess(properties), sources);
        // One part carrying the whole site: this suite is about what the template does in a browser, and
        // that is the same whichever part a page came out of.
        ch.admin.bit.jeap.doc.domain.SitePart whole = new ch.admin.bit.jeap.doc.domain.SitePart(
                ch.admin.bit.jeap.doc.domain.PartKey.shellOf(site.id()), "the site itself", "", true,
                site.environments().stream().map(SiteEnvironment::id).toList(), java.util.List.of());
        BuiltSite built = builder.generate(builder.prepare(1, site, whole, GENERATED_AT));
        // What a run cost, as the runner writes it at the seam between the generator and the upload. Without
        // it the page describing the documentation has nothing to fetch, and these tests are what prove the
        // fetching works at all - under the site's own Content-Security-Policy, in a real browser.
        // Through of(...), which is what DocumentationBuildRunner calls: built positionally, this fixture
        // would keep compiling through a reordering of two same-typed components and quietly change what
        // SiteServingBrowserIT asserts the page shows.
        builder.describeRun(built, ch.admin.bit.jeap.doc.domain.port.DocumentationStatus.of(1L, GENERATED_AT,
                92_000L, built));
        return built;
    }

    /**
     * What the guide page of one environment says about itself.
     * <p>
     * The four copies are otherwise the same page, so a test that landed on the wrong one could not tell. This
     * is how a page names the tree it came out of.
     */
    protected static String guideMarkerOf(SiteEnvironment environment) {
        return "This copy is the one in the %s tree.".formatted(environment.id());
    }

    /**
     * A page in every environment, holding the two things the generated root pages do not: a line naming the
     * environment it belongs to, so one copy can be told from its neighbours, and a diagram fence, so that the
     * plugin rendering it has something to render.
     */
    /**
     * The PlantUML the generator writes, in the syntax it writes it.
     * <p>
     * The generator's own tests assert that it emits these constructs; nothing there says PlantUML accepts
     * them. A diagram that does not parse renders as an error box in the reader's browser and the site build
     * does not notice, so the constructs are rendered here for real: the package block of a whitebox view, a
     * box that is bolded and linked at once, the dotted arrow of a REST call, and a label carrying the escaped
     * line break that several messages on one arrow produce.
     * <p>
     * <b>One box links somewhere else than the page it is on.</b> A link inside a fence is the one kind the
     * generator does not rewrite - it is written absolute already - so nothing but a browser says whether it
     * is followed at all, or followed into a new tab. {@code c_shipping} points at the site root so that
     * following it is observable; the other two point at the page itself.
     */
    private static final String GENERATED_DIAGRAM = """
            @startuml
            left to right direction
            package "orders" {
              component "orders-intake" as c_orders_intake [[/guide/]]
              component "orders-risk" as c_orders_risk [[/guide/]]
            }
            component "shipping" as c_shipping [[/]] #line.bold
            c_orders_intake --> c_orders_risk : OrdersPaymentAcceptedEvent\\nOrdersPaymentRejectedEvent
            c_orders_intake ..> c_shipping : GET /api/shipments
            @enduml""";

    /**
     * The entity relationship diagram the generator writes, in the syntax it writes it.
     * <p>
     * Different constructs from the one above: entities rather than components, a separator inside a box,
     * stereotype markers and a crow's-foot arrow. PlantUML draws a syntax error as a picture too, so only
     * rendering these in a browser says whether they parse.
     * <p>
     * <b>A parameterised type and an array type sit between two plain columns on purpose.</b> PlantUML tells
     * a field from a method by whether the line has parentheses, which those two types have - so if that
     * heuristic applied to an entity's members they would be drawn in a compartment of their own, after
     * {@code remark}. It does not: every member of an entity is a field, drawn where it was declared, and
     * the assertion in {@code SiteTemplateBrowserIT} is what keeps that from changing under us.
     */
    private static final String GENERATED_SCHEMA_DIAGRAM = """
            @startuml
            skinparam nodesep 8
            skinparam ranksep 20
            entity "orders_order" {
              * id : uuid <<PK>>
              --
                party_id : uuid <<FK>>
              * total : numeric(12,2)
                tags : text[]
                remark : text
            }
            entity "orders_party" {
              * id : uuid <<PK>>
            }
            "orders_order" }o--|| "orders_party" : party_id
            @enduml""";

    private static void writeGuidePage(Path environmentTree, SiteEnvironment environment) throws IOException {
        Files.writeString(environmentTree.resolve(GUIDE_ROUTE + ".md"), """
                # The upload guide

                Documentation reaches the doc service through the upload API of its pipeline.

                %s

                ```plantuml
                %s
                ```

                ```plantuml
                %s
                ```
                """.formatted(guideMarkerOf(environment), GENERATED_DIAGRAM, GENERATED_SCHEMA_DIAGRAM), StandardCharsets.UTF_8);
    }

    /**
     * A model source that knows no system, so a site has one part: its shell. What the parts of a site are is
     * SystemSitePartitionTest's business; here they only have to exist.
     */
    private static final ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource NO_MODEL =
            new ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource() {

                @Override
                public boolean isConfiguredFor(String environment) {
                    return false;
                }

                @Override
                public java.util.Optional<String> sourceUrlOf(String environment) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
                    return java.util.Optional.empty();
                }

                @Override
                public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(
                        String environment) {
                    return ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot.empty();
                }

                @Override
                public java.util.List<String> systemSlugsOf(String environment) {
                    return java.util.List.of();
                }
            };
}
