package ch.admin.bit.jeap.doc.domain.governance;

/**
 * One entry of the systems overview: enough to list a system and link to its page.
 *
 * @param score null where the system has no score yet
 * @param trend {@code UP}, {@code DOWN}, {@code STABLE}, {@code UNKNOWN} or {@code NO_DATA}, as the Governance
 *             Service names it
 */
public record GovernanceSystemSummary(String name, Integer score, String trend) {
}
