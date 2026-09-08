package ch.admin.bit.jeap.doc.domain.architecture.imports;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What an ask for an import becomes.
 * <p>
 * The two things that matter are collapsing and reporting: ten asks for one environment are one import, the
 * way ten build triggers are one build, and an ask the queue refused is answered rather than dropped.
 */
class ArchitectureImportQueueTest {

    private static final String ENVIRONMENT = "dev";

    /** An import of an environment already queued would run the same fetch again behind itself. */
    @Test
    void submit_whenAnImportOfThatEnvironmentIsAlreadyWaiting_thenTheSecondAskJoinsIt() {
        List<Runnable> queued = new ArrayList<>();
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, queued::add);

        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);
        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.ALREADY_ASKED_FOR);
        assertThat(queue.submit("ref")).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);

        assertThat(queued).describedAs("one import per environment").hasSize(2);
    }

    /**
     * <b>An ask that arrives while the import is reading is not lost.</b> It is the case the endpoint exists
     * for - somebody corrected something in the architecture repository - and the run in flight may have read
     * the resource already, so joining it would answer 202 for a fetch that predates the correction.
     * <p>
     * Once more and not once per ask: what is remembered is a flag, so the three asks below are one further
     * import.
     */
    @Test
    void submit_whenTheImportIsAlreadyRunning_thenItRunsOnceMoreAfterwards() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        List<Runnable> queued = new ArrayList<>();
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, queued::add);
        // Asked for three times while the first run is reading, and only while that one is: what the second
        // run does with an ask is the same question over again.
        AtomicBoolean firstRun = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (firstRun.compareAndSet(true, false)) {
                assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);
                assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);
                assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);
            }
            return null;
        }).when(job).importEnvironment(ENVIRONMENT);

        queue.submit(ENVIRONMENT);
        queued.removeFirst().run();

        assertThat(queued).describedAs("one further import for the three asks, not three").hasSize(1);
        queued.removeFirst().run();
        assertThat(queued).describedAs("and nothing after it, because nobody asked again").isEmpty();
        verify(job, org.mockito.Mockito.times(2)).importEnvironment(ENVIRONMENT);
    }

    /** Once the import has run, the environment can be asked for again - it is asked for hourly. */
    @Test
    void submit_whenTheImportHasRun_thenTheEnvironmentCanBeAskedForAgain() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, new SyncTaskExecutor());

        queue.submit(ENVIRONMENT);
        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);

        verify(job, org.mockito.Mockito.times(2)).importEnvironment(ENVIRONMENT);
    }

    /** And after one that threw: an environment held in the set for ever would never be imported again. */
    @Test
    void submit_whenTheImportThrew_thenTheEnvironmentCanBeAskedForAgain() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("the archrepo said no"))
                .when(job).importEnvironment(ENVIRONMENT);
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, task -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // The executor's own thread would log it; what matters here is what the queue kept.
            }
        });

        queue.submit(ENVIRONMENT);

        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.QUEUED);
    }

    /** A refused ask is reported, so an operator is not told an import is coming when none is. */
    @Test
    void submit_whenTheQueueIsFull_thenItIsRefusedAndNotHeldAsInFlight() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        TaskExecutor full = task -> {
            throw new TaskRejectedException("the queue is full");
        };
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, full);

        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.REFUSED);
        // Not held: the next ask has to be able to reach a queue that has room again.
        assertThat(queue.submit(ENVIRONMENT)).isEqualTo(ArchitectureImportQueue.Outcome.REFUSED);
        verify(job, never()).importEnvironment(ENVIRONMENT);
    }

    @Test
    void submitWhatIsMissing_whenTheQueueIsFull_thenItSaysSo() {
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        ArchitectureImportQueue refusing = new ArchitectureImportQueue(job, task -> {
            throw new TaskRejectedException("the queue is full");
        });

        assertThat(refusing.submitWhatIsMissing()).isFalse();
        assertThat(new ArchitectureImportQueue(job, new SyncTaskExecutor()).submitWhatIsMissing()).isTrue();
        verify(job).importWhatIsMissing();
    }

    /** The import runs on the executor rather than on the thread that asked - an import takes minutes. */
    @Test
    void submit_thenNothingRunsOnTheAskingThread() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ArchitectureImportJob job = mock(ArchitectureImportJob.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ran.countDown();
            return null;
        }).when(job).importEnvironment(ENVIRONMENT);
        List<Runnable> queued = new ArrayList<>();
        ArchitectureImportQueue queue = new ArchitectureImportQueue(job, queued::add);

        queue.submit(ENVIRONMENT);

        verify(job, never()).importEnvironment(ENVIRONMENT);
        queued.getFirst().run();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
