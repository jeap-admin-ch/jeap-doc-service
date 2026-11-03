package ch.admin.bit.jeap.doc.domain.governance;

/**
 * The four resources a governance import reads, and stores, from the Governance Service's {@code /docs-api}.
 * <p>
 * The two indexes are stored like the two resources they list: {@link #key()} is null for them, there is
 * exactly one row of each, and they carry the raw body a page later reads the scoring time and trend period
 * from. {@link #SYSTEM} and {@link #RULE} carry a key per stored row - the system name or rule ID the index
 * named it under.
 */
public enum GovernanceResourceKind {

    SYSTEMS_INDEX,
    RULES_INDEX,
    SYSTEM,
    RULE
}
