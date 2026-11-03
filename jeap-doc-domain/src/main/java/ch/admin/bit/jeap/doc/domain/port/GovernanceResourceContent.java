package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceRule;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceRulesIndex;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystem;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceSystemsIndex;

import java.util.Optional;

/**
 * Turns a stored governance resource's bytes into what a page is written from.
 * <p>
 * Nothing here calls the Governance Service or knows a URL - it only knows the shape of the JSON {@link
 * ch.admin.bit.jeap.doc.domain.governance.GovernanceResource#content()} holds, the way {@code
 * ArchitectureArtifactContent} turns a replicated artifact's bytes into what a page is written from.
 */
public interface GovernanceResourceContent {

    /**
     * @return empty where the stored bytes cannot be read as the systems index - a defect to log and leave
     * out of the site rather than fail the whole build over
     */
    Optional<GovernanceSystemsIndex> systemsIndex(byte[] content);

    /** @return empty where the stored bytes cannot be read as a system */
    Optional<GovernanceSystem> system(byte[] content);

    /**
     * @return empty where the stored bytes cannot be read as the rules index - a defect to log and leave out
     * of the site rather than fail the whole build over
     */
    Optional<GovernanceRulesIndex> rulesIndex(byte[] content);

    /** @return empty where the stored bytes cannot be read as a rule */
    Optional<GovernanceRule> rule(byte[] content);
}
