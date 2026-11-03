package ch.admin.bit.jeap.doc.domain.governance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The state of one rule for one component, as the Governance Service delivers it.
 *
 * @param state       {@code OK}, {@code FAIL}, {@code PAUSED}, {@code DISABLED} or {@code UNKNOWN}
 * @param comment     the first line of the rule's comment, plain text; null without one
 * @param details     the further lines, one item each, plain text
 */
public record GovernanceRuleState(String ruleId, String name, String state, String comment, List<String> details,
                                  OffsetDateTime lastStatusChange) {

    public GovernanceRuleState {
        details = List.copyOf(details);
    }
}
