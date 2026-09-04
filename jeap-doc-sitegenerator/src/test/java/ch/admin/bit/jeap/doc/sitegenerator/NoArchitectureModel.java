package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.BuildMetrics;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;

import java.util.List;
import java.util.Optional;

/**
 * An instance with no architecture repository configured - which is a legitimate one, and the state the tests
 * of the site-level files want: they are about what a site says about itself, not about what is documented in
 * it.
 */
public final class NoArchitectureModel implements ArchitectureModelSource {

    /** The one instance needed: it has no state, and every answer of it is the same. */
    static final NoArchitectureModel INSTANCE = new NoArchitectureModel();

    private NoArchitectureModel() {
    }

    public static SystemPages systemPages(SiteUrls urls) {
        return new SystemPages(new NoArchitectureModel(), NoMessageSchemas.INSTANCE, new StructureTemplates(List.of()),
                new GeneratorProperties(), new ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties(),
                BuildMetrics.NONE, urls);
    }

    @Override
    public boolean isConfiguredFor(String environment) {
        return false;
    }

    @Override
    public Optional<String> sourceUrlOf(String environment) {
        return Optional.empty();
    }

    @Override
    public ArchitectureSnapshot read(String environment) {
        throw new IllegalStateException("No architecture repository is configured for " + environment + ".");
    }

    @Override
    public java.util.Optional<java.time.Instant> lastSuccessfulImportAt(String environment) {
        return java.util.Optional.empty();
    }
}
