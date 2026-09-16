package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import ch.admin.bit.jeap.doc.template.arc42.Arc42Template;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;
import ch.admin.bit.jeap.doc.sitegenerator.GeneratorProperties;
import ch.admin.bit.jeap.doc.sitegenerator.SystemPages;
import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SitePartition;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.sitegenerator.AboutThisDocumentation;
import ch.admin.bit.jeap.doc.sitegenerator.BuildWorkspaces;
import ch.admin.bit.jeap.doc.sitegenerator.DocusaurusSiteBuilder;
import ch.admin.bit.jeap.doc.sitegenerator.NodeProcess;
import ch.admin.bit.jeap.doc.sitegenerator.SiteSources;
import ch.admin.bit.jeap.doc.sitegenerator.SiteTemplate;
import ch.admin.bit.jeap.doc.sitegenerator.SiteUrls;
import ch.admin.bit.jeap.doc.sitegenerator.WrittenContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;


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

    /**
     * A page in the shape a generated site really has: inside a system, inside a <b>numbered</b> chapter
     * folder, inside a component's own tree.
     * <p>
     * <b>The numbers are the point.</b> Docusaurus serves a folder called {@code 5-building-block-view} at
     * {@code building-block-view}, so a page's route is not its path - and a search index that took one for
     * the other offered a dead link for every generated chapter of a real site while every test here stayed
     * green, because this fixture had no numbered folder in it.
     */
    protected static final String SYSTEM = "orders";
    protected static final String COMPONENT = "orders-intake";
    /** Where that page is written, below an environment tree. */
    protected static final String COMPONENT_PAGE_PATH =
            "systems/" + SYSTEM + "/system-architecture/5-building-block-view/components/" + COMPONENT
            + "/component-architecture/6-runtime-view";
    /** And where the site serves it: every number gone. */
    protected static final String COMPONENT_PAGE_ROUTE =
            "systems/" + SYSTEM + "/system-architecture/building-block-view/components/" + COMPONENT
            + "/component-architecture/runtime-view";
    /** A word that occurs on that page and nowhere else, so a search for it can only find it. */
    protected static final String COMPONENT_PAGE_WORD = "backpressure";

    /**
     * The system whose runtime views this suite draws - a second one, so that the hand-written page above and
     * the generated tree below never write the same file.
     * <p>
     * <b>These pages are generated by the real structure template</b>, from a model and a reaction graph,
     * rather than written by hand like the one above. What {@link ReactionGraphBrowserIT} drives is a diagram
     * the plugin renders from the DOT the generator wrote, and a fence a test wrote itself would prove nothing
     * about the generator.
     */
    protected static final String REACTING_SYSTEM = "shipping";
    protected static final String REACTING_COMPONENT = "shipping-dispatch";
    protected static final String REACTING_MESSAGE = "orders-payment-accepted-event";

    /** The three routes the runtime views are served at, below an environment tree. */
    protected static final String SYSTEM_REACTIONS_ROUTE =
            "systems/" + REACTING_SYSTEM + "/system-architecture/runtime-view/system-reactions";
    protected static final String COMPONENT_REACTIONS_ROUTE =
            "systems/" + REACTING_SYSTEM + "/system-architecture/building-block-view/components/"
            + REACTING_COMPONENT + "/component-architecture/runtime-view/component-reactions";
    protected static final String MESSAGE_REACTIONS_ROUTE =
            "systems/" + REACTING_SYSTEM + "/system-architecture/building-block-view/events/"
            + REACTING_MESSAGE;

    /** The observer's id of the reaction these pages draw, which is what a deep link addresses. */
    protected static final long REACTION_ID = 4242;

    /** A component that reacts to one message three times, which is what a cluster is drawn for. */
    protected static final String BUSY_COMPONENT = "shipping-label-printer";

    /** A system whose message this one answers - drawn as another system's, and linked, because it has pages. */
    protected static final String OTHER_SYSTEM = "billing";

    /** And its component, which reacts to a message of the first system - so the two graphs point at each other. */
    protected static final String OTHER_COMPONENT = "billing-settlement";

    /** The reaction of that component, which a link from the other system's graph focuses. */
    protected static final long OTHER_REACTION_ID = 5150;

    /** The message the second system publishes, whose page draws what answered it. */
    protected static final String OTHER_MESSAGE = "billing-settled-event";

    /** A message no landscape documents: drawn, and deliberately not a link. */
    protected static final String UNDOCUMENTED_MESSAGE = "LegacyWarehouseSyncEvent";

    /** A component this landscape does not document, for the same reason. */
    protected static final String UNDOCUMENTED_COMPONENT = "warehouse-legacy-worker";

    /** The variant of the message type whose page draws two diagrams. */
    protected static final String VARIANT = "express";

    /**
     * The observer's id of that variant's message node. A link into a message page addresses the node of the
     * variant it came from, and the variant's diagram is not the first one on the page - so this is the id
     * that says whether the link landed on the right of two.
     */
    protected static final long VARIANT_MESSAGE_ID = 9;

    /** How many further reactions the system's graph carries, so that it is one nobody reads at a glance. */
    protected static final int WIDE_REACTIONS = 30;

    protected static final Instant GENERATED_AT = Instant.parse("2026-08-26T10:15:30Z");

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

    /**
     * The class the prefix above was published for. The hook this suite overrides runs before every
     * <b>test</b>, and the copy is the whole built site - so without this the fixture would be published
     * again per test method rather than per class, for a risk that only exists between classes.
     */
    private static Class<?> publishedFor;

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
     * How the site is cut into parts - asked rather than assumed, because what it answers depends on the
     * architecture model in the database this module's classes share. See
     * {@link #serveEveryPartOfItFromThisBuildToo}.
     */
    @Autowired
    private SitePartition partition;

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
     * The rows are written per test rather than once, because <b>the newest successful build is the published
     * one</b> and the other integration tests of this module publish sites of their own for the same site id.
     * Nothing orders the test classes, so a suite that published once would be driving another test's four
     * fixture files as soon as one of them happened to run in between.
     * <p>
     * <b>And the objects with them, once per class.</b> Pointing new rows at the prefix this suite published
     * once is only safe while that prefix is still there - and it is not: a build of this site by any other
     * class supersedes it, and the runner removes what it superseded. The site is generated once, which is
     * what costs the minute; publishing it again for each class costs a copy, and no more than that -
     * {@link #prepareWhatIsServed()} is called before every test.
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
            if (builtSite == null) {
                builtSite = buildTheSite(site);
            }
            if (getClass().equals(publishedFor)) {
                // Already this class's own publication, and nothing between two of its tests can have taken
                // it: the classes of this module run one after another.
                return;
            }
            // The objects too, and not only the rows. A build of this site by another class - the runner
            // picks up every part that is owed one, whichever class asked - publishes a newer prefix and
            // removes the superseded one, which is this fixture: 'Removed the published site under
            // default/284 (263 files)'. So each class publishes the site it built again, under its own
            // prefix, and nothing it drives can be served by a prefix somebody else has taken away.
            DocumentationBuild own = builds.start(ch.admin.bit.jeap.doc.domain.PartKey.shellOf(site.id()),
                    BuildTrigger.IMPORT, INSTANCE, Instant.now(), null);
            publishedPrefix = site.id() + "/" + own.id();
            publication.publish(new ch.admin.bit.jeap.doc.domain.port.PartPublication(publishedPrefix,
                    ch.admin.bit.jeap.doc.domain.SharedAssets.prefixOf(site.id())),
                    builtSite.directory());
            recordAsPublished(own);
            serveEveryPartOfItFromThisBuildToo(site);
            publishedFor = getClass();
        }
    }

    /**
     * <b>One build, and it has to answer for every part of the site.</b> This suite builds the whole site as a
     * single part and publishes it as the shell - and the shell only answers for what no other part claims. A
     * part per system is claimed as soon as the architecture model of any environment knows that system, and
     * the model lives in the database the classes of this module share: a class that imported one leaves a
     * {@code system-orders} part behind whose own publication then answers every {@code /systems/orders/}
     * path of this fixture. Nothing fails - the other publication is a real site - so what a test drives is
     * quietly somebody else's page, which is how a suite goes green on a page it did not write.
     * <p>
     * So this build is recorded as the publication of every part there is: the parts the partition cuts the
     * site into now, and the ones something has published for it, which are not always the same set. All of
     * them point at the one prefix, so whichever part owns a path, the file served is this suite's.
     */
    private void serveEveryPartOfItFromThisBuildToo(Site site) {
        for (String part : otherPartsOf(site)) {
            recordAsPublished(builds.start(ch.admin.bit.jeap.doc.domain.PartKey.of(site.id(), part),
                    BuildTrigger.IMPORT, INSTANCE, Instant.now(), null));
        }
    }

    /** Every part of the site but the shell, which is published above. */
    private SortedSet<String> otherPartsOf(Site site) {
        SortedSet<String> parts = new TreeSet<>();
        partition.partsOf(site).stream().map(SitePart::id).forEach(parts::add);
        builds.publishedPartsOf(site.id()).stream().map(PublishedPart::part).forEach(parts::add);
        parts.remove(SitePart.SHELL);
        return parts;
    }

    private void recordAsPublished(DocumentationBuild build) {
        builds.succeeded(build.id(), publishedPrefix, builtSite.pageCount(), builtSite.sizeInBytes(),
                builtSite.docusaurusMillis(), "digest-of-the-suites-site", Instant.now());
    }


    /**
     * What this suite's fixture site is made of: the pages the generator writes, plus a guide page in every
     * environment tree that says which tree it is.
     * <p>
     * Shared with {@link SiteSearchBrowserIT}, which builds a search index over the same content. An index
     * over a fixture that merely looked like the site would prove nothing about the site.
     */
    protected SiteSources fixtureSources(SiteUrls urls, BuildProperties properties) {
        return new SiteSources(urls, new DefaultResourceLoader(), withoutSystemPages(),
                new DocumentationSites(new SiteProperties()),
                new SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), properties, provenance(),
                new AboutThisDocumentation()) {
            @Override
            public WrittenContent write(long buildId, Site written, SitePart part, Path content,
                                        Instant generatedAt) throws IOException {
                WrittenContent sources = super.write(buildId, written, part, content, generatedAt);
                for (SiteEnvironment environment : written.environments()) {
                    writeGuidePage(content.resolve(environment.id()), environment);
                    writeFoldPage(content.resolve(environment.id()));
                    writeTablesPage(content.resolve(environment.id()));
                    writeComponentPage(content.resolve(environment.id()));
                    writeReactingSystem(content.resolve(environment.id()), environment);
                    writeDocumentedSystem(content.resolve(environment.id()), environment);
                }
                return sources;
            }
        };
    }

    /**
     * These tests drive the site template in a browser, not the documentation in it: they build a site with the
     * pages they need and nothing else. What a real build reads from the architecture repository is covered by
     * {@code DocumentationGenerationIT}.
     */
    private SystemPages withoutSystemPages() {
        Reactions reactions = new Reactions();
        return new SystemPages(withoutArchitectureModel(), withoutMessageSchemas(), withoutArtifacts(),
                withoutArtifacts(), reactions, reactions, new NoCustomDocumentation(),
                new NoCustomStorage(), new CustomProperties(), new StructureTemplates(List.of()),
                new GeneratorProperties(), new ArchitectureImportProperties(),
                BuildMetrics.NONE, urls);
    }

    /** A system with nothing uploaded for it: this suite writes its pages by hand. */
    private static ch.admin.bit.jeap.doc.domain.template.SystemDocumentation withoutUploads(
            ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem system) {
        return ch.admin.bit.jeap.doc.domain.template.SystemDocumentation.of(Site.DEFAULT_SITE, system,
                ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation.nothing(),
                (subject, chapter, directory) -> 0);
    }

    /**
     * An instance whose artifacts have never been replicated. Throwing rather than answering "none", like
     * the message schemas next to it: this site configures no architecture repository, so nothing asks, and a
     * change that made it ask should fail here rather than look like a landscape without artifacts.
     */
    private static Artifacts withoutArtifacts() {
        return new Artifacts();
    }

    /**
     * Both halves of the reaction reading, neither of which this suite reaches: it writes the runtime views
     * it needs with the template itself, so anything asking here is a change that should fail rather than
     * look like an environment with no reactions.
     * <p>
     * A class of its own rather than a second interface on {@link Artifacts}: the two repository ports erase
     * {@code remove(Collection<…>)} to the same signature, so one class cannot implement both.
     */
    private static final class Reactions implements ReactionGraphRepository, ReactionGraphContent {

        @Override
        public List<ReactionGraphRef> findRefs(String environment, ArchitectureImportKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredReactionGraph> find(String environment, ArchitectureImportKind kind,
                                                  String name, String system, String variant) {
            return Optional.empty();
        }

        @Override
        public List<StoredReactionGraph> findVariants(String environment, String messageType) {
            return List.of();
        }

        @Override
        public void store(StoredReactionGraph graph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirm(String environment, ArchitectureImportKind kind, String name, String system,
                            String variant, Instant checkedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(Collection<ReactionGraphRef> graphs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObservedReactions read(StoredReactionGraph graph) {
            return ObservedReactions.empty();
        }
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
        NeverImported imports = new NeverImported();
        return new ch.admin.bit.jeap.doc.domain.DocumentationProvenance(
                new DocumentationSites(new SiteProperties()), imports, importStatesOf(imports),
                withoutArchitectureModel(),
                new ch.admin.bit.jeap.doc.domain.template.StructureTemplates(java.util.List.of()),
                new BuildProperties(), new ArchitectureImportProperties(),
                new ch.admin.bit.jeap.doc.domain.upload.UploadProperties(),
                new ch.admin.bit.jeap.doc.domain.custom.CustomProperties(),
                java.time.Clock.systemDefaultZone());
    }

    /** The one display read a provenance asks for, answered by the same import state. */
    private static ch.admin.bit.jeap.doc.domain.port.DisplayReads importStatesOf(NeverImported imports) {
        return (ch.admin.bit.jeap.doc.domain.port.DisplayReads) java.lang.reflect.Proxy.newProxyInstance(
                SiteBrowserTestBase.class.getClassLoader(),
                new Class<?>[]{ch.admin.bit.jeap.doc.domain.port.DisplayReads.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("importState")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return imports.state((String) arguments[0],
                            (ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind) arguments[1]);
                });
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
        SiteSources sources = fixtureSources(urls, properties);
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
     * does not notice, so the constructs are rendered here for real: the package block of a whitebox view,
     * <b>a second package holding a component of another system</b>, a box that is gold and bolded and linked
     * at once, <b>both blue arrows</b>, and a label carrying the escaped line break that several messages on
     * one arrow produce.
     * <p>
     * <b>Two boxes share a label and differ only in their alias.</b> That is what a component context view
     * emits where two systems each have a component of one name, and it is the construct that would silently
     * merge them into one box carrying the arrows of both.
     * <p>
     * <b>A package carries a link.</b> The neighbour of a component context view is a package rather than a
     * box, so that is where the way into the neighbour's own documentation now lives.
     * <p>
     * <b>One box links somewhere else than the page it is on.</b> A link inside a fence is the one kind the
     * generator does not rewrite - it is written absolute already - so nothing but a browser says whether it
     * is followed at all, or followed into a new tab. {@code c_gateway_2} points at the site root so that
     * following it is observable; the others point at the page itself.
     */
    private static final String GENERATED_DIAGRAM = """
            @startuml
            left to right direction
            skinparam nodesep 8
            skinparam ranksep 20
            package "orders" [[/guide/]] {
              component "orders-intake" as c_orders_intake [[/guide/]] #Gold;line.bold
              component "orders-risk" as c_orders_risk [[/guide/]]
            }
            package "shipping" [[/guide/]] {
              component "gateway" as c_gateway [[/guide/]]
            }
            package "catalog" [[/guide/]] {
              component "gateway" as c_gateway_2 [[/]]
            }
            c_orders_intake -[#green,dashed]-> c_orders_risk : OrdersPaymentAcceptedEvent\\nOrdersPaymentRejectedEvent
            c_orders_intake -[#blue,dashed]-> c_gateway : ShippingArrangeCommand
            c_orders_intake -[#blue]-> c_gateway_2 : GET /api/tariffs
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

    /** The system nothing has deployed and a team has documented - see {@code CustomDocsBrowserIT}. */
    protected static final String DOCUMENTED_SYSTEM = "catalog";

    /** Its library, which no architecture model could ever hold. */
    protected static final String DOCUMENTED_LIBRARY = "catalog-client";

    /** Where a reader finds that system's uploaded pages. */
    protected static final String DOCUMENTED_CHAPTER_ROUTE =
            "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/glossary";

    /** And its library's tree, beside the components of its system. */
    protected static final String DOCUMENTED_LIBRARY_ROUTE =
            "systems/" + DOCUMENTED_SYSTEM + "/system-architecture/building-block-view/libraries/"
            + DOCUMENTED_LIBRARY + "/library-architecture";

    /** Where the page with folds is served. */
    protected static final String FOLD_ROUTE = "folds";

    /**
     * A page of folds, written by {@code MarkdownWriter} as a generated page is. One fold per kind of content a
     * generated page folds: code, and the two diagram languages, which draw when they come into view.
     */
    private static void writeFoldPage(Path environmentTree) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .heading(1, "Folds")
                .details("Version 1.0.0", body -> body.fence("java", "record FoldedKey(String id) {}"))
                .details("PlantUML", body -> body.fence("plantuml", """
                        @startuml
                        component "folded-left" as l
                        component "folded-right" as r
                        l --> r
                        @enduml"""))
                .details("GraphViz", body -> body.fence("dot", "digraph { foldedA -> foldedB }"))
                .details("<script>window.MARKER_FOLD = 1</script>", body -> body.paragraph("Harmless."));
        Files.writeString(environmentTree.resolve(FOLD_ROUTE + ".md"), page.text(), StandardCharsets.UTF_8);
    }

    /** Where the page with a long and a short table is served - see {@code TableControlsBrowserIT}. */
    protected static final String TABLES_ROUTE = "tables";

    /** The rows of the long table, above the threshold at which a table gets a filter. */
    protected static final int RELATIONS = 20;

    /** The counts of the short table, in the order written; a numeric sort must not order them as text. */
    protected static final List<String> TIMES_OBSERVED = List.of("12'408", "987", "96", "2'150", "31", "4");

    /** The column of counterparts, written as the generator writes one: links, comma separated. */
    protected static final String CALLERS_COLUMN = "Callers";

    /** A description with commas in it, which is prose and not a list of anything. */
    protected static final String PROSE_DESCRIPTION = "Manages orders, invoices, deliveries, and returns";

    /** The counterparts of the row the chip is tested on, the last two of them behind it. */
    protected static final List<String> WIDE_CALLERS =
            List.of("alpha-caller", "beta-caller", "gamma-caller", "delta-caller", "epsilon-caller");

    /**
     * A cell of counterparts, as the component pages write one: one link per counterpart, comma separated.
     * The first row carries five, which is what the client module collapses behind its chip.
     */
    private static ch.admin.bit.jeap.doc.markdown.Markdown callers(int row) {
        if (row == 0) {
            // The first caller carries a pact above the line, as a component page writes one: it is part of
            // that item and not an item of its own.
            return ch.admin.bit.jeap.doc.markdown.Md.joinWith(", ", WIDE_CALLERS.stream()
                    .map(caller -> caller.equals(WIDE_CALLERS.getFirst())
                            ? ch.admin.bit.jeap.doc.markdown.Md.sentence("{}{}",
                                    ch.admin.bit.jeap.doc.markdown.Md.link("#" + caller, caller),
                                    ch.admin.bit.jeap.doc.markdown.Md.superscript(
                                            ch.admin.bit.jeap.doc.markdown.Md.link("#pact", "pact")))
                            : ch.admin.bit.jeap.doc.markdown.Md.link("#" + caller, caller))
                    .toList());
        }
        if (row % 4 == 0) {
            return ch.admin.bit.jeap.doc.markdown.Md.text("-");
        }
        return ch.admin.bit.jeap.doc.markdown.Md.joinWith(", ", List.of(
                ch.admin.bit.jeap.doc.markdown.Md.link("#caller-" + row, "caller-" + row),
                ch.admin.bit.jeap.doc.markdown.Md.code("caller-" + row + "-b")));
    }

    private static void writeTablesPage(Path environmentTree) throws IOException {
        String[] systems = {"orders", "shipping", "billing", "catalog", "warehouse"};
        String[] types = {"publishes", "consumes", "calls"};
        String[] travels = {"OrderPlacedEvent", "PaymentAcceptedEvent", "ShipmentCreatedCommand", "InvoiceIssuedEvent",
                "StockReservedEvent"};
        List<List<ch.admin.bit.jeap.doc.markdown.Markdown>> relations = new java.util.ArrayList<>();
        for (int i = 0; i < RELATIONS; i++) {
            String from = systems[i % systems.length] + "-" + (i % 2 == 0 ? "intake" : "worker");
            String to = systems[(i * 3 + 1) % systems.length] + "-" + (i % 3 == 0 ? "dispatch" : "service");
            relations.add(List.of(ch.admin.bit.jeap.doc.markdown.Md.link("#" + from, from),
                    ch.admin.bit.jeap.doc.markdown.Md.text(to),
                    ch.admin.bit.jeap.doc.markdown.Md.text(types[i % types.length]),
                    ch.admin.bit.jeap.doc.markdown.Md.code(travels[(i * 7) % travels.length]),
                    callers(i)));
        }
        String[] reactions = {"OrderPlacedEvent", "PaymentAcceptedEvent", "ShipmentCreatedCommand",
                "InvoiceIssuedEvent", "StockReservedEvent", "RefundRequestedCommand"};
        List<List<ch.admin.bit.jeap.doc.markdown.Markdown>> observed = new java.util.ArrayList<>();
        for (int i = 0; i < reactions.length; i++) {
            observed.add(List.of(ch.admin.bit.jeap.doc.markdown.Md.code(reactions[i]),
                    ch.admin.bit.jeap.doc.markdown.Md.text("shipping-dispatch"),
                    ch.admin.bit.jeap.doc.markdown.Md.text(TIMES_OBSERVED.get(i))));
        }
        MarkdownWriter page = new MarkdownWriter()
                .heading(1, "Tables")
                .paragraph(ch.admin.bit.jeap.doc.markdown.Md.link("./" + FOLD_ROUTE + ".md", "The page with folds"))
                .heading(2, "Relations")
                .table(List.of("From", "To", "Type", "Interaction", CALLERS_COLUMN), relations)
                .heading(2, "Reactions")
                .table(List.of("Trigger", "Reacting component", "Times observed"), observed)
                // A table of prose, as a description column is: its commas are not separators.
                .heading(2, "Descriptions")
                .table(List.of("Component", "Description"),
                        List.of(List.of(ch.admin.bit.jeap.doc.markdown.Md.code("orders-intake"),
                                ch.admin.bit.jeap.doc.markdown.Md.text(PROSE_DESCRIPTION))));
        Files.writeString(environmentTree.resolve(TABLES_ROUTE + ".md"), page.text(), StandardCharsets.UTF_8);
    }

    /** Shared with {@link SiteSearchBrowserIT}, which indexes the same pages the site is built from. */
    protected static void writeGuidePage(Path environmentTree, SiteEnvironment environment) throws IOException {
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
     * The page inside a component's tree, in a numbered chapter - see {@link #COMPONENT_PAGE_PATH}.
     * <p>
     * Written by hand rather than generated: this suite configures no architecture repository, and what is
     * needed here is the <b>shape</b> of a generated page's path, which is what a route is derived from.
     */
    private static void writeComponentPage(Path environmentTree) throws IOException {
        Path page = environmentTree.resolve(COMPONENT_PAGE_PATH + ".md");
        Files.createDirectories(page.getParent());
        Files.writeString(page, ("""
                ---
                title: 6. Runtime View
                ---

                # 6. Runtime View

                The intake applies WORD when the queue grows.
                """).replace("WORD", COMPONENT_PAGE_WORD), StandardCharsets.UTF_8);
    }

    /**
     * The tree of a system with reactions, written by the arc42 template itself: its chapter 6, the chapter 6
     * of its component, and the section on the page of the message that triggered them.
     * <p>
     * The graph is one message, one component reacting and one message published in answer - small enough to
     * read in a browser and enough to carry every feature the story asks for: a node to search for, a node to
     * link to, and an edge between them.
     */
    private void writeReactingSystem(Path environmentTree, SiteEnvironment environment)
            throws IOException {
        DocumentedMessage trigger = message("OrdersPaymentAcceptedEvent", REACTING_MESSAGE)
                .withVersions(TRIGGER_VERSIONS);
        DocumentedMessage dispatched = message("ShippingDispatchedEvent", "shipping-dispatched-event");
        DocumentedSystem shipping = new DocumentedSystem("shipping", REACTING_SYSTEM, "Ships what was ordered",
                List.of(), null,
                List.of(component(REACTING_COMPONENT, "Dispatches what has been paid for"),
                        component(BUSY_COMPONENT, "Prints a label per parcel")),
                List.of(), List.of(trigger, dispatched));
        // A second system with reactions of its own, so that the two systems' graphs point at each other: a
        // component of this one answers a message of that one, and publishes a message that one draws.
        DocumentedSystem billing = new DocumentedSystem("billing", OTHER_SYSTEM, "Settles what was shipped",
                List.of(), null, List.of(component(OTHER_COMPONENT, "Settles a shipment")), List.of(),
                List.of(message("BillingSettledEvent", OTHER_MESSAGE)));
        ArchitectureModel model = ArchitectureModel.of(List.of(shipping, billing));

        // Every component of the environment that has a runtime view, which is what decides whether a
        // reaction node is a link - on this system's graphs as much as on the other's.
        Set<String> componentsWithAGraph = Set.of(REACTING_COMPONENT, OTHER_COMPONENT);

        ReactionViews reactions = ReactionViews.of(GENERATED_AT, ReactionView.of(systemGraph(), model, shipping),
                Map.of(REACTING_COMPONENT, ReactionView.of(componentGraph(), model, shipping)),
                Map.of("OrdersPaymentAcceptedEvent",
                        List.of(new ReactionViews.VariantView("", ReactionView.of(messageGraph(), model,
                                        shipping)),
                                new ReactionViews.VariantView(VARIANT,
                                        ReactionView.of(messageGraph(VARIANT_MESSAGE_ID, VARIANT), model,
                                                shipping)))),
                componentsWithAGraph);

        // The prefix a link inside a fence has to carry, and it is the site's own rule: the base URL, then the
        // environment - except the main one, whose tree is served at the site root. Getting this wrong is a
        // link that 404s for a reader while every generated page still looks right.
        String linkPrefix = urls.baseUrl(defaultSite()) + (environment.main() ? "" : environment.id() + "/");
        GenerationContext context = new GenerationContext(model, environment.id(), "https://archrepo.example",
                GENERATED_AT, GENERATED_AT, new DiagramLimits(100, 4, 40, 100, 200), linkPrefix)
                .withReactions(reactions);
        writeSystemPage(environmentTree, REACTING_SYSTEM, "Ships what was ordered");
        new Arc42Template().writeSystem(withoutUploads(shipping), context,
                environmentTree.resolve("systems").resolve(REACTING_SYSTEM));

        // The other system's tree, with its own reactions - which is what makes crossing from one system's
        // graph into the other's, and back, something a test can walk.
        ReactionView answering = ReactionView.of(otherSystemGraph(), model, billing);
        ReactionViews billingReactions = ReactionViews.of(GENERATED_AT, answering,
                Map.of(OTHER_COMPONENT, answering),
                Map.of("BillingSettledEvent", List.of(new ReactionViews.VariantView("", answering))),
                componentsWithAGraph);
        writeSystemPage(environmentTree, OTHER_SYSTEM, "Settles what was shipped");
        new Arc42Template().writeSystem(withoutUploads(billing),
                new GenerationContext(model, environment.id(), "https://archrepo.example", GENERATED_AT,
                        GENERATED_AT, new DiagramLimits(100, 4, 40, 100, 200), linkPrefix, null,
                        billingReactions),
                environmentTree.resolve("systems").resolve(OTHER_SYSTEM));
    }

    /**
     * The system's own page, which the generator writes above the structure and the template's pages link back
     * to. Written by hand here because this suite generates a system's tree and not a site's worth - and a
     * link to a page nothing wrote fails the whole site build, which is the rule these pages are subject to as
     * much as a real build's.
     */
    /**
     * A system whose documentation a team wrote: no architecture model holds it, it has a library, and its
     * pages are the ones an upload brings. Written through the template and the real page writer, so what a
     * browser sees here is what an upload produces.
     */
    private void writeDocumentedSystem(Path environmentTree, SiteEnvironment environment) throws IOException {
        GenerationContext context = new GenerationContext(new ArchitectureModel(List.of()), environment.id(),
                "https://archrepo.example", GENERATED_AT, GENERATED_AT,
                new DiagramLimits(100, 4, 40, 100, 200), "/");
        writeSystemPage(environmentTree, DOCUMENTED_SYSTEM, "Documented by hand");
        new Arc42Template().writeSystem(UploadedDocumentation.of(DOCUMENTED_SYSTEM, DOCUMENTED_LIBRARY),
                context, environmentTree.resolve("systems").resolve(DOCUMENTED_SYSTEM));
    }

    private static void writeSystemPage(Path environmentTree, String slug, String description)
            throws IOException {
        Path systemDirectory = environmentTree.resolve("systems").resolve(slug);
        Files.createDirectories(systemDirectory);
        Files.writeString(systemDirectory.resolve("index.md"),
                ("---\ntitle: " + slug + "\n---\n\n# " + slug + "\n\n" + description + "\n"),
                StandardCharsets.UTF_8);
    }

    /**
     * The graph of the system: the reaction every test addresses, a component that reacts to one message
     * several times, a message of another system, a message no landscape documents, a reaction of a component
     * this landscape does not have, and one reaction nothing triggered.
     * <p>
     * Each of those is a case the drawing has to answer differently - a cluster, a blue node, a node that is
     * not a link, a box with no arrow into it - and a graph with one of everything is what makes a page worth
     * looking at.
     */
    private static ObservedReactions systemGraph() {
        List<ObservedReactions.ObservedMessage> messages = new java.util.ArrayList<>(List.of(
                new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", null),
                new ObservedReactions.ObservedMessage(5, "BillingSettledEvent", null),
                new ObservedReactions.ObservedMessage(7, UNDOCUMENTED_MESSAGE, null),
                new ObservedReactions.ObservedMessage(VARIANT_MESSAGE_ID, "OrdersPaymentAcceptedEvent", VARIANT)));
        List<ObservedReactions.ObservedReaction> reactions = new java.util.ArrayList<>(List.of(
                new ObservedReactions.ObservedReaction(REACTION_ID, REACTING_COMPONENT, null),
                // Three reactions of one component to one message: one dashed box with three nodes in it.
                new ObservedReactions.ObservedReaction(4243, BUSY_COMPONENT, null),
                new ObservedReactions.ObservedReaction(4244, BUSY_COMPONENT, null),
                new ObservedReactions.ObservedReaction(4245, BUSY_COMPONENT, null),
                // A component this landscape does not document: drawn, and not a link.
                new ObservedReactions.ObservedReaction(4246, UNDOCUMENTED_COMPONENT, null),
                // And one nothing triggered, which the observer can hold.
                new ObservedReactions.ObservedReaction(4247, REACTING_COMPONENT, null)));
        List<ObservedReactions.ObservedTrigger> triggers = new java.util.ArrayList<>(List.of(
                new ObservedReactions.ObservedTrigger(1, REACTION_ID, 12),
                new ObservedReactions.ObservedTrigger(1, 4243, 400),
                new ObservedReactions.ObservedTrigger(1, 4244, 4),
                new ObservedReactions.ObservedTrigger(1, 4245, null),
                new ObservedReactions.ObservedTrigger(7, 4246, 7)));
        List<ObservedReactions.ObservedAction> actions = new java.util.ArrayList<>(List.of(
                new ObservedReactions.ObservedAction(REACTION_ID, 3),
                new ObservedReactions.ObservedAction(4243, 5),
                new ObservedReactions.ObservedAction(4246, VARIANT_MESSAGE_ID)));
        // And enough further reactions that the picture is one a reader has to navigate: a minimap and a
        // fitted view are features of a graph nobody can read at once.
        for (int index = 0; index < WIDE_REACTIONS; index++) {
            long message = 100 + index;
            long reaction = 200 + index;
            messages.add(new ObservedReactions.ObservedMessage(message, "ShippingBulkEvent" + index, null));
            reactions.add(new ObservedReactions.ObservedReaction(reaction, "shipping-bulk-" + index, null));
            triggers.add(new ObservedReactions.ObservedTrigger(message, reaction, index));
        }
        return new ObservedReactions(messages, reactions, triggers, actions);
    }

    /**
     * What the second system saw: its component answering a message of the first, and publishing one the
     * first system's graph draws. Two systems whose graphs point at each other is what makes a link between
     * them worth walking.
     */
    private static ObservedReactions otherSystemGraph() {
        return new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(11, "ShippingDispatchedEvent", null),
                        new ObservedReactions.ObservedMessage(13, "BillingSettledEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(OTHER_REACTION_ID, OTHER_COMPONENT, null)),
                List.of(new ObservedReactions.ObservedTrigger(11, OTHER_REACTION_ID, 3)),
                List.of(new ObservedReactions.ObservedAction(OTHER_REACTION_ID, 13)));
    }

    /** What one component saw: the same reaction, addressed by the same id a deep link carries. */
    private static ObservedReactions componentGraph() {
        return new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                        new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(REACTION_ID, REACTING_COMPONENT, null),
                        new ObservedReactions.ObservedReaction(4247, REACTING_COMPONENT, null)),
                List.of(new ObservedReactions.ObservedTrigger(1, REACTION_ID, 12)),
                List.of(new ObservedReactions.ObservedAction(REACTION_ID, 3)));
    }

    /** What one message triggered, which is the section on its own page. */
    private static ObservedReactions messageGraph() {
        return messageGraph(1, null);
    }

    /**
     * The graph of one message type, or of one variant of it - and <b>the subject is a node of it</b>, which is
     * what makes a variant's diagram addressable: the observer gives every variant of a type an id of its own,
     * so a link from another graph names that id and lands on the variant's own diagram.
     */
    private static ObservedReactions messageGraph(long subject, String variant) {
        return new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(subject, "OrdersPaymentAcceptedEvent", variant)),
                List.of(new ObservedReactions.ObservedReaction(REACTION_ID, REACTING_COMPONENT, null),
                        new ObservedReactions.ObservedReaction(4243, BUSY_COMPONENT, null)),
                List.of(new ObservedReactions.ObservedTrigger(subject, REACTION_ID, 12),
                        new ObservedReactions.ObservedTrigger(subject, 4243, 400)),
                List.of());
    }

    private static DocumentedComponent component(String name, String description) {
        return new DocumentedComponent(name, name, description, ComponentType.SELF_CONTAINED_SYSTEM, null,
                null, null, List.of(), null, null, null);
    }

    /**
     * The versions of the triggering message: one with a key and a value schema, and one with a value schema
     * only - so the versions table has a group of two rows and a group of one.
     */
    protected static final List<DocumentedMessageVersion> TRIGGER_VERSIONS = List.of(
            new DocumentedMessageVersion("1.0.0", null, null, null,
                    new MessageSchema("OrdersPaymentAcceptedEvent_v1.avdl", "https://registry.example/v1.avdl",
                            "record OrdersPaymentAcceptedEventPayload {\n    string orderId;\n}")),
            new DocumentedMessageVersion("2.0.0", "BACKWARD", "1.0.0",
                    new MessageSchema("OrdersPaymentAcceptedEvent_key.avdl", "https://registry.example/key.avdl",
                            "record OrdersPaymentAcceptedEventKey {\n    string orderId;\n}"),
                    new MessageSchema("OrdersPaymentAcceptedEvent_v2.avdl", "https://registry.example/v2.avdl",
                            "record OrdersPaymentAcceptedEventPayload {\n    string orderId;\n"
                            + "    union { null, string } aFieldWithAVeryLongNameThatWouldRunPastANarrowColumn = null;"
                            + "\n}")));

    private static DocumentedMessage message(String name, String slug) {
        return new DocumentedMessage(name, slug, MessageKind.EVENT, "internal", "shipping-topic", null, null,
                null, List.of(), List.of());
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
