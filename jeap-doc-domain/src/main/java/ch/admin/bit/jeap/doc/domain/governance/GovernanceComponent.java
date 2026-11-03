package ch.admin.bit.jeap.doc.domain.governance;

import java.util.List;

/** One component of a scored system, parsed from the system resource. */
public record GovernanceComponent(String name, Integer score, String trend, List<GovernanceDailyScore> history,
                                  List<GovernanceRuleState> ruleStates) {

    public GovernanceComponent {
        history = List.copyOf(history);
        ruleStates = List.copyOf(ruleStates);
    }
}
