package ch.admin.bit.jeap.doc.domain.governance;

import java.util.List;

/** One scored system, parsed from its stored resource: its own score and history, and its components'. */
public record GovernanceSystem(String name, Integer score, String trend, List<GovernanceDailyScore> history,
                               List<GovernanceComponent> components) {

    public GovernanceSystem {
        history = List.copyOf(history);
        components = List.copyOf(components);
    }
}
