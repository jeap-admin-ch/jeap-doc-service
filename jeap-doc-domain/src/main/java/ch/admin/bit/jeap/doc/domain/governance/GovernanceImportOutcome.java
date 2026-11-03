package ch.admin.bit.jeap.doc.domain.governance;

/**
 * What one run of the governance import did, for the state row and the meter.
 */
public enum GovernanceImportOutcome {

    /** At least one resource was stored or removed. */
    CHANGED,

    /** Nothing had changed, so nothing was written. The common case, and the cheap one. */
    UNCHANGED,

    /** The Governance Service could not be read. Nothing stored was touched. */
    FAILED,

    /**
     * The index was read, but an entry in it could not be replicated. Nothing of the run was stored, and it
     * does not count as a success.
     */
    PARTIAL,

    /** No Governance Service upstream is configured. */
    NOT_CONFIGURED
}
