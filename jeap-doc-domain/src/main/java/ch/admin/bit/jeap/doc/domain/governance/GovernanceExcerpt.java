package ch.admin.bit.jeap.doc.domain.governance;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the governance excerpt on a system's and a component's architecture page shows: the system's score, and
 * per component its score and the rules it fails - read from what the governance import stored, never from the
 * Governance Service.
 * <p>
 * Every link is a path below the context path of the service, into the governance site, and is null where the
 * site has no such page.
 *
 * @param system        the system's name, as the governance data has it
 * @param link          the system's page on the governance site
 * @param rulesOverview the rules overview of the governance site
 * @param scoredAt      when the scoring run the scores are of completed; null before the first one
 * @param history       the last {@link GovernanceExcerpts#HISTORY_DAYS} days of the score history, oldest first
 */
public record GovernanceExcerpt(String system, String link, String rulesOverview, OffsetDateTime scoredAt,
                                Integer score, String trend, List<GovernanceDailyScore> history,
                                List<Component> components) {

    public GovernanceExcerpt {
        history = List.copyOf(history);
        components = List.copyOf(components);
    }

    /**
     * One component of the system.
     *
     * @param compliant        how many rules it complies with ({@code OK})
     * @param pausedOrDisabled how many rules are paused or disabled for it; a rule in the state {@code UNKNOWN}
     *                         is counted nowhere
     * @param failingRules     the rules in the state {@code FAIL}, in the order the governance data lists them
     */
    public record Component(String name, String link, Integer score, String trend, List<GovernanceDailyScore> history,
                            int compliant, int pausedOrDisabled, List<FailingRule> failingRules) {
        public Component {
            history = List.copyOf(history);
            failingRules = List.copyOf(failingRules);
        }
    }

    /**
     * A rule the component fails.
     *
     * @param label   the rule's name, or its ID where no name is known
     * @param comment the first line of the rule state's comment; null without one
     * @param details its further lines
     * @param since   when the state last changed; null where the governance data does not say
     * @param link    the rule's page on the governance site
     */
    public record FailingRule(String ruleId, String label, String comment, List<String> details,
                              OffsetDateTime since, String link) {
        public FailingRule {
            details = List.copyOf(details);
        }
    }
}
