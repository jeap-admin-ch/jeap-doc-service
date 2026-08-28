package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildRequest;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The pending build requests, on PostgreSQL. One row per site while a build is wanted, and no row otherwise.
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
    public boolean request(String site, BuildTrigger trigger, Instant now) {
        boolean started = requests.requestIfAbsent(site, now, trigger.name()) == 1;
        if (started) {
            log.debug("A build of the site {} was asked for by {}.", site, trigger);
        }
        return started;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BuildRequest> pending() {
        return requests.findAllByOrderByRequestedAtAsc().stream()
                .map(entity -> new BuildRequest(entity.getSite(), entity.getRequestedAt(), entity.getTrigger()))
                .toList();
    }

    /**
     * Reads the request and clears it in one transaction. The delete decides: an instance whose delete hit no row
     * lost the request to someone else and must not build, or it would run a second build over the same inputs.
     */
    @Override
    @Transactional
    public Optional<BuildTrigger> claim(String site) {
        Optional<BuildTrigger> trigger = requests.findById(site).map(DocumentationBuildRequestEntity::getTrigger);
        if (trigger.isEmpty()) {
            return Optional.empty();
        }
        return requests.clear(site) == 1 ? trigger : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> pendingSince(String site) {
        return requests.findById(site).map(DocumentationBuildRequestEntity::getRequestedAt);
    }
}
