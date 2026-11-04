package ch.admin.bit.jeap.doc.domain.architecture;

import java.time.Instant;
import java.util.List;

/** One complete snapshot and the ownership that can be established for its reactions. */
public record PublishedReactions(ObservedReactions observations, Instant importedAt, List<Ownership> ownership,
                                       List<MessageOwnership> messageOwnership, boolean ownershipComplete) {
    public PublishedReactions {
        ownership = List.copyOf(ownership);
        messageOwnership = List.copyOf(messageOwnership);
    }

    public record Ownership(ObservedReactions.Identity reaction, String system, String component) {}
    public record MessageOwnership(String message, String system) {}
}
