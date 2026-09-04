package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a site may be published before its architecture model has ever been imported.
 * <p>
 * The window this covers lasts from an instance starting until its first import finishes. Postponing the build
 * costs nothing - the request stays standing and the next poll tries again - where failing it would consume the
 * request and leave the site unpublished until something asked again.
 */
class ArchitectureModelReadinessTest {

    @Test
    void isReadyToBuild_whenTheSiteRequiresTheModelAndItHasNeverBeenImported_thenNotYet() {
        ArchitectureModelReadiness readiness = new ArchitectureModelReadiness(
                new StubSource(Set.of("dev"), Optional.empty()));

        assertThat(readiness.isReadyToBuild(site(true, "dev"))).isFalse();
    }

    @Test
    void isReadyToBuild_whenTheModelHasBeenImported_thenYes() {
        ArchitectureModelReadiness readiness = new ArchitectureModelReadiness(
                new StubSource(Set.of("dev"), Optional.of(Instant.parse("2026-08-28T08:00:00Z"))));

        assertThat(readiness.isReadyToBuild(site(true, "dev"))).isTrue();
    }

    /**
     * A site that says it does not need the model is not held back by one, and not having one is then the
     * configuration working rather than something to report.
     */
    @Test
    void isReadyToBuild_whenTheSiteDoesNotRequireTheModel_thenYesEvenWithoutOne() {
        ArchitectureModelReadiness readiness = new ArchitectureModelReadiness(
                new StubSource(Set.of("dev"), Optional.empty()));

        assertThat(readiness.isReadyToBuild(site(false, "dev"))).isTrue();
    }

    /**
     * The flag only has an effect where an architecture repository is configured. A site documenting from
     * other sources configures none and never meets it, which is what makes requiring the model a safe default.
     */
    @Test
    void isReadyToBuild_whenNoArchitectureRepositoryIsConfiguredForTheEnvironment_thenYes() {
        ArchitectureModelReadiness readiness = new ArchitectureModelReadiness(
                new StubSource(Set.of(), Optional.empty()));

        assertThat(readiness.isReadyToBuild(site(true, "dev"))).isTrue();
    }

    @Test
    void isReadyToBuild_whenOneOfSeveralEnvironmentsHasNoModel_thenNotYet() {
        ArchitectureModelReadiness readiness = new ArchitectureModelReadiness(
                new StubSource(Set.of("prod"), Optional.empty()));

        assertThat(readiness.isReadyToBuild(site(true, "dev", "prod"))).isFalse();
    }

    private static Site site(boolean architectureModelRequired, String... environments) {
        List<SiteEnvironment> declared = new ArrayList<>();
        for (int index = 0; index < environments.length; index++) {
            declared.add(new SiteEnvironment(environments[index], environments[index], environments[index],
                    index, index == 0, index == 0));
        }
        return new Site(Site.DEFAULT_SITE, "Documentation", null, null, null, "classic", declared, null, true,
                architectureModelRequired);
    }

    private record StubSource(Set<String> configured, Optional<Instant> lastSuccess)
            implements ArchitectureModelSource {

        @Override
        public boolean isConfiguredFor(String environment) {
            return configured.contains(environment);
        }

        @Override
        public Optional<String> sourceUrlOf(String environment) {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> lastSuccessfulImportAt(String environment) {
            return lastSuccess;
        }

        @Override
        public ArchitectureSnapshot read(String environment) {
            return ArchitectureSnapshot.empty();
        }
    }
}
