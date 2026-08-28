package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The version gate of the site generator.
 * <p>
 * It is the only thing standing between an instance with too old a Node and a failure deep inside the bundler
 * on the first build, a quarter of an hour into a deployment that already looked successful. Worth testing
 * exactly because nothing else exercises it until then.
 */
class SiteGeneratorAvailabilityCheckTest {

    @org.junit.jupiter.api.io.TempDir
    Path workspaceRoot;


    @ParameterizedTest
    @ValueSource(strings = {"24.0.0", "24.16.0", "25.1.0", "30.0.0"})
    void requireSupported_whenNodeIsNewEnough_thenItPasses(String version) {
        assertThatCode(() -> SiteGeneratorAvailabilityCheck.requireSupported(version)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20.9.0", "22.21.1", "23.11.0"})
    void requireSupported_whenNodeIsTooOld_thenTheReasonNamesBothVersions(String version) {
        assertThatThrownBy(() -> SiteGeneratorAvailabilityCheck.requireSupported(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(SiteGeneratorAvailabilityCheck.MINIMUM_NODE_MAJOR))
                .hasMessageContaining(version);
    }

    /**
     * A minor floor has to be compared, or a check would let through exactly the versions it was raised for.
     */
    @Test
    void isSupported_thenTheMinorIsComparedAndNotOnlyTheMajor() {
        // Asserted against fixed numbers rather than against the constants: with a minor floor of 0 a test
        // written in terms of them would pass with the minor comparison deleted.
        assertThat(SiteGeneratorAvailabilityCheck.isSupported(24, 1, 24, 2)).isFalse();
        assertThat(SiteGeneratorAvailabilityCheck.isSupported(24, 2, 24, 2)).isTrue();
        assertThat(SiteGeneratorAvailabilityCheck.isSupported(24, 3, 24, 2)).isTrue();
        assertThat(SiteGeneratorAvailabilityCheck.isSupported(25, 0, 24, 2)).isTrue();
        assertThat(SiteGeneratorAvailabilityCheck.isSupported(23, 9, 24, 0)).isFalse();
    }

    /**
     * A version that cannot be read is not a reason to refuse to start: the build says soon enough, and an
     * instance that would have worked should not be stopped by a parser.
     */
    @Test
    void requireSupported_whenTheVersionCannotBeRead_thenItIsLetThrough() {
        assertThatCode(() -> SiteGeneratorAvailabilityCheck.requireSupported("not a version"))
                .doesNotThrowAnyException();
    }
    /**
     * The colour scheme is the one a typo is most likely to hit: {@code DocumentationSites} accepts any
     * non-blank name, so this check is all that stands between it and a Docusaurus failure minutes into a run
     * that names neither the site nor the property.
     */
    @Test
    void afterPropertiesSet_whenASiteAsksForASchemeTheTemplateDoesNotShip_thenTheStartupFails() throws IOException {
        SiteProperties siteProperties = new SiteProperties();
        SiteProperties.Site configured = new SiteProperties.Site();
        configured.setColorScheme("corporate");
        siteProperties.setSites(Map.of(Site.DEFAULT_SITE, configured));

        assertThatThrownBy(() -> checkWith(siteProperties, installedDependencies()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Site.DEFAULT_SITE)
                .hasMessageContaining("corporate")
                .hasMessageContaining("jeap");
    }

    /**
     * The dependencies are installed by the image build. Without them the service cannot generate anything, and
     * the reason has to name the property that points at them.
     */
    @Test
    void afterPropertiesSet_whenTheDependenciesAreNotThere_thenTheStartupFails() {
        BuildProperties properties = buildProperties();
        properties.setNodeModulesDirectory(workspaceRoot.resolve("nowhere"));

        assertThatThrownBy(() -> checkWith(new SiteProperties(), properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jeap.doc.build.node-modules-directory");
    }

    /**
     * The dependencies have to have been installed from the lockfile this service carries. Bumping the doc
     * service in an instance without rebuilding its image is what produces the mismatch, and a rollout does
     * that by accident.
     */
    @Test
    void afterPropertiesSet_whenTheDependenciesCameFromADifferentLockfile_thenTheStartupFails() throws IOException {
        BuildProperties properties = installedDependencies();
        Files.writeString(properties.getNodeModulesDirectory().resolveSibling(SiteTemplate.LOCKFILE),
                "{\"name\": \"something-else\"}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> checkWith(new SiteProperties(), properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebuilt");
    }

    /**
     * A missing lockfile beside the dependencies cannot be checked, and that is a warning rather than a refusal
     * to start: an image that does not keep it is still an image that may work.
     */
    @Test
    void afterPropertiesSet_whenThereIsNoLockfileToCompare_thenItStartsAnyway() throws IOException {
        BuildProperties properties = installedDependencies();
        Files.delete(properties.getNodeModulesDirectory().resolveSibling(SiteTemplate.LOCKFILE));

        assertThatCode(() -> checkWith(new SiteProperties(), properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private BuildProperties buildProperties() {
        BuildProperties properties = new BuildProperties();
        properties.setWorkspaceDirectory(workspaceRoot);
        return properties;
    }

    /** A node_modules directory with the lockfile this service carries beside it, as the image build leaves it. */
    private BuildProperties installedDependencies() throws IOException {
        BuildProperties properties = buildProperties();
        Path nodeModules = workspaceRoot.resolve("install").resolve(SiteTemplate.NODE_MODULES);
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolveSibling(SiteTemplate.LOCKFILE),
                new SiteTemplate().read(SiteTemplate.LOCKFILE), StandardCharsets.UTF_8);
        properties.setNodeModulesDirectory(nodeModules);
        return properties;
    }

    private SiteGeneratorAvailabilityCheck checkWith(SiteProperties sites, BuildProperties properties) {
        return new SiteGeneratorAvailabilityCheck(properties, new BuildWorkspaces(properties), new SiteTemplate(),
                new NodeProcess(properties), new DocumentationSites(sites));
    }

}
