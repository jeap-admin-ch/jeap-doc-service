package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import java.time.Instant;
import java.util.Optional;

/** One atomically replaced complete snapshot per environment. */
public interface ReactionGraphRepository {
    Optional<ReactionGraphRef> findRef(String environment);
    Optional<StoredReactionGraph> find(String environment);
    void store(StoredReactionGraph graph);
    void confirm(String environment, Instant checkedAt);
}
