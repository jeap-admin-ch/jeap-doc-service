package ch.admin.bit.jeap.doc.domain.governance;

import java.time.LocalDate;

/**
 * A component currently failing a rule, as the rule page lists it: its system and the day it has been
 * non-compliant since.
 */
public record GovernanceNonCompliantComponent(String system, String component, LocalDate since) {
}
