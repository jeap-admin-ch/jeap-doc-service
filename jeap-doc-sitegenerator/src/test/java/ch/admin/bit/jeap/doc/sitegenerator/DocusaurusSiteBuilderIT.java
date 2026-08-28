package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        sources = new SiteSources(urls, resourceLoader);
        builder = builderWriting(sources);
    }


    /**
     * The search index has to hold something for every environment.
     * <p>
     * Two independently sensible settings once left it empty: the banner puts a {@code noindex} meta on every
     * page of a non-main environment, which the search plugin reads as "unlisted" and skips, and the plugin
     * drops the site's front page unless the main environment is the first route base path it is given. The
     * result was a search bar on every page that found nothing at all - and nothing failed.
     */
    @Test
    void generate_thenEveryEnvironmentIsInTheSearchIndex() throws Exception {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(6, site, GENERATED_AT);

        String index = Files.readString(built.directory().resolve("search-index.json"), StandardCharsets.UTF_8);
        for (SiteEnvironment environment : site.environments()) {
            // The tree's own route, so a hit actually leads somewhere - the main environment is at the root.
            String route = environment.main() ? "/docs/" : "/docs/" + environment.id() + "/";
            assertThat(index)
                    .describedAs("the search index should hold the root page of %s", environment.id())
                    .contains("\"u\":\"" + route + "\"");
        }
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

        BuiltSite built = builderWriting(new SiteSources(urls, resourceLoader) {
            @Override
            public void write(Site written, Path content, Instant generatedAt) throws IOException {
                super.write(written, content, generatedAt);
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
    private DocusaurusSiteBuilder builderWriting(SiteSources writing) {
        return new DocusaurusSiteBuilder(properties, new BuildWorkspaces(properties), new SiteTemplate(),
                new NodeProcess(properties), writing);
    }

    @Test
    void generate_thenASiteWithARootPagePerEnvironmentAndTheConfiguredPlugins() {
        Site site = new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();

        BuiltSite built = builder.generate(1, site, GENERATED_AT);

        // The main environment owns the site root, the others sit behind their prefix.
        assertThat(built.directory().resolve("index.html")).isRegularFile();
        assertThat(built.directory().resolve("dev/index.html")).isRegularFile();
        assertThat(built.directory().resolve("ref/index.html")).isRegularFile();
        assertThat(built.directory().resolve("abn/index.html")).isRegularFile();

        // Search and llms.txt, as in the jEAP documentation.
        assertThat(built.directory().resolve("search-index.json")).isRegularFile();
        assertThat(built.directory().resolve("llms.txt")).isRegularFile();
        assertThat(built.directory().resolve("llms-full.txt")).isRegularFile();

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
        BuiltSite built = builderWriting(new SiteSources(urls, resourceLoader) {
            @Override
            public void write(Site written, Path content, Instant generatedAt) throws IOException {
                super.write(written, content, generatedAt);
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
            }
        }).generate(3, site, GENERATED_AT);

        String page = Files.readString(built.directory().resolve("diagrams/index.html"), StandardCharsets.UTF_8);
        assertThat(page).contains("data-plantuml-diagram=plantuml").contains("data-plantuml-diagram=dot");
        assertThat(page).doesNotContain(".png").doesNotContain(".svg\"");
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
