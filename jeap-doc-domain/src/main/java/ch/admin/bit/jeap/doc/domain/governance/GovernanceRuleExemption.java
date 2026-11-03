package ch.admin.bit.jeap.doc.domain.governance;

import java.time.LocalDate;
import java.util.List;

/**
 * A component's exemption from a rule, as the rule resource carries it - the rules overview folds these up per
 * rule; the rule page itself does not show them.
 *
 * @param until  null for an exemption with no end
 * @param effect {@code PAUSED} (an {@code until} day not yet passed), {@code DISABLED} (no {@code until}) or
 *               {@code EXPIRED} (the {@code until} day has passed)
 */
public record GovernanceRuleExemption(String id, String component, String reason, LocalDate until, String effect,
                                      List<GovernanceRuleParameter> parameters) {

    public GovernanceRuleExemption {
        parameters = List.copyOf(parameters);
    }
}
