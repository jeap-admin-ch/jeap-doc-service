package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a real documentation site with the real site generator.
 * <p>
 * It is the one test that proves the site template compiles and that the adapter can run it, and it is not
 * conditional: <b>Node is a precondition of this build</b>, in the way Docker is for the tests that need a
 * database. Its dependencies are installed into {@code target/site-install} before the integration tests run,
 * out of the same {@code jeap-doc-site} artifact and with the same {@code npm ci} an instance's image uses.
 */
class DocusaurusSiteBuilderIT {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-25T10:15:30Z");

    @TempDir
    Path workspaceRoot;

    private DocusaurusSiteBuilder builder;
    private BuildProperties properties;
    private SiteUrls urls;
    private org.springframework.core.io.ResourceLoader resourceLoader;
    private SiteSources sources;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        properties.setWorkspaceDirectory(workspaceRoot);
        properties.setNodeModulesDirectory(Path.of("target/site-install/node_modules").toAbsolutePath());
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        urls = new SiteUrls(publication, "/docs");
        resourceLoader = new org.springframework.core.io.DefaultResourceLoader();
        sources = new SiteSources(urls, resourceLoader, NoArchitectureModel.systemPages(urls),
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation());
        builder = builderWriting(sources);
    }


    /**
     * Links have to survive the environment prefixing, in both of the shapes a documentation page uses them.
     * <p>
     * The plugin that prefixes root-relative links runs <b>before</b> Docusaurus resolves relative ones. The
     * other way round it would prefix a permalink Docusaurus had already resolved - `./other.md` in the DEV
     * tree becoming `/dev/dev/other` - and with `onBrokenLinks: 'throw'` that is a failed build of every
     * environment but the main one. Nothing generates links yet, so only a test says so.
     */
    @Test
    void generate_whenPagesLinkToEachOther_thenTheLinksResolveInEveryEnvironment() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        DocusaurusSiteBuilder linking = builderWriting(sourcesAlsoWriting((written, content) -> {
            for (SiteEnvironment environment : written.environments()) {
                Path tree = content.resolve(environment.id());
                Files.writeString(tree.resolve("other.md"), """
                        # The other page

                        Back to [the front page](/).
                        """, StandardCharsets.UTF_8);
                Files.writeString(tree.resolve("linking.md"), """
                        # Linking

                        A relative link to [the other page](./other.md), and a root-relative one to
                        [the front page](/).
                        """, StandardCharsets.UTF_8);
            }
        }));

        BuiltSite built = linking.generate(linking.prepare(7, site, wholeSiteOf(site), GENERATED_AT));

        // The build not throwing is half of it - a link that resolved to nothing would have failed it. The
        // other half is that the prefixing did not double up, which only the emitted href shows.
        for (SiteEnvironment environment : site.environments()) {
            String tree = environment.main() ? "" : environment.id() + "/";
            String route = "/docs/" + tree;
            Path page = built.directory().resolve(tree + "linking/index.html");
            assertThat(page).describedAs("the linking page of %s", environment.id()).isRegularFile();

            // The generator minifies, so the attribute may or may not be quoted.
            String html = Files.readString(page, StandardCharsets.UTF_8);
            assertThat(html)
                    .describedAs("the relative link on the linking page of %s", environment.id())
                    .containsPattern("href=\"?" + java.util.regex.Pattern.quote(route + "other/") + "[\"> ]");
            assertThat(html)
                    .describedAs("the root-relative link on the linking page of %s", environment.id())
                    .containsPattern("href=\"?" + java.util.regex.Pattern.quote(route) + "[\"> ]");
            if (!environment.main()) {
                // What prefixing twice would have produced.
                assertThat(html).doesNotContain("/docs/" + environment.id() + "/" + environment.id() + "/");
            }
        }
    }

    /**
     * A builder whose sources are the given ones, so that a test can add a page to what the doc service writes
     * without the production code needing a hook for it.
     */
    /**
     * The static generation from a pool of worker threads, which an instance with room in its container may
     * ask for. It is a real build because that is the only honest assertion about the template's configuration:
     * Docusaurus rejects a `future.faster` key it does not know, and it refuses the worker threads outright
     * unless the v4 flag they depend on is on - so a site that comes out of this ran with them.
     */
    @Test
    void generate_whenTheStaticGenerationMayUseWorkerThreads_thenTheSiteIsStillProduced() {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
        properties.setSsgWorkerThreads(true);

        DocusaurusSiteBuilder workers = builderWriting(sources);

        BuiltSite built = workers.generate(workers.prepare(9, site, wholeSiteOf(site), GENERATED_AT));

        assertThat(built.directory().resolve("index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/index.html")).isRegularFile();
        assertThat(built.pageCount()).isPositive();
    }

    /**
     * A real Docusaurus build of a part that carries <b>one system</b>, in every environment of the site.
     * <p>
     * Every other case here builds the shell, and the shell exercises none of what the split added: the
     * per-system mount, the {@code partTree} of the docs options, the way out of a part, and the filter that
     * keeps an environment without content out of the build. A part is what an instance really builds
     * fifty-one times per publication, so it is built here once.
     */
    @Test
    void generate_whenThePartCarriesOneSystem_thenItIsMountedWhereTheWholeSiteWouldHavePutIt()
            throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
        DocusaurusSiteBuilder ofOneSystem = builderWriting(sourcesReadingALandscape());

        BuiltSite built = ofOneSystem.generate(
                ofOneSystem.prepare(11, site, systemPartOf(site, "orders"), GENERATED_AT));

        // The same URLs the system would have had in a site built whole - which is the point of the split.
        assertThat(built.directory().resolve("systems/orders/index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/systems/orders/index.html")).isRegularFile();
        // And nothing above the system: the systems index and the root page are the shell's pages.
        assertThat(built.directory().resolve("index.html")).doesNotExist();

        // The way out of the part, carrying the prefix of the environment the reader is in. Built without
        // that prefix, a reader in the dev tree landed in the main environment's index - and `pathname://` is
        // outside onBrokenLinks, so no build would ever have said so. The protocol is a build-time marker:
        // what reaches the page is a plain anchor, which is the whole reason it leaves the check.
        String inProduction = Files.readString(built.directory().resolve("systems/orders/index.html"),
                StandardCharsets.UTF_8);
        assertThat(inProduction).contains("All systems")
                .containsPattern("href=\"?" + Pattern.quote("/docs/systems/") + "[\"> ]");
        String inDevelopment = Files.readString(built.directory().resolve("dev/systems/orders/index.html"),
                StandardCharsets.UTF_8);
        assertThat(inDevelopment).contains("All systems")
                .describedAs("the sidebar's way out carries the environment the reader is in")
                .containsPattern("href=\"?" + Pattern.quote("/docs/dev/systems/") + "[\"> ]");
        // What prefixing twice would have produced. The main environment's index is on this page too - the
        // footer of the site links to it from every tree, which is what a footer is - so its absence is not
        // what says the sidebar got the environment right; the href above is.
        assertThat(inDevelopment).doesNotContain("/docs/dev/dev/");
    }

    /**
     * <b>The assumption the shared-asset scheme rests on</b>, and nothing asserted it: two parts of one site
     * emit byte-identical files for every shared name.
     * <p>
     * They are published to one prefix of the site, written by every part build, so a build overwriting
     * another's file has to be writing what was already there. Where it is not, the last part to finish
     * decides what the whole site loads - which is a site that renders differently depending on the order its
     * parts happened to build in, and nothing about the builds would look wrong.
     */
    @Test
    void generate_whenTwoPartsOfOneSiteAreBuilt_thenTheirSharedFilesAreTheSameBytes() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
        DocusaurusSiteBuilder ofALandscape = builderWriting(sourcesReadingALandscape());

        BuiltSite shell = ofALandscape.generate(
                ofALandscape.prepare(12, site, wholeSiteOf(site), GENERATED_AT));
        Map<String, byte[]> sharedOfShell = sharedFilesOf(shell);
        BuiltSite ofOneSystem = ofALandscape.generate(
                ofALandscape.prepare(13, site, systemPartOf(site, "orders"), GENERATED_AT));
        Map<String, byte[]> sharedOfPart = sharedFilesOf(ofOneSystem);

        assertThat(sharedOfShell).describedAs("the shell emits shared files at all").isNotEmpty();
        assertThat(sharedOfPart).describedAs("and so does a part").isNotEmpty();
        for (String name : sharedOfShell.keySet()) {
            if (sharedOfPart.containsKey(name)) {
                assertThat(sharedOfPart.get(name))
                        .describedAs("the shared file %s, which both parts publish to one prefix", name)
                        .isEqualTo(sharedOfShell.get(name));
            }
        }
    }

    /** The files of a build that go to the site's shared prefix, by their path within the site. */
    private static Map<String, byte[]> sharedFilesOf(BuiltSite built) throws IOException {
        Map<String, byte[]> shared = new java.util.LinkedHashMap<>();
        try (java.util.stream.Stream<Path> files = Files.walk(built.directory())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = built.directory().relativize(file).toString().replace('\\', '/');
                if (ch.admin.bit.jeap.doc.domain.SharedAssets.holds(path)) {
                    shared.put(path, Files.readAllBytes(file));
                }
            }
        }
        return shared;
    }

    /** The part carrying one system, in every environment of the site - what the partition produces. */
    private static SitePart systemPartOf(Site site, String slug) {
        return new SitePart(ch.admin.bit.jeap.doc.domain.PartKey.of(site.id(), "system-" + slug),
                "the system " + slug, "systems/" + slug, true,
                site.environments().stream().map(SiteEnvironment::id).toList(),
                site.environments().stream()
                        .map(environment -> environment.routePrefix() + "/systems/" + slug + "/").toList());
    }

    /** Sources whose landscape has one system in it, read by every environment. */
    private SiteSources sourcesReadingALandscape() {
        return new SiteSources(urls, resourceLoader,
                new SystemPages(OneSystemEverywhere.INSTANCE, NoMessageSchemas.INSTANCE,
                        NoArchitectureArtifacts.INSTANCE, NoArchitectureArtifacts.INSTANCE,
                        NoReactions.INSTANCE, NoReactions.INSTANCE,
                        new NoCustomDocumentation(), NoCustomStorage.INSTANCE,
                        new ch.admin.bit.jeap.doc.domain.custom.CustomProperties(),
                        new ch.admin.bit.jeap.doc.domain.template.StructureTemplates(java.util.List.of()),
                        new GeneratorProperties(),
                        new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                        ch.admin.bit.jeap.doc.domain.port.BuildMetrics.NONE, urls),
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(OneSystemEverywhere.INSTANCE, new NoCustomDocumentation()), properties,
                TestProvenance.of(OneSystemEverywhere.INSTANCE), new AboutThisDocumentation());
    }

    /** A landscape of one system, read by every environment of the site. */
    private static final class OneSystemEverywhere
            implements ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource {

        private static final OneSystemEverywhere INSTANCE = new OneSystemEverywhere();

        @Override
        public boolean isConfiguredFor(String environment) {
            return true;
        }

        @Override
        public java.util.Optional<String> sourceUrlOf(String environment) {
            return java.util.Optional.of("https://archrepo.example.com/archrepo");
        }

        @Override
        public java.util.List<String> systemSlugsOf(String environment) {
            return java.util.List.of("orders");
        }

        @Override
        public ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot read(String environment) {
            return new ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot(
                    ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel.of(java.util.List.of(
                            new ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem("orders", "orders",
                                    "The orders system.", java.util.List.of(), null, java.util.List.of(),
                                    java.util.List.of(), java.util.List.of()))),
                    GENERATED_AT.minusSeconds(900));
        }

        @Override
        public java.util.Optional<Instant> lastSuccessfulImportAt(String environment) {
            return java.util.Optional.of(GENERATED_AT.minusSeconds(900));
        }
    }

    private DocusaurusSiteBuilder builderWriting(SiteSources writing) {
        return new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties), new SiteTemplate(),
                new NodeProcess(properties), writing);
    }

    /**
     * The generator's own sources, plus a page or two written beside them - so that a page reaches the site
     * generator exactly the way a page of real documentation does.
     */
    private SiteSources sourcesAlsoWriting(ExtraPages extra) {
        return new SiteSources(urls, resourceLoader, NoArchitectureModel.systemPages(urls),
                new DocumentationSites(new SiteProperties()),
                new ch.admin.bit.jeap.doc.domain.SystemSitePartition(NO_MODEL, new NoCustomDocumentation()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation()) {
            @Override
            public WrittenContent write(long buildId, Site written, SitePart part, Path content,
                                        Instant generatedAt) throws IOException {
                WrittenContent writtenContent = super.write(buildId, written, part, content, generatedAt);
                extra.writeInto(written, content);
                return writtenContent;
            }
        };
    }

    /** Pages a test writes into the content of a build, beside the ones the generator writes. */
    private interface ExtraPages {
        void writeInto(Site site, Path content) throws IOException;
    }

    /** The part that carries the whole site: every environment and every system of it. */
    private static SitePart wholeSiteOf(Site site) {
        return new SitePart(ch.admin.bit.jeap.doc.domain.PartKey.shellOf(site.id()), "the site itself", "",
                true, site.environments().stream().map(SiteEnvironment::id).toList(), java.util.List.of());
    }

    @Test
    void generate_thenASiteWithARootPagePerEnvironmentAndTheConfiguredPlugins() {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        Logger nodeLog = (Logger) LoggerFactory.getLogger(NodeProcess.class);
        nodeLog.addAppender(logged);

        BuiltSite built;
        try {
            built = builder.generate(builder.prepare(1, site, wholeSiteOf(site), GENERATED_AT));
        } finally {
            nodeLog.detachAppender(logged);
        }

        // The main environment owns the site root, the others sit behind their prefix.
        assertThat(built.directory().resolve("index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/index.html")).isRegularFile();
        assertThat(built.directory().resolve("ref/index.html")).isRegularFile();
        assertThat(built.directory().resolve("abn/index.html")).isRegularFile();

        assertThat(built.pageCount()).isPositive();
        assertThat(built.sizeInBytes()).isPositive();
        assertThat(built.docusaurusMillis()).isPositive();
    }

    @Test
    void generate_thenTheEnvironmentSwitcherIsOnThePage() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(builder.prepare(2, site, wholeSiteOf(site), GENERATED_AT));

        String page = Files.readString(built.directory().resolve("index.html"), StandardCharsets.UTF_8);
        assertThat(page).contains("Switch environment").contains("PROD").contains("DEV");
        // The non-production trees say what they are, and are kept out of search engines.
        String development = Files.readString(built.directory().resolve("dev/index.html"), StandardCharsets.UTF_8);
        assertThat(development).contains("Development").contains("noindex");
    }

    /**
     * Diagrams are fenced source blocks rendered in the reader's browser, never images. This is the only place in
     * the enabler where that is proved before the stories that generate diagrams depend on it.
     */
    @Test
    void generate_whenAPageCarriesDiagramFences_thenThePluginRendersThemInTheBrowser() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        // A page beside the ones the doc service writes, so that the fences reach the generator the same way a
        // page of real documentation will.
        DocusaurusSiteBuilder diagrams = builderWriting(sourcesAlsoWriting((written, content) ->
                Files.writeString(content.resolve("prod/diagrams.md"), """
                        # Diagrams

                        ```plantuml
                        @startuml
                        component "jeap-doc-service" as doc
                        @enduml
                        ```

                        ```dot
                        digraph { upload -> build }
                        ```
                        """, StandardCharsets.UTF_8)));

        BuiltSite built = diagrams.generate(diagrams.prepare(3, site, wholeSiteOf(site), GENERATED_AT));

        String page = Files.readString(built.directory().resolve("diagrams/index.html"), StandardCharsets.UTF_8);
        // Whether the attribute value is quoted is the HTML minifier's business, not the plugin's.
        assertThat(page).containsPattern("data-plantuml-diagram=\"?plantuml")
                .containsPattern("data-plantuml-diagram=\"?dot");
        // The site's own logo and favicon are images; a diagram is not - it is its source, rendered in the
        // browser, so the figure the plugin writes carries no image at all.
        Matcher figures = Pattern.compile("<figure[^>]*data-plantuml-diagram.*?</figure>", Pattern.DOTALL)
                .matcher(page);
        int seen = 0;
        while (figures.find()) {
            seen++;
            assertThat(figures.group()).doesNotContain("<img").doesNotContain(".png").doesNotContain(".svg");
        }
        assertThat(seen).as("both fences became diagram figures").isEqualTo(2);
    }

    /**
     * A site's own mark has to survive the build. It used to be written under the same name as the one the
     * template ships, and the generator copies its static directories <b>without overwriting</b> - so the
     * configured logo was skipped in favour of the default, silently and with nothing in the build output.
     */
    @Test
    void generate_whenTheSiteBringsItsOwnLogo_thenItIsTheOneInTheBuiltSite() throws Exception {
        Path logo = Files.writeString(workspaceRoot.resolve("mark.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"><title>configured</title></svg>",
                StandardCharsets.UTF_8);
        SiteProperties siteProperties = new SiteProperties();
        SiteProperties.Site configured = new SiteProperties.Site();
        configured.setTitle("Governance");
        configured.setLogo(logo.toUri().toString());
        siteProperties.setSites(java.util.Map.of(Site.DEFAULT_SITE, configured));
        Site site = new DocumentationSites(siteProperties).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(builder.prepare(5, site, wholeSiteOf(site), GENERATED_AT));

        // The site's own mark is published under its own path - the generator names it after what it is, not
        // after the file it came from.
        Path published = built.directory().resolve("branding/logo.svg");
        assertThat(published).isRegularFile();
        assertThat(Files.readString(published, StandardCharsets.UTF_8)).contains("configured");
        // ...and the template's default is still there, untouched, for the sites that bring none.
        assertThat(Files.readString(built.directory().resolve("img/logo.svg"), StandardCharsets.UTF_8))
                .doesNotContain("configured");
        // A site that names a logo but no favicon uses the logo as both, so the favicon must point at the file
        // that was actually written rather than at a name nothing wrote.
        assertThat(Files.readString(workspaceRoot.resolve("5/content/site.json"), StandardCharsets.UTF_8))
                .contains("\"logo\" : \"branding/logo.svg\"")
                .contains("\"favicon\" : \"branding/logo.svg\"");
    }

    @Test
    void generate_thenTheWorkspaceIsNamedAfterTheBuildAndCanBeDiscarded() {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        builder.generate(builder.prepare(4, site, wholeSiteOf(site), GENERATED_AT));
        assertThat(workspaceRoot.resolve("4")).isDirectory();

        builder.discard(4);
        assertThat(workspaceRoot.resolve("4")).doesNotExist();
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
