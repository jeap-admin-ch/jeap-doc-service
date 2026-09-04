package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;

import java.time.Duration;

/**
 * What the architecture import reports about itself.
 * <p>
 * A port of its own rather than two methods on the build metrics: an import runs on its own schedule and
 * outside any build, and what an operator alarms on is how long ago one last succeeded.
 */
public interface ArchitectureImportMetrics {

    /** Reports nothing, for a test that is not about the meters. */
    ArchitectureImportMetrics NONE = new ArchitectureImportMetrics() {

        @Override
        public void imported(String environment, ArchitectureImportKind kind, ImportOutcome outcome,
                             Duration duration, int items) {
            // nothing to report
        }

        @Override
        public void items(String environment, ArchitectureImportKind kind, String outcome, int count) {
            // nothing to report
        }
    };

    /**
     * One run of one step: how long it took, what it did and how much is stored afterwards.
     */
    void imported(String environment, ArchitectureImportKind kind, ImportOutcome outcome, Duration duration,
                  int items);

    /**
     * How many things a run stored, confirmed unchanged, removed or could not replicate. Against the run count
     * it answers how much the import is costing the architecture repository; {@code skipped} staying above
     * zero is an artifact the architecture repository serves and this service refuses, run after run.
     *
     * @param outcome {@code stored}, {@code unchanged}, {@code removed} or {@code skipped}
     */
    void items(String environment, ArchitectureImportKind kind, String outcome, int count);
}
