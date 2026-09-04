package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;
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
                new DocumentationSites(new SiteProperties()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation());
        builder = builderWriting(sources);
    }


    /**
     * Every environment has a search index of its own, holding that environment and nothing else.
     * <p>
     * Two things are asserted at once here, and both have been wrong before. That an environment is indexed at
     * all: two independently sensible settings once left the index empty - the banner puts a {@code noindex}
     * meta on every page of a non-main environment, which the search plugin reads as "unlisted" and skips, and
     * the plugin drops the site's front page unless the main environment is the first route base path it is
     * given, so the result was a search bar on every page that found nothing, and nothing failed. And that the
     * indexes are separate: the environments hold the same pages, so one index over all of them answers every
     * query with the same page once per environment.
     * <p>
     * The main environment is the one served at the site root. It is not one of the configured search paths but
     * what is left when none of them matched, so its index is the file with no environment in its name.
     */
    @Test
    void generate_thenEachEnvironmentHasASearchIndexOfItsOwn() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(6, site, GENERATED_AT);

        for (SiteEnvironment environment : site.environments()) {
            // The tree's own route, so a hit actually leads somewhere - the main environment is at the root.
            String route = environment.main() ? "/docs/" : "/docs/" + environment.id() + "/";
            String index = searchIndexOf(built, environment);
            assertThat(index)
                    .describedAs("the search index of %s should hold its own root page", environment.id())
                    .contains("\"u\":\"" + route + "\"");
            for (SiteEnvironment other : site.environments()) {
                if (other.main() || other.id().equals(environment.id())) {
                    // The main environment's route is a prefix of every other one, so it cannot be looked for
                    // by its route; that it holds only itself is what the three assertions below add up to.
                    continue;
                }
                assertThat(index)
                        .describedAs("the search index of %s should not hold pages of %s",
                                environment.id(), other.id())
                        .doesNotContain("\"u\":\"/docs/" + other.id() + "/");
            }
        }
    }

    /**
     * The index file of one environment. The plugin names the main environment's - the one it knows as the
     * leftover of every configured path - without an environment in it at all.
     */
    private static String searchIndexOf(BuiltSite built, SiteEnvironment environment) throws IOException {
        String infix = environment.main() ? "" : "-" + environment.id();
        List<Path> written;
        try (Stream<Path> files = Files.walk(built.directory())) {
            written = files.filter(file -> file.getFileName().toString().startsWith("search-index")).toList();
        }
        Path index = written.stream()
                // The exact name: `hashed: true` puts the hash in the query rather than in the file name, and a
                // pattern tolerating one here would make the main environment's lookup - whose infix is empty -
                // match another environment's file as well.
                .filter(file -> file.getFileName().toString().equals("search-index" + infix + ".json"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No search index was written for the environment "
                        + environment.id() + "; the build produced "
                        + written.stream().map(built.directory()::relativize).map(Path::toString)
                                .sorted().toList()));
        return Files.readString(index, StandardCharsets.UTF_8);
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

        BuiltSite built = builderWriting(new SiteSources(urls, resourceLoader, NoArchitectureModel.systemPages(urls),
                new DocumentationSites(new SiteProperties()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation()) {
            @Override
            public Map<String, EnvironmentModel> write(long buildId, Site written, Path content,
                                                       Instant generatedAt) throws IOException {
                Map<String, EnvironmentModel> models = super.write(buildId, written, content, generatedAt);
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
                return models;
            }
        }).generate(7, site, GENERATED_AT);

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

        BuiltSite built = builderWriting(sources).generate(9, site, GENERATED_AT);

        assertThat(built.directory().resolve("index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/index.html")).isRegularFile();
        assertThat(built.pageCount()).isPositive();
    }

    private DocusaurusSiteBuilder builderWriting(SiteSources writing) {
        return new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties), new SiteTemplate(),
                new NodeProcess(properties), writing);
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
            built = builder.generate(1, site, GENERATED_AT);
        } finally {
            nodeLog.detachAppender(logged);
        }

        // The main environment owns the site root, the others sit behind their prefix.
        assertThat(built.directory().resolve("index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/index.html")).isRegularFile();
        assertThat(built.directory().resolve("ref/index.html")).isRegularFile();
        assertThat(built.directory().resolve("abn/index.html")).isRegularFile();

        // Offline search, as in the jEAP documentation.
        assertThat(built.directory().resolve("search-index.json")).isRegularFile();

        assertThat(built.pageCount()).isPositive();
        assertThat(built.sizeInBytes()).isPositive();
        assertThat(built.docusaurusMillis()).isPositive();
    }

    @Test
    void generate_thenTheEnvironmentSwitcherIsOnThePage() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(2, site, GENERATED_AT);

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
        BuiltSite built = builderWriting(new SiteSources(urls, resourceLoader, NoArchitectureModel.systemPages(urls),
                new DocumentationSites(new SiteProperties()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation()) {
            @Override
            public Map<String, EnvironmentModel> write(long buildId, Site written, Path content,
                                                       Instant generatedAt) throws IOException {
                Map<String, EnvironmentModel> models = super.write(buildId, written, content, generatedAt);
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
                        """, StandardCharsets.UTF_8);
                return models;
            }
        }).generate(3, site, GENERATED_AT);

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

        BuiltSite built = builder.generate(5, site, GENERATED_AT);

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

        builder.generate(4, site, GENERATED_AT);
        assertThat(workspaceRoot.resolve("4")).isDirectory();

        builder.discard(4);
        assertThat(workspaceRoot.resolve("4")).doesNotExist();
    }
}
