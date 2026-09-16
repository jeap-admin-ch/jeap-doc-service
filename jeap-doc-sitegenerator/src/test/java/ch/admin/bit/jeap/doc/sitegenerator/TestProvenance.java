package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.DocumentationProvenance;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SiteProperties;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.custom.CustomProperties;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.template.StructureTemplates;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return of(siteProperties, architectureModel, templates, new InMemoryImports());
    }

    /** The same, over import state a test can move - which is what the live status is read from. */
    static DocumentationProvenance of(SiteProperties siteProperties, ArchitectureModelSource architectureModel,
                                      StructureTemplates templates, ArchitectureImportRepository imports) {
        return new DocumentationProvenance(new DocumentationSites(siteProperties), imports,
                new ImportStates(imports), architectureModel, templates, new BuildProperties(),
                new ArchitectureImportProperties(), new UploadProperties(), new CustomProperties(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC));
    }

    /** The one display read a provenance asks for, answered by the same import state. */
    private record ImportStates(ArchitectureImportRepository imports) implements DisplayReads {

        @Override
        public ArchitectureImportState importState(String environment, ArchitectureImportKind kind) {
            return imports.state(environment, kind);
        }

        @Override
        public List<PublishedPart> publishedPartsOf(String site) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PublishedSearchIndex> currentSearchIndexOf(String site) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentationBuild> recentBuilds(String site, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentationBuild> recentBuildsOf(PartKey part, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DocumentationBuild> build(String site, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DocumentationBuild> publishedBuild(PartKey part) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DocumentationBuild> runningBuilds() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BuildRequest> pendingRequests() {
            throw new UnsupportedOperationException();
        }
    }

    /** An instance whose imports have never run, which is what a fresh one looks like. */
    static final class InMemoryImports implements ArchitectureImportRepository {

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
