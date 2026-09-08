package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An instance with no architecture repository at all, which is a legitimate one.
 * <p>
 * Every site then has exactly one part - its shell - so a test about builds, locks or housekeeping needs no
 * landscape to have parts. What a model does to the parts of a site is {@link SystemSitePartitionTest}'s
 * business, and what it does to readiness is {@link ArchitectureModelReadinessTest}'s.
 */
class NoArchitectureModel implements ArchitectureModelSource {

    @Override
    public boolean isConfiguredFor(String environment) {
        return false;
    }

    @Override
    public Optional<String> sourceUrlOf(String environment) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> lastSuccessfulImportAt(String environment) {
        return Optional.empty();
    }

    @Override
    public ArchitectureSnapshot read(String environment) {
        return ArchitectureSnapshot.empty();
    }

    @Override
    public List<String> systemSlugsOf(String environment) {
        return List.of();
    }
}
