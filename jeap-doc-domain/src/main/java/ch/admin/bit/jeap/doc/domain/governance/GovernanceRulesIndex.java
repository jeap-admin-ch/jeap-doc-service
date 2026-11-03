package ch.admin.bit.jeap.doc.domain.governance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The rules index, parsed from the stored resource: every known rule, active or not, and the time the scores
 * are as of.
 *
 * @param scoredAt null before the first completed scoring run
 */
public record GovernanceRulesIndex(OffsetDateTime scoredAt, List<GovernanceRuleSummary> rules) {

    public GovernanceRulesIndex {
        rules = List.copyOf(rules);
    }
}
