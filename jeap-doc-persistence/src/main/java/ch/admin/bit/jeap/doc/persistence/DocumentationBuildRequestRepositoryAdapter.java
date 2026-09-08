package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Publication;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The pending build requests, on PostgreSQL. One row per part while a build of it is wanted, and no row
 * otherwise.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class DocumentationBuildRequestRepositoryAdapter implements DocumentationBuildRequestRepository {

    private final DocumentationBuildRequestJpaRepository requests;

    /**
     * One statement, so that two triggers arriving at the same moment are one request rather than one request
     * and one failed transaction, and so that what an ask adds cannot be lost to another instance claiming the
     * row in between - see {@link DocumentationBuildRequestJpaRepository#requestOrJoin}.
     */
    @Override
    @Transactional
    public boolean request(PartKey part, BuildTrigger trigger, Instant now, Publication publication,
                           boolean forced) {
        boolean created = requests.requestOrJoin(part.site(), part.part(), now, trigger.name(),
                publication == null ? null : publication.id(),
                publication == null ? null : publication.requestedAt(), forced);
        if (created) {
            log.debug("A build of {} was asked for by {}.", part, trigger);
        } else {
            // Everything about the request it joined describes who asked first - except the two things the
            // statement merges, which are about what the build it leads to has to achieve: that it may not be
            // skipped, and which publication it belongs to.
            log.debug("A build of {} was already pending; the {} trigger joins it{}.", part, trigger,
                    forced ? " and it may no longer be skipped" : "");
        }
        return created;
    }

    @Override
    @Transactional
    public int requestAll(List<PartKey> parts, BuildTrigger trigger, Instant now, Publication publication,
                          boolean forced) {
        int created = 0;
        for (PartKey part : parts) {
            if (request(part, trigger, now, publication, forced)) {
                created++;
            }
        }
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BuildRequest> pending() {
        return requests.findAllByOrderByRequestedAtAsc().stream()
                .map(DocumentationBuildRequestRepositoryAdapter::toDomain)
                .toList();
    }

    /**
     * Reads the request and clears it in one transaction. The delete decides: an instance whose delete hit no row
     * lost the request to someone else and must not build, or it would run a second build over the same inputs.
     */
    @Override
    @Transactional
    public Optional<BuildRequest> claim(PartKey part) {
        Optional<BuildRequest> request = requests
                .findById(new DocumentationBuildRequestEntity.RequestId(part.site(), part.part()))
                .map(DocumentationBuildRequestRepositoryAdapter::toDomain);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        return requests.clear(part.site(), part.part()) == 1 ? request : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> pendingSince(String site) {
        return requests.oldestRequestOf(site);
    }

    @Override
    @Transactional(readOnly = true)
    public int pendingCount(String site) {
        return requests.countOfSite(site);
    }

    private static BuildRequest toDomain(DocumentationBuildRequestEntity entity) {
        return new BuildRequest(PartKey.of(entity.getId().site(), entity.getId().part()),
                entity.getRequestedAt(), entity.getTrigger(), publicationOf(entity), entity.isForced());
    }

    private static Publication publicationOf(DocumentationBuildRequestEntity entity) {
        return entity.getPublicationId() == null
                ? null
                : new Publication(entity.getPublicationId(), entity.getPublicationRequestedAt());
    }
}
