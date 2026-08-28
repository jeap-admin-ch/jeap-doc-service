package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The directories the documentation builds work in.
 * <p>
 * A workspace is named after its build, and the database says which builds are running - so <b>a directory may be
 * removed when its build is not running</b>, whichever instance created it. That one rule is what makes the
 * clean-up safe while other instances are building, what lets it run at startup as well as before every build,
 * and what gets the leftovers of an instance that never comes back removed by whichever instance builds next.
 * <p>
 * The workspace holds one build's scratch files and outlives nothing, so it belongs on storage that belongs to
 * this container alone: the writable layer of a task on ECS, an {@code emptyDir} on Kubernetes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildWorkspaces {

    private final BuildProperties properties;

    /**
     * A fresh directory for the given build, replacing anything a previous attempt of that build left.
     */
    public Path create(long buildId) throws IOException {
        Path workspace = of(buildId);
        FileSystemUtils.deleteRecursively(workspace);
        Files.createDirectories(workspace);
        return workspace;
    }

    /**
     * Removes the workspace of a build that has finished - unless it is being kept on purpose, which is for
     * reproducing a failure and for nothing else.
     */
    public void discard(long buildId) {
        Path workspace = of(buildId);
        if (properties.isKeepWorkspace()) {
            log.warn("The workspace {} of the build {} is kept because jeap.doc.build.keep-workspace is on. It "
                     + "is a disk leak with a purpose; switch it off when the failure has been found.",
                    workspace, buildId);
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(workspace);
        } catch (IOException e) {
            log.warn("The workspace {} of the build {} could not be removed; the next sweep will get it.",
                    workspace, buildId, e);
        }
    }

    /**
     * Removes every workspace whose build is not among the ones that are still running, and reports how many
     * there were. A build that is running is one whose directory is in use, by this instance or by another.
     */
    public int sweep(Set<Long> runningBuildIds) {
        if (properties.isKeepWorkspace()) {
            // Otherwise the flag keeps a workspace only until the next build, which is exactly the sequence
            // someone reproducing a failure goes through: fail, look, trigger again.
            log.warn("Workspaces are kept ({}), so none are swept. This is a disk leak with a purpose; turn "
                     + "jeap.doc.build.keep-workspace off when the failure has been reproduced.", root());
            return 0;
        }
        Path root = root();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> stale;
        try (Stream<Path> entries = Files.list(root)) {
            stale = entries.filter(entry -> isStale(entry, runningBuildIds)).toList();
        } catch (IOException e) {
            // Nothing is broken by a workspace that stays: it costs disk, and the next sweep tries again.
            log.warn("The build workspaces under {} could not be listed.", root, e);
            return 0;
        }
        int removed = 0;
        for (Path entry : stale) {
            log.info("Removing the workspace {}, left by a build that is no longer running.", entry.getFileName());
            try {
                FileSystemUtils.deleteRecursively(entry);
                removed++;
            } catch (IOException e) {
                log.warn("The workspace {} could not be removed; the next sweep will get it.",
                        entry.getFileName(), e);
            }
        }
        return removed;
    }

    /**
     * The directory of one build.
     */
    public Path of(long buildId) {
        return root().resolve(Long.toString(buildId));
    }

    /**
     * The directory the workspaces live under - configured, or the temporary directory of the JVM, which is
     * right on a developer machine and wrong in a container.
     */
    public Path root() {
        Path configured = properties.getWorkspaceDirectory();
        return configured != null ? configured : Path.of(System.getProperty("java.io.tmpdir"), "jeap-doc-build");
    }

    /**
     * Whether an entry is a workspace whose build is over. Anything that is not one of our workspaces - a file,
     * a directory not named after a build - is left alone: the rule is <i>a workspace may go when its build is
     * not running</i>, and the workspace root is a directory an operator configures and may share.
     */
    private static boolean isStale(Path entry, Set<Long> runningBuildIds) {
        if (!Files.isDirectory(entry)) {
            return false;
        }
        try {
            return !runningBuildIds.contains(Long.parseLong(entry.getFileName().toString()));
        } catch (NumberFormatException e) {
            // Not a workspace of ours: a workspace is named after its build, and a build is a number.
            return false;
        }
    }
}
