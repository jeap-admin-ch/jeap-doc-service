package ch.admin.bit.jeap.doc.domain.governance;

import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the job guarantees: one instance imports at a time, and a build is asked for only when the import
 * changed something - see {@code ArchitectureImportJobTest} for the same guarantees over the heavier chain.
 */
class GovernanceImportJobTest {

    private GovernanceImport governanceImport;
    private DocumentationBuildTrigger trigger;
    private RecordingLocks locks;

    @BeforeEach
    void setUp() {
        governanceImport = mock(GovernanceImport.class);
        trigger = mock(DocumentationBuildTrigger.class);
        locks = new RecordingLocks(true);
    }

    @Test
    void importGovernanceData_thenItRunsUnderItsOwnLock() {
        assertThat(jobUnderTest().importGovernanceData()).isTrue();

        assertThat(locks.taken).containsExactly("governanceImport");
    }

    @Test
    void importGovernanceData_whenAnotherInstanceHoldsTheLock_thenNothingRunsAndNoBuildIsAskedFor() {
        locks = new RecordingLocks(false);

        assertThat(jobUnderTest().importGovernanceData()).isFalse();

        verifyNoInteractions(governanceImport);
        verifyNoInteractions(trigger);
    }

    @Test
    void importGovernanceData_whenTheImportIsComplete_thenTheGovernanceSiteIsAskedFor() {
        when(governanceImport.run()).thenReturn(true);

        jobUnderTest().importGovernanceData();

        verify(trigger).requestBecauseTheGovernanceWasImported();
    }

    /** A run that left nothing complete has nothing new to publish. */
    @Test
    void importGovernanceData_whenTheImportIsNotComplete_thenNoBuildIsAskedFor() {
        when(governanceImport.run()).thenReturn(false);

        jobUnderTest().importGovernanceData();

        verifyNoInteractions(trigger);
    }

    /** What the import did is done. A trigger that throws costs an hour - the next import asks again. */
    @Test
    void importGovernanceData_whenAskingFails_thenNoExceptionEscapes() {
        when(governanceImport.run()).thenReturn(true);
        when(trigger.requestBecauseTheGovernanceWasImported())
                .thenThrow(new IllegalStateException("the database went away"));

        jobUnderTest().importGovernanceData();
    }

    private GovernanceImportJob jobUnderTest() {
        return new GovernanceImportJob(governanceImport, locks, trigger);
    }

    private static final class RecordingLocks implements ExclusiveWork {

        private final boolean granting;
        private final List<String> taken = new ArrayList<>();

        private RecordingLocks(boolean granting) {
            this.granting = granting;
        }

        @Override
        public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
            taken.add(name);
            return granting ? Optional.ofNullable(work.get()) : Optional.empty();
        }
    }
}
