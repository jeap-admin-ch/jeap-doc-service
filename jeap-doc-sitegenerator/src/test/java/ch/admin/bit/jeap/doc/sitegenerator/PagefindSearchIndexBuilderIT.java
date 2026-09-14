package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PublicationProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.SearchIndex;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.SystemSitePartition;
import ch.admin.bit.jeap.doc.domain.port.BuiltSearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a real search index with the real Pagefind.
 * <p>
 * Like {@link DocusaurusSiteBuilderIT} it is not conditional: Node is a precondition of this build, and
 * Pagefind arrives with the site template's dependencies. What it proves is the mechanism the whole feature
 * rests on - that an index can be built from <b>records</b>, with no HTML and no Docusaurus anywhere, and that
 * the binary the npm package installs actually runs here.
 */
class PagefindSearchIndexBuilderIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T10:15:30Z"), ZoneOffset.UTC);

    @TempDir
    Path workspaceRoot;

    private PagefindSearchIndexBuilder builder;

    @BeforeEach
    void setUp() {
        BuildProperties properties = new BuildProperties();
        properties.setWorkspaceDirectory(workspaceRoot);
        properties.setNodeModulesDirectory(Path.of("target/site-install/node_modules").toAbsolutePath());
        PublicationProperties publication = new PublicationProperties();
        publication.setUrl("https://doc.example.ch");
        SiteUrls urls = new SiteUrls(publication, "/docs");
        DefaultResourceLoader resources = new DefaultResourceLoader();
        SiteSources sources = new SiteSources(urls, resources,
                NoArchitectureModel.systemPages(urls), new DocumentationSites(new SiteProperties()),
                new SystemSitePartition(NoArchitectureModel.INSTANCE, new NoCustomDocumentation()), properties,
                TestProvenance.of(NoArchitectureModel.INSTANCE), new AboutThisDocumentation());
        builder = new PagefindSearchIndexBuilder(properties, new BuildWorkspaces(properties), sources,
                new NodeProcess(properties), new NoCustomDocumentation(), new NoCustomStorage(), resources,
                CLOCK);
    }

    /**
     * The bundle a reader's browser fetches, file by file. The names are asserted because they are the
     * contract with the client: it loads {@code pagefind.js}, reads {@code pagefind-entry.json} and then asks
     * for chunks and fragments by names those two hand it.
     */
    @Test
    void build_thenTheBundleHoldsWhatTheClientFetches() {
        BuiltSearchIndex index = builder.build(site(), SitePart.wholeSiteOf(site()));

        assertThat(index.records()).isPositive();
        assertThat(bundle(index).resolve("pagefind.js")).exists();
        assertThat(bundle(index).resolve("pagefind-entry.json")).exists();
        assertThat(bundle(index).resolve("index")).isDirectoryContaining(chunk ->
                chunk.getFileName().toString().endsWith(".pf_index"));
        assertThat(bundle(index).resolve("fragment")).isDirectoryContaining(fragment ->
                fragment.getFileName().toString().endsWith(".pf_fragment"));
        assertThat(wasm(index)).describedAs("the engine for the one language this site is in").hasSize(1);
    }

    /** One fragment per page: the fragment is what a result shows, so there is one for every record. */
    @Test
    void build_thenThereIsOneFragmentPerRecord() throws IOException {
        BuiltSearchIndex index = builder.build(site(), SitePart.wholeSiteOf(site()));

        try (Stream<Path> fragments = Files.list(bundle(index).resolve("fragment"))) {
            assertThat(fragments).hasSize(index.records());
        }
    }

    /**
     * Pagefind writes three prebuilt user interfaces and a second engine beside the index. The template brings
     * its own search box and forces English, so publishing them would upload half a megabyte per index that
     * nothing ever fetches.
     */
    @Test
    void build_thenWhatIsNeverServedIsNotInTheBundle() {
        BuiltSearchIndex index = builder.build(site(), SitePart.wholeSiteOf(site()));

        assertThat(PagefindSearchIndexBuilder.NOT_SERVED)
                .allSatisfy(name -> assertThat(bundle(index).resolve(name)).doesNotExist());
    }

    /**
     * The whole site in one index, and every environment tree in it. The index spanning the environments is
     * what lets one query be scoped to the reader's tree by a filter instead of by an index per tree - and the
     * page count is what says no tree was left out.
     */
    @Test
    void build_thenEveryEnvironmentTreeIsIndexed() throws IOException {
        Site site = site();
        Path content = new BuildWorkspaces(propertiesOf(workspaceRoot)).searchIndexWorkspace(site.id())
                .resolve(SiteTemplate.CONTENT_DIRECTORY);

        BuiltSearchIndex index = builder.build(site, SitePart.wholeSiteOf(site));

        assertThat(index.records()).isEqualTo(SearchRecords.of(content, site).size());
        assertThat(SearchRecords.of(content, site)).extracting(SearchRecord::environment)
                .containsAll(site.environments().stream().map(e -> e.id()).toList());
    }

    /** The workspace is scratch space, and a run that has been published has no use for it. */
    @Test
    void discard_thenTheWorkspaceIsGone() {
        BuiltSearchIndex index = builder.build(site(), SitePart.wholeSiteOf(site()));

        builder.discard(index);

        assertThat(index.directory()).doesNotExist();
        assertThat(index.directory().getParent()).doesNotExist();
    }

    /**
     * The index workspace lives under the same root as the build workspaces, and a build pass sweeps that root
     * of everything that is not a running build. The one it has just written survives because it is fresh -
     * see {@link BuildWorkspaces} and the tests that pin it.
     */
    @Test
    void build_thenTheWorkspaceIsNotOneASweepWouldTake() {
        builder.build(site(), SitePart.wholeSiteOf(site()));

        int removed = new BuildWorkspaces(propertiesOf(workspaceRoot)).sweep(java.util.Set.of());

        assertThat(removed).isZero();
    }

    /** Where the bundle itself is: what is published is the directory holding it, keys and URLs alike. */
    private static Path bundle(BuiltSearchIndex index) {
        return index.directory().resolve(SearchIndex.DIRECTORY);
    }

    private static BuildProperties propertiesOf(Path root) {
        BuildProperties properties = new BuildProperties();
        properties.setWorkspaceDirectory(root);
        return properties;
    }

    private static List<Path> wasm(BuiltSearchIndex index) {
        try (Stream<Path> files = Files.list(bundle(index))) {
            return files.filter(file -> file.getFileName().toString().endsWith(".pagefind")).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Site site() {
        return new DocumentationSites(new SiteProperties()).find(Site.DEFAULT_SITE).orElseThrow();
    }
}
