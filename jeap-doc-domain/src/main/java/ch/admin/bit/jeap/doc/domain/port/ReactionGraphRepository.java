package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import java.time.Instant;
import java.util.Optional;

/**
 * One atomically replaced complete snapshot per environment.
 * It has no foreign keys into the architecture model, which is replaced independently.
 */
public interface ReactionGraphRepository {
    /** Reads metadata without loading the graph bytes for the conditional fetch. */
    Optional<ReactionGraphRef> findRef(String environment);
    Optional<StoredReactionGraph> find(String environment);
    /** Replaces the bytes and metadata together, setting the check time to the import time. */
    void store(StoredReactionGraph graph);
    /** Advances only the check time; the bytes and their import time remain unchanged. */
    void confirm(String environment, Instant checkedAt);
}
