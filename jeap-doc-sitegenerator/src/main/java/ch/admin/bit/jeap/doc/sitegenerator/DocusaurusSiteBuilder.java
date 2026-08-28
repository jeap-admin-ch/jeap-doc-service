package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.port.BuiltSite;
import ch.admin.bit.jeap.doc.domain.port.SiteBuildException;
import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates the documentation site with Docusaurus, as a child process of the service.
 * <p>
 * The order of a run is the point of this class: the workspace is created, the generated content is written into
 * it, <b>the site template is installed over that content</b> and only then is the generator started. Nothing
 * that was generated can be part of the application that runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocusaurusSiteBuilder implements SiteBuilder {

    /** The generator's own entry point, resolved inside the installed dependencies. */
    static final String DOCUSAURUS_CLI = "node_modules/@docusaurus/core/bin/docusaurus.mjs";

    static final String OUTPUT_DIRECTORY = "build";

    /**
     * The generator's own sub-command. It happens to read the same as {@link #OUTPUT_DIRECTORY} and means
     * something else entirely - one is what to do, the other is where to put the result.
     */
    static final String BUILD_COMMAND = "build";

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
    public BuiltSite generate(long buildId, Site site, Instant generatedAt) {
        try {
            Path workspace = workspaces.create(buildId);

            // 1. the generated content, into the one directory the generator may write into
            sources.write(site, workspace.resolve(SiteTemplate.CONTENT_DIRECTORY), generatedAt);

            // 2. the application, over the top of it
            template.installInto(workspace);
            linkDependencies(workspace);

            // 3. the site generator itself
            long start = System.nanoTime();
            node.run(workspace, DOCUSAURUS_CLI, BUILD_COMMAND, "--out-dir", OUTPUT_DIRECTORY);
            long docusaurusMillis = (System.nanoTime() - start) / 1_000_000;

            Path output = workspace.resolve(OUTPUT_DIRECTORY);
            if (!Files.isDirectory(output)) {
                throw new SiteBuildException("The site generator finished without producing " + OUTPUT_DIRECTORY);
            }
            return new BuiltSite(output, countPages(output), sizeOf(output), docusaurusMillis);
        } catch (IOException e) {
            throw new SiteBuildException("The build workspace could not be prepared: " + e.getMessage(), e);
        }
    }

    @Override
    public void discard(long buildId) {
        workspaces.discard(buildId);
    }

    @Override
    public int sweepWorkspaces(Set<Long> runningBuildIds) {
        return workspaces.sweep(runningBuildIds);
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

    private static int countPages(Path output) {
        try (Stream<Path> files = Files.walk(output)) {
            return (int) files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".html"))
                    .count();
        } catch (IOException e) {
            log.error("The pages of the generated site could not be counted.", e);
            return 0;
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
            log.error("The size of the generated site could not be measured.", e);
            return 0;
        }
    }
}
