package ch.admin.bit.jeap.doc.domain.governance;

import java.math.BigDecimal;

/**
 * One entry of the rules overview: enough to list a rule and link to its page. An inactive rule carries no
 * weight, share, conformance rate or trend.
 *
 * @param weight          null for an inactive rule
 * @param share           the rule's share of the total weight, in percent - null for an inactive rule
 * @param conformanceRate null for an inactive rule, or before the first completed scoring run
 * @param trend           {@code UP}, {@code DOWN}, {@code STABLE} or {@code NO_DATA}, as the Governance Service
 *                        names it
 * @param exemptionCount  null for an inactive rule
 */
public record GovernanceRuleSummary(String ruleId, String name, boolean active, Integer weight, BigDecimal share,
                                    Integer conformanceRate, String trend, Integer exemptionCount) {
}
