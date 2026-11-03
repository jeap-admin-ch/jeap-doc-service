package ch.admin.bit.jeap.doc.web.api.governance;

/**
 * The paths the governance webhook is served at.
 * <p>
 * Its own root, and not below {@code /api/sites}: the call reports that a scoring run completed, which is not
 * a request to publish anything - whether the governance site is rebuilt is decided by what the import that
 * follows finds changed.
 */
final class GovernanceApiPaths {

    /** Where the Governance Service calls after every completed scoring run. */
    static final String WEBHOOK = "/api/governance/webhook";

    private GovernanceApiPaths() {
    }
}
