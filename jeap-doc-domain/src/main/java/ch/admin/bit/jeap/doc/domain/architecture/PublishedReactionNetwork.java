package ch.admin.bit.jeap.doc.domain.architecture;

import java.time.Instant;
import java.util.List;

/** One complete snapshot and the ownership that can be established for its reactions. */
public record PublishedReactionNetwork(ReactionNetwork graph, Instant importedAt, List<Ownership> ownership,
                                       boolean ownershipComplete) {
    public PublishedReactionNetwork {
        ownership = List.copyOf(ownership);
    }

    public record Ownership(ReactionNetwork.Identity reaction, String system, String component) {}
}
