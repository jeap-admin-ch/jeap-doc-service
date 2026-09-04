package ch.admin.bit.jeap.doc.domain.architecture.imports;

/**
 * What one run of one import step did, for the state row and the meter.
 * <p>
 * All but one of them reach the row. {@link #NOT_CONFIGURED} is returned before a run happens at all, so
 * nothing is recorded for it - an environment with no architecture repository has no row to write.
 */
public enum ImportOutcome {

    /** Something changed and was written. */
    REPLACED,

    /** Nothing had changed, so nothing was written. The common case, and the cheap one. */
    UNCHANGED,

    /**
     * The step ran out of its deadline, or the instance running it stopped.
     * <p>
     * What that leaves behind differs by kind, and the row does not say which: an artifact step keeps what it
     * had already stored, while the <b>model</b> is all or nothing and writes nothing at all - the landscape
     * stored before the run goes on being generated from. Either way it is not a success, so the staleness
     * gauge goes on rising until a run gets through its whole list.
     */
    PARTIAL,

    /** The upstream could not be read. Nothing stored was touched. */
    FAILED,

    /** No architecture repository is configured for this environment. */
    NOT_CONFIGURED
}
