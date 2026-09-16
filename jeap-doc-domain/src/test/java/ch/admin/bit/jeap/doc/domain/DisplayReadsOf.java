package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;

import java.util.List;
import java.util.Optional;

/** The display reads, answered by the repositories a test already has - as the adapter answers them. */
public record DisplayReadsOf(DocumentationBuildRepository builds, DocumentationBuildRequestRepository requests,
                             SearchIndexRepository searchIndexes, ArchitectureImportRepository imports)
        implements DisplayReads {

    @Override
    public List<PublishedPart> publishedPartsOf(String site) {
        return builds.publishedPartsOf(site);
    }

    @Override
    public Optional<PublishedSearchIndex> currentSearchIndexOf(String site) {
        return searchIndexes.currentOf(site);
    }

    @Override
    public List<DocumentationBuild> recentBuilds(String site, int limit) {
        return builds.recent(site, limit);
    }

    @Override
    public List<DocumentationBuild> recentBuildsOf(PartKey part, int limit) {
        return builds.recentOf(part, limit);
    }

    @Override
    public Optional<DocumentationBuild> build(String site, long id) {
        return builds.find(site, id);
    }

    @Override
    public Optional<DocumentationBuild> publishedBuild(PartKey part) {
        return builds.published(part);
    }

    @Override
    public List<DocumentationBuild> runningBuilds() {
        return builds.running();
    }

    @Override
    public List<BuildRequest> pendingRequests() {
        return requests.pending();
    }

    @Override
    public ArchitectureImportState importState(String environment, ArchitectureImportKind kind) {
        return imports.state(environment, kind);
    }
}
