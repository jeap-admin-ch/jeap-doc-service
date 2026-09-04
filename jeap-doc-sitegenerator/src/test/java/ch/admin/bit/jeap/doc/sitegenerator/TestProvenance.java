package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.DocumentationProvenance;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The publishable facts, for the tests of what is written into a site.
 * <p>
 * What those tests are about is the files a run produces, not what the service knows about itself - so this
 * assembles a provenance over the configuration they already have and an import state that is empty. The facts
 * themselves have their own test.
 */
final class TestProvenance {

    private TestProvenance() {
    }

    static DocumentationProvenance of(ArchitectureModelSource architectureModel) {
        return of(new SiteProperties(), architectureModel, new StructureTemplates(List.of()));
    }

    static DocumentationProvenance of(SiteProperties siteProperties, ArchitectureModelSource architectureModel,
                                      StructureTemplates templates) {
        return new DocumentationProvenance(new DocumentationSites(siteProperties), new InMemoryImports(),
                architectureModel, templates, new BuildProperties(), new ArchitectureImportProperties(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC));
    }

    /** An instance whose imports have never run, which is what a fresh one looks like. */
    private static final class InMemoryImports implements ArchitectureImportRepository {

        private final Map<String, ArchitectureImportState> states = new LinkedHashMap<>();

        @Override
        public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
            return states.getOrDefault(environment + "-" + kind,
                    ArchitectureImportState.none(environment, kind));
        }

        @Override
        public List<ArchitectureImportState> states() {
            return new ArrayList<>(states.values());
        }

        @Override
        public void save(ArchitectureImportState state) {
            states.put(state.environment() + "-" + state.kind(), state);
        }
    }
}
