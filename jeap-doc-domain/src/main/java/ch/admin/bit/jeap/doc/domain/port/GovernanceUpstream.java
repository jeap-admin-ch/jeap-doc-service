package ch.admin.bit.jeap.doc.domain.port;

import java.util.Optional;

/**
 * The Governance Service's {@code /docs-api}, as the governance import reads it.
 * <p>
 * Every request here is conditional, and that is the whole point: the two indexes carry the time of the last
 * completed scoring run, so they change at every run, but a system or rule resource moves far less often - and
 * is fetched only where its own entity tag did.
 */
public interface GovernanceUpstream {

    /** Whether a Governance Service is configured for this instance at all. */
    boolean isConfigured();

    /**
     * @param knownIndexEtag the tag of the index as it was last seen, or null to ask unconditionally
     * @return empty when the upstream answered "not modified"
     * @throws GovernanceUnavailableException when the Governance Service could not be read
     */
    Optional<GovernanceIndexFetch> systemsIndex(String knownIndexEtag);

    /**
     * @param knownIndexEtag the tag of the index as it was last seen, or null to ask unconditionally
     * @return empty when the upstream answered "not modified"
     * @throws GovernanceUnavailableException when the Governance Service could not be read
     */
    Optional<GovernanceIndexFetch> rulesIndex(String knownIndexEtag);

    /**
     * @param knownEtag the tag of the copy already stored, or null when there is none
     * @throws GovernanceUnavailableException when the Governance Service could not be read
     */
    GovernanceResourceFetch system(String systemName, String knownEtag);

    /**
     * @param knownEtag the tag of the copy already stored, or null when there is none
     * @throws GovernanceUnavailableException when the Governance Service could not be read
     */
    GovernanceResourceFetch rule(String ruleId, String knownEtag);
}
