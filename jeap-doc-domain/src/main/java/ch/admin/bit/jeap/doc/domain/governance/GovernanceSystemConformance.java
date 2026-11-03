package ch.admin.bit.jeap.doc.domain.governance;

/** A rule's conformance rate within one system - the rule page's breakdown beside its overall rate. */
public record GovernanceSystemConformance(String system, Integer rate) {
}
