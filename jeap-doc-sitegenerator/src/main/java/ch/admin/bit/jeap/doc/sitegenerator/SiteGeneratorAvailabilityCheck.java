package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks while the service starts that it could generate a documentation site at all.
 * <p>
 * A configuration error of an instance should surface in its deployment, not fifteen minutes into the first
 * build - which is the rule the bucket and the spool directory already follow. Everything here is cheap and runs
 * once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteGeneratorAvailabilityCheck implements InitializingBean {

    /** The script the startup check runs to find out whether Node is there and new enough. */
    private static final String VERSION_SCRIPT = "version.mjs";

    /**
     * The oldest Node the doc service runs the site generator on: the current LTS, and deliberately newer than
     * what the template's plugins would still tolerate. An instance of this service is a container someone else
     * builds, and the version it pins should not be one this repository encouraged it to keep.
     * <p>
     * Major and minor, because a floor is sometimes a minor one - a check comparing only the major would let
     * 24.0 through where 24.2 was meant.
     * <p>
     * <b>Change it together with the {@code engines} field of the template's package.json</b>, the base image in
     * {@code docs/site-image.md} and the build image in the {@code Jenkinsfile}: they are one statement written
     * in four places.
     */
    static final int MINIMUM_NODE_MAJOR = 24;
    static final int MINIMUM_NODE_MINOR = 0;

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private final BuildProperties properties;
    private final BuildWorkspaces workspaces;
    private final SiteTemplate template;
    private final NodeProcess node;
    private final DocumentationSites sites;

    /**
     * While the context is built, not once it is ready: an instance that cannot generate a site must not have
     * opened its port and reported itself up first, and the build scheduler starts with the context - so a
     * build could otherwise begin before any of this had been checked.
     */
    @Override
    public void afterPropertiesSet() throws IOException {
        checkWorkspace();
        checkDependencies();
        checkColorSchemes();
        checkNode();
    }

    /**
     * The workspace root has to be writable. Nothing is removed here: the sweep before each build is what clears
     * what is left over, and it only removes directories of builds that are not running - so a starting instance
     * cannot disturb a build another instance is in the middle of.
     */
    private void checkWorkspace() throws IOException {
        Path root = workspaces.root();
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, "startup", ".probe");
            Files.delete(probe);
        } catch (IOException e) {
            throw new IllegalStateException(("The build workspace directory %s cannot be written to. It is set "
                                             + "with jeap.doc.build.workspace-directory and has to be a "
                                             + "directory this container may write to.").formatted(root), e);
        }
        log.info("Documentation builds work in {}.", root);
    }

    /**
     * The dependencies of the site template are installed by the image build, and <b>from the lockfile of the
     * very template this service carries</b>. When the two differ, the site generator would be run against
     * dependency versions its configuration was never written for - which fails deep inside the bundler, with a
     * message that names none of this. Bumping the doc service in an instance without rebuilding its image is
     * exactly what produces it, and that is a thing a rollout does by accident.
     */
    private void checkDependencies() throws IOException {
        Path nodeModules = properties.getNodeModulesDirectory();
        if (nodeModules == null || !Files.isDirectory(nodeModules)) {
            throw new IllegalStateException(("The dependencies of the site template are not at %s. They are "
                                             + "installed by the image build; jeap.doc.build."
                                             + "node-modules-directory has to point at them.").formatted(nodeModules));
        }
        Path installedLockfile = nodeModules.resolveSibling(SiteTemplate.LOCKFILE);
        if (!Files.isRegularFile(installedLockfile)) {
            log.warn("There is no {} beside {}, so it cannot be checked that the installed dependencies are the "
                     + "ones this version of the doc service expects. The image build should keep it.",
                    SiteTemplate.LOCKFILE, nodeModules);
            return;
        }
        String installed = Files.readString(installedLockfile, StandardCharsets.UTF_8);
        if (!installed.equals(template.read(SiteTemplate.LOCKFILE))) {
            throw new IllegalStateException(("The dependencies at %s were installed from a different %s than the "
                                             + "one this doc service carries. The image has to be rebuilt for "
                                             + "this version of the service.")
                    .formatted(nodeModules, SiteTemplate.LOCKFILE));
        }
        log.info("The site template's dependencies at {} match the ones this doc service expects.", nodeModules);
    }

    /**
     * Every configured site asks for a colour scheme by name, and the template resolves that name to a
     * stylesheet. Checked against the stylesheets the template really ships rather than against a list kept
     * beside them: a name with nothing behind it fails the Docusaurus build minutes into a run, with a message
     * that names neither the site nor the property that set it.
     */
    private void checkColorSchemes() throws IOException {
        Set<String> shipped = template.colorSchemes();
        if (shipped.isEmpty()) {
            throw new IllegalStateException(("The site template ships no colour scheme at all - %s is empty or "
                                             + "missing from the jeap-doc-site artifact.")
                    .formatted(SiteTemplate.SCHEMES_DIRECTORY));
        }
        for (Site site : sites.all()) {
            if (!shipped.contains(site.colorScheme())) {
                throw new IllegalStateException(("The site '%s' asks for the colour scheme '%s', which the site "
                                                 + "template does not ship. Available: %s.")
                        .formatted(site.id(), site.colorScheme(), shipped));
            }
        }
        log.info("The site template ships the colour schemes {}.", shipped);
    }

    /**
     * Node has to be there and new enough for what the template's plugins need - and the version it reports is
     * actually compared, because a Node that is too old fails deep inside the bundler on the first build, with a
     * message that names none of this.
     */
    private void checkNode() throws IOException {
        Path probe = Files.createTempDirectory(workspaces.root(), "node-check");
        String version;
        try {
            Files.writeString(probe.resolve(VERSION_SCRIPT),
                    "console.log(process.versions.node)\n", StandardCharsets.UTF_8);
            version = node.runAndCapture(probe, VERSION_SCRIPT).strip();
        } finally {
            Files.deleteIfExists(probe.resolve(VERSION_SCRIPT));
            Files.deleteIfExists(probe);
        }
        requireSupported(version);
        log.info("The site generator runs on Node {} at {}.", version, properties.getNodeCommand());
    }

    /** Whether the reported version is at least the floor, comparing the minor and not only the major. */
    static boolean isSupported(int major, int minor, int minimumMajor, int minimumMinor) {
        return major > minimumMajor || (major == minimumMajor && minor >= minimumMinor);
    }

    static void requireSupported(String version) {
        Matcher reported = VERSION.matcher(version);
        if (!reported.find()) {
            log.warn("Node {}.{} or newer is needed, and the version could not be read from '{}'. The first "
                     + "build will say whether it is new enough.",
                    MINIMUM_NODE_MAJOR, MINIMUM_NODE_MINOR, version);
            return;
        }
        int major = Integer.parseInt(reported.group(1));
        int minor = Integer.parseInt(reported.group(2));
        if (!isSupported(major, minor, MINIMUM_NODE_MAJOR, MINIMUM_NODE_MINOR)) {
            throw new IllegalStateException(("The site template needs Node %d.%d or newer and this is Node %s. "
                                             + "The image of this instance has to carry a newer Node runtime.")
                    .formatted(MINIMUM_NODE_MAJOR, MINIMUM_NODE_MINOR, version));
        }
    }
}
