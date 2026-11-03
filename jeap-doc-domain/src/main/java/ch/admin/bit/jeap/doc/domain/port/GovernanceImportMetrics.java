package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportOutcome;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;

import java.time.Duration;

/**
 * What the governance import reports about itself.
 * <p>
 * A port of its own rather than two methods on the build metrics: an import runs on its own schedule and
 * outside any build, and what an operator alarms on is how long ago one last succeeded.
 */
public interface GovernanceImportMetrics {

    /** Reports nothing, for a test that is not about the meters. */
    GovernanceImportMetrics NONE = new GovernanceImportMetrics() {

        @Override
        public void imported(GovernanceResourceKind kind, GovernanceImportOutcome outcome, Duration duration,
                             int items) {
            // nothing to report
        }

        @Override
        public void items(GovernanceResourceKind kind, String outcome, int count) {
            // nothing to report
        }
    };

    /** One run of one kind: how long it took, what it did and how much is stored afterwards. */
    void imported(GovernanceResourceKind kind, GovernanceImportOutcome outcome, Duration duration, int items);

    /**
     * How many resources a run stored, confirmed unchanged, removed or could not replicate.
     *
     * @param outcome {@code stored}, {@code unchanged}, {@code removed} or {@code skipped}
     */
    void items(GovernanceResourceKind kind, String outcome, int count);
}
