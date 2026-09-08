package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRequestRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The standing build requests, in memory, for the tests in which more than one runner works the same queue.
 * <p>
 * The one thing it has to get right is what makes the database version correct: <b>claiming is atomic</b>, so
 * of two instances reaching the same part exactly one is told to build it.
 */
public class StandingBuildRequests implements DocumentationBuildRequestRepository {

    private final Map<PartKey, BuildRequest> pending = new ConcurrentHashMap<>();

    @Override
    public boolean request(PartKey part, BuildTrigger trigger, Instant now, Publication publication,
                           boolean forced) {
        BuildRequest standing = pending.putIfAbsent(part,
                new BuildRequest(part, now, trigger, publication, forced));
        if (standing == null) {
            return true;
        }
        // Like the database version: the request keeps everything about who asked first, while an ask that may
        // not be skipped raises that flag on it and an ask belonging to a publication puts it into one.
        Publication joined = standing.publication() == null ? publication : standing.publication();
        if ((forced && !standing.forced()) || joined != standing.publication()) {
            pending.replace(part, standing, new BuildRequest(standing.part(), standing.requestedAt(),
                    standing.trigger(), joined, forced || standing.forced()));
        }
        return false;
    }

    @Override
    public List<BuildRequest> pending() {
        List<BuildRequest> standing = new ArrayList<>(pending.values());
        standing.sort(Comparator.comparing(BuildRequest::requestedAt));
        return standing;
    }

    @Override
    public Optional<BuildRequest> claim(PartKey part) {
        return Optional.ofNullable(pending.remove(part));
    }

    @Override
    public Optional<Instant> pendingSince(String site) {
        return pending().stream()
                .filter(request -> request.part().site().equals(site))
                .map(BuildRequest::requestedAt)
                .findFirst();
    }

    @Override
    public int pendingCount(String site) {
        return (int) pending.keySet().stream().filter(part -> part.site().equals(site)).count();
    }
}
