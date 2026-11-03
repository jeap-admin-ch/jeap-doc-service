package ch.admin.bit.jeap.doc.domain.governance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A component within a rule's violation grace period: its system, comment and details, when the violation was
 * detected and when the grace period ends.
 */
public record GovernanceGracePeriodComponent(String system, String component, String comment,
                                              List<String> details, OffsetDateTime detectedAt,
                                              OffsetDateTime gracePeriodEndsAt) {

    public GovernanceGracePeriodComponent {
        details = List.copyOf(details);
    }
}
