package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.db.tx.TransactionalReadReplica;
import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import ch.admin.bit.jeap.doc.domain.port.DisplayReads;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The reads that may come from the read replica. Each one starts its transaction there and asks the adapter
 * that owns the table, which joins it - so the queries and the mapping stay in one place.
 * <p>
 * Without a replica the transaction manager is the primary's, and nothing changes.
 */
@Repository
@RequiredArgsConstructor
class DisplayReadsAdapter implements DisplayReads {

    private final DocumentationBuildRepository builds;
    private final DocumentationBuildRequestRepository requests;
    private final SearchIndexRepository searchIndexes;
    private final ArchitectureImportRepository imports;

    @Override
    @TransactionalReadReplica
    public List<PublishedPart> publishedPartsOf(String site) {
        return builds.publishedPartsOf(site);
    }

    @Override
    @TransactionalReadReplica
    public Optional<PublishedSearchIndex> currentSearchIndexOf(String site) {
        return searchIndexes.currentOf(site);
    }

    @Override
    @TransactionalReadReplica
    public List<DocumentationBuild> recentBuilds(String site, int limit) {
        return builds.recent(site, limit);
    }

    @Override
    @TransactionalReadReplica
    public List<DocumentationBuild> recentBuildsOf(PartKey part, int limit) {
        return builds.recentOf(part, limit);
    }

    @Override
    @TransactionalReadReplica
    public Optional<DocumentationBuild> build(String site, long id) {
        return builds.find(site, id);
    }

    @Override
    @TransactionalReadReplica
    public Optional<DocumentationBuild> publishedBuild(PartKey part) {
        return builds.published(part);
    }

    @Override
    @TransactionalReadReplica
    public List<DocumentationBuild> runningBuilds() {
        return builds.running();
    }

    @Override
    @TransactionalReadReplica
    public List<BuildRequest> pendingRequests() {
        return requests.pending();
    }

    @Override
    @TransactionalReadReplica
    public ArchitectureImportState importState(String environment, ArchitectureImportKind kind) {
        return imports.state(environment, kind);
    }
}
