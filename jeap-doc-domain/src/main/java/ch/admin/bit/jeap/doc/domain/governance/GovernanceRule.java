package ch.admin.bit.jeap.doc.domain.governance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * One rule, parsed from the stored resource: its explanation and, for an active rule, its weight, violation
 * delay, parameters and conformance. An inactive rule carries only its name, rule ID and explanation - every
 * other field is null or empty, and the rule page shows nothing beyond them.
 *
 * @param explanation           the rule's explanation, as delivered - null for a custom rule without one
 * @param weight                null for an inactive rule
 * @param share                 the rule's share of the total weight, in percent - null for an inactive rule
 * @param violationDelay        how long a violation is tolerated before it counts against the score - null for
 *                              an inactive rule, or an active one configured with none
 * @param gracePeriodConfigured whether this rule has a violation grace period at all; gates whether the rule
 *                              page shows the grace period section
 * @param conformanceRate       null for an inactive rule, or before the first completed scoring run
 * @param trend                 {@code UP}, {@code DOWN}, {@code STABLE} or {@code NO_DATA}, as the Governance
 *                              Service names it
 */
public record GovernanceRule(String ruleId, String name, boolean active, String explanation, Integer weight,
                             BigDecimal share, Duration violationDelay, boolean gracePeriodConfigured,
                             List<GovernanceRuleParameter> parameters, List<GovernanceRuleExemption> exemptions,
                             Integer conformanceRate, String trend, List<GovernanceSystemConformance> systems,
                             List<GovernanceNonCompliantComponent> nonCompliantComponents,
                             List<GovernanceGracePeriodComponent> gracePeriodComponents) {

    public GovernanceRule {
        parameters = List.copyOf(parameters);
        exemptions = List.copyOf(exemptions);
        systems = List.copyOf(systems);
        nonCompliantComponents = List.copyOf(nonCompliantComponents);
        gracePeriodComponents = List.copyOf(gracePeriodComponents);
    }
}
