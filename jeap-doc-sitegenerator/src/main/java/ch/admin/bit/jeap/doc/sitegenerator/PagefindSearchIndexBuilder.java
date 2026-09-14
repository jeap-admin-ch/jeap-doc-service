package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.SearchIndex;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.port.BuiltSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationRepository;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexBuilder;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the search index of a site with Pagefind, as a child process of the service.
 * <p>
 * Two things about the shape are the point. It writes <b>the whole site</b> into one content tree in a single
 * pass and indexes that, so a reader inside one system finds a page in another - which is what the site lost
 * when it began to be published one build per system. And it never runs Docusaurus: the index is made of
 * records, not of HTML, so building it costs a content pass and about a second per thousand pages rather than
 * a site generation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagefindSearchIndexBuilder implements SearchIndexBuilder {

    /** The indexer, shipped in the site template's jar beside the Docusaurus application but not part of it. */
    static final String SCRIPT = "pagefind-index.mjs";

    static final String RECORDS_FILE = "records.jsonl";

    /**
     * What is published: a directory holding nothing but {@link SearchIndex#DIRECTORY}.
     * <p>
     * <b>The bundle sits one level down on purpose.</b> Publishing writes a directory's files under a prefix
     * with their paths as their keys, and the site serves a path by looking up that key - so an object of the
     * index has to be keyed {@code pagefind/…}, exactly as the reader asks for it. Pagefind's client resolves
     * every other file relative to where it loaded {@code pagefind.js} from, which is what fixes the name.
     */
    static final String PUBLISH_DIRECTORY = "publish";

    /**
     * What Pagefind writes that we do not serve: its own prebuilt user interfaces, and the engine for a
     * language it could not determine.
     * <p>
     * The template brings its own search box against {@code pagefind.js} and forces English, so none of this
     * is ever fetched - and publishing it would upload half a megabyte per index that nothing reads.
     */
    static final Set<String> NOT_SERVED = Set.of(
            "pagefind-ui.js", "pagefind-ui.css",
            "pagefind-modular-ui.js", "pagefind-modular-ui.css",
            "pagefind-component-ui.js", "pagefind-component-ui.css",
            "pagefind-highlight.js",
            "wasm.unknown.pagefind");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BuildProperties properties;
    private final BuildWorkspaces workspaces;
    private final SiteSources sources;
    private final NodeProcess node;
    private final CustomDocumentationRepository documentation;
    private final CustomDocumentationStorage documentationStorage;
    private final ResourceLoader resources;
    private final Clock clock;

    @Override
    public BuiltSearchIndex build(Site site, SitePart part) {
        long started = System.nanoTime();
        // Under the same root as the build workspaces, and named by BuildWorkspaces, which is what sweeps it:
        // the name cannot be a build id, so no sweep takes it from under a running run, and one that has lain
        // untouched for a day is cleared for a site nothing indexes any more. BuildWorkspacesTest pins both.
        Path workspace = workspaces.searchIndexWorkspace(site.id());
        try {
            BuildWorkspaces.deleteTree(workspace);
            Files.createDirectories(workspace);

            Path content = workspace.resolve(SiteTemplate.CONTENT_DIRECTORY);
            // The build id only ever reaches the page about the documentation, which is indexed as text and
            // read from the published site rather than from here. Zero says "no build wrote this".
            sources.write(0L, site, part, content, clock.instant());

            // The pages of the site, and then the content of the microsites they frame - which is in no
            // content tree, because a microsite is served file by file exactly as it was built.
            List<SearchRecord> records = MicrositeSearchRecords.expand(SearchRecords.of(content, site),
                    documentation.micrositesOf(site.id()), documentationStorage);
            if (records.isEmpty()) {
                throw new SiteBuildException(
                        "The content of " + site.id() + " holds no page, so there is nothing to index.");
            }
            writeRecords(records, workspace.resolve(RECORDS_FILE));

            extractScript(workspace);
            linkDependencies(workspace);
            String bundlePath = PUBLISH_DIRECTORY + "/" + SearchIndex.DIRECTORY;
            String output = node.runAndCapture(workspace, SCRIPT, RECORDS_FILE, bundlePath);
            log.debug("The indexer said: {}", output);

            Path published = workspace.resolve(PUBLISH_DIRECTORY);
            Path bundle = published.resolve(SearchIndex.DIRECTORY);
            if (!Files.isDirectory(bundle)) {
                throw new SiteBuildException("The indexer finished without producing " + bundlePath);
            }
            removeWhatIsNotServed(bundle);
            return new BuiltSearchIndex(published, records.size(), (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            throw new SiteBuildException("The search index of " + site.id() + " could not be built: "
                                         + e.getMessage(), e);
        }
    }

    @Override
    public void discard(BuiltSearchIndex index) {
        // The bundle is inside the workspace, so removing the workspace removes it.
        Path workspace = index.directory().getParent();
        try {
            BuildWorkspaces.deleteTree(workspace);
        } catch (IOException e) {
            log.warn("The search index workspace {} could not be removed; the next run replaces it.",
                    workspace, e);
        }
    }

    /**
     * One JSON object per line. A line rather than one document, because a site of twenty thousand pages is
     * held in memory neither here nor in the indexer this way.
     */
    private static void writeRecords(List<SearchRecord> records, Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (SearchRecord record : records) {
                ObjectNode json = JSON.createObjectNode()
                        .put("url", record.url())
                        .put("title", record.title())
                        .put("content", record.content())
                        .put("environment", record.environment())
                        .put("source", record.source());
                // Absent rather than empty, both of them: a record with no value for a key a query names is
                // excluded by the index, and that is exactly what should happen to the site's own pages when
                // a reader narrows the search to a subject.
                if (record.subject() != null) {
                    json.put("subject", record.subject());
                }
                if (record.system() != null) {
                    json.put("system", record.system());
                }
                if (record.name() != null) {
                    json.put("name", record.name());
                }
                if (record.microsite() != null) {
                    json.put("microsite", record.microsite());
                }
                writer.write(JSON.writeValueAsString(json));
                writer.newLine();
            }
        }
    }

    /**
     * The indexer, out of the template's jar. Only the one file: the workspace runs no Docusaurus application
     * and has no use for one.
     */
    private void extractScript(Path workspace) throws IOException {
        try (InputStream script = resources.getResource(
                "classpath:" + SiteTemplate.ROOT + "/" + SCRIPT).getInputStream()) {
            Files.copy(script, workspace.resolve(SCRIPT), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The same read-only {@code node_modules} the site generator resolves its imports against. */
    private void linkDependencies(Path workspace) throws IOException {
        Path nodeModules = properties.getNodeModulesDirectory();
        if (nodeModules == null) {
            throw new SiteBuildException("jeap.doc.build.node-modules-directory is not configured, so the "
                                         + "indexer cannot resolve pagefind.");
        }
        Files.createSymbolicLink(workspace.resolve(SiteTemplate.NODE_MODULES), nodeModules);
    }

    private static void removeWhatIsNotServed(Path bundle) throws IOException {
        try (Stream<Path> files = Files.list(bundle)) {
            for (Path file : files.filter(f -> NOT_SERVED.contains(f.getFileName().toString())).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
