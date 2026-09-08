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
     * and one failed transaction - see {@link DocumentationBuildRequestJpaRepository#requestIfAbsent}.
     */
    @Override
    @Transactional
    public boolean request(PartKey part, BuildTrigger trigger, Instant now, Publication publication,
                           boolean forced) {
        boolean started = requests.requestIfAbsent(part.site(), part.part(), now, trigger.name(),
                publication == null ? null : publication.id(),
                publication == null ? null : publication.requestedAt(), forced) == 1;
        if (started) {
            log.debug("A build of {} was asked for by {}.", part, trigger);
            return true;
        }
        // The ask joined a request that was already pending. Everything about that request describes who
        // asked first - except these two, which are about what the build it leads to has to achieve.
        if (forced && requests.force(part.site(), part.part()) == 1) {
            // The flag is raised on that row rather than a second one written, which the primary key forbids
            // anyway - and it is why forcing a publication reaches the parts an import already asked for.
            log.debug("A build of {} was already pending and may no longer be skipped.", part);
        }
        if (publication != null && requests.adoptIntoPublication(part.site(), part.part(), publication.id(),
                publication.requestedAt()) == 1) {
            // The part is built by this publication's pass, so it is one of its parts. Left out of it, the
            // publication reads as finished while this part is still owed a build.
            log.debug("A build of {} was already pending and joins the publication {}.", part,
                    publication.id());
        }
        return false;
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
