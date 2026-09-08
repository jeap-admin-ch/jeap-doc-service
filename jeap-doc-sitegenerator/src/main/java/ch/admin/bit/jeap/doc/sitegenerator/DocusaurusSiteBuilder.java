package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.PreparedPart;
import ch.admin.bit.jeap.doc.domain.port.DocumentationStatus;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates one part of a documentation site with Docusaurus, as a child process of the service.
 * <p>
 * The order of a run is the point of this class: the workspace is created, the generated content is written into
 * it, <b>the site template is installed over that content</b> and only then is the generator started. Nothing
 * that was generated can be part of the application that runs.
 * <p>
 * And it is two steps rather than one. Writing a part's content is seconds and generating its site is minutes,
 * so the content is written and hashed first: a part whose content is what is already published needs no
 * generator run at all, which is what makes a part per system affordable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocusaurusSiteBuilder implements SiteBuilder {

    /** The generator's own entry point, resolved inside the installed dependencies. */
    static final String DOCUSAURUS_CLI = "node_modules/@docusaurus/core/bin/docusaurus.mjs";

    static final String OUTPUT_DIRECTORY = "build";

    /** Writes the numbers of a run as JSON, which a browser and whoever opens the file both read. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The generator's own sub-command. It happens to read the same as {@link #OUTPUT_DIRECTORY} and means
     * something else entirely - one is what to do, the other is where to put the result.
     */
    static final String BUILD_COMMAND = "build";

    /**
     * What each environment of a prepared part contributed, until that build is over.
     * <p>
     * It is read once, by the build that prepared it, and removed with the workspace. Held here rather than on
     * {@link PreparedPart} because it is the site generator's own bookkeeping - the domain hands the prepared
     * part back to be generated and has no use for the counts on the way.
     */
    private final Map<Long, Map<String, EnvironmentModel>> preparedModels = new ConcurrentHashMap<>();

    private final BuildProperties properties;
    private final BuildWorkspaces workspaces;
    private final SiteTemplate template;
    private final NodeProcess node;
    private final SiteSources sources;

    @Override
    public void abortCurrentBuild() {
        node.abort();
    }

    @Override
    public PreparedPart prepare(long buildId, Site site, SitePart part, Instant generatedAt) {
        try {
            Path workspace = workspaces.create(buildId);
            WrittenContent written = write(buildId, site, part, workspace, generatedAt);
            String digest = ContentDigest.of(workspace.resolve(SiteTemplate.CONTENT_DIRECTORY),
                    written.volatileTimestamps(), DocServiceVersion.get());
            preparedModels.put(buildId, written.models());
            return new PreparedPart(buildId, part, workspace, digest);
        } catch (IOException e) {
            throw new SiteBuildException("The build workspace could not be prepared: " + e.getMessage(), e);
        }
    }

    @Override
    public BuiltSite generate(PreparedPart prepared) {
        try {
            Path workspace = prepared.workspace();

            // The application, over the top of the content that is already there.
            template.installInto(workspace);
            linkDependencies(workspace);

            // And then the site generator itself.
            long start = System.nanoTime();
            node.run(workspace, DOCUSAURUS_CLI, BUILD_COMMAND, "--out-dir", OUTPUT_DIRECTORY);
            long docusaurusMillis = (System.nanoTime() - start) / 1_000_000;

            Path output = workspace.resolve(OUTPUT_DIRECTORY);
            if (!Files.isDirectory(output)) {
                throw new SiteBuildException("The site generator finished without producing " + OUTPUT_DIRECTORY);
            }
            return new BuiltSite(output, countPages(output), sizeOf(output), docusaurusMillis,
                    systemsPerEnvironment(preparedModels.getOrDefault(prepared.buildId(), Map.of())));
        } catch (IOException e) {
            throw new SiteBuildException("The site template could not be installed: " + e.getMessage(), e);
        }
    }

    private WrittenContent write(long buildId, Site site, SitePart part, Path workspace, Instant generatedAt)
            throws IOException {
        return sources.write(buildId, site, part, workspace.resolve(SiteTemplate.CONTENT_DIRECTORY),
                generatedAt);
    }

    @Override
    public void discard(long buildId) {
        preparedModels.remove(buildId);
        workspaces.discard(buildId);
    }

    @Override
    public int sweepWorkspaces(Set<Long> runningBuildIds) {
        return workspaces.sweep(runningBuildIds);
    }

    /**
     * Writes the numbers of the run into the site it produced, as a file of the site's root.
     * <p>
     * Into the output directory rather than into {@code content/static}, which Docusaurus copies while it
     * builds: at that point the numbers do not exist yet. This runs after the generator and before the upload,
     * which is the only moment they do and the site is still on local disk.
     * <p>
     * A failure here is logged and swallowed. The site is generated and about to be published; one table
     * missing from one page is not worth losing it.
     */
    @Override
    public void describeRun(BuiltSite generated, DocumentationStatus status) {
        Path file = generated.directory().resolve(AboutThisDocumentation.STATUS_FILE);
        try {
            Files.writeString(file, JSON.writeValueAsString(status), StandardCharsets.UTF_8);
            log.debug("Wrote what the run cost into {}.", file);
        } catch (IOException | RuntimeException e) {
            log.warn("What the run cost could not be written to {}, so the page describing the documentation "
                     + "will not show it. The site is published all the same.", file, e);
        }
    }

    /**
     * The installed dependencies are linked rather than copied: they are tens of thousands of files and are the
     * same for every run, so a copy would be the slowest part of a fast build. They stay read-only - the service
     * cannot modify its own toolchain.
     */
    private void linkDependencies(Path workspace) throws IOException {
        Path nodeModules = properties.getNodeModulesDirectory();
        if (nodeModules == null) {
            throw new SiteBuildException(
                    "jeap.doc.build.node-modules-directory is not configured, so the site generator has no "
                    + "dependencies to build against.");
        }
        Files.createSymbolicLink(workspace.resolve(SiteTemplate.NODE_MODULES), nodeModules.toAbsolutePath());
    }

    /**
     * How many systems each environment documents, which is what the meters read. The rest of what the run
     * counted is the business of the page that describes the documentation.
     */
    private static Map<String, Integer> systemsPerEnvironment(Map<String, EnvironmentModel> models) {
        Map<String, Integer> systems = new LinkedHashMap<>();
        models.forEach((environment, model) -> systems.put(environment, model.systemCount()));
        return systems;
    }

    /**
     * Both counts fail the build rather than answering zero.
     * <p>
     * Zero is what an empty site measures, and these numbers go on the page describing the documentation, into
     * the build row and onto two gauges - where nothing afterwards can tell "no pages" from "the pages could
     * not be counted". A walk that fails over the directory the generator has just written is a broken build,
     * and the build is what should say so.
     */
    private static int countPages(Path output) {
        try (Stream<Path> files = Files.walk(output)) {
            return (int) files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".html"))
                    .count();
        } catch (IOException | UncheckedIOException e) {
            // UncheckedIOException as well: Files.walk declares IOException only for the starting file, and an
            // error part way down the tree arrives from the stream pipeline unchecked. sizeOf catches both.
            throw new SiteBuildException("The pages of the generated site at " + output
                                         + " could not be counted: " + e.getMessage(), e);
        }
    }

    private static long sizeOf(Path output) {
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        } catch (IOException | UncheckedIOException e) {
            throw new SiteBuildException("The size of the generated site at " + output
                                         + " could not be measured: " + e.getMessage(), e);
        }
    }
}
