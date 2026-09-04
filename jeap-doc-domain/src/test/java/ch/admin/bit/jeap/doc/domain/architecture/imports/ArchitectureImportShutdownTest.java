package ch.admin.bit.jeap.doc.domain.architecture.imports;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four things about it that are silent when they are wrong.
 */
class ArchitectureImportShutdownTest {

    private final ArchitectureImportShutdown shutdown = new ArchitectureImportShutdown();

    /**
     * The phase is a number whose wrongness is silent: at the executors' phase or below, the stop of the
     * executor the imports queue on goes first and waits the whole phase timeout for a run that cannot finish
     * in it - which is exactly what this exists to avoid.
     */
    @Test
    void phase_isAboveTheExecutorsOwn() {
        assertThat(shutdown.getPhase()).isGreaterThan(ExecutorConfigurationSupport.DEFAULT_PHASE);
    }

    /**
     * A context that has not started its lifecycle beans yet is not running either. Reading that as stopping
     * would have the catch-up import at startup give up before it had begun.
     */
    @Test
    void isStopping_beforeItHasEvenStarted_thenNothingIsStopping() {
        assertThat(shutdown.isStopping()).isFalse();
        assertThat(shutdown.isRunning()).isFalse();
    }

    /**
     * A context can be stopped and started again - an integration test, an actuator restart, any lifecycle
     * restart. Left set, the flag would have every import of the restarted context give up before its first
     * step and log that this instance is stopping, for ever.
     */
    @Test
    void isStopping_whenTheContextIsStartedAgain_thenNothingIsStoppingAnyMore() {
        shutdown.start();
        shutdown.stop();

        shutdown.start();

        assertThat(shutdown.isRunning()).isTrue();
        assertThat(shutdown.isStopping()).isFalse();
    }

    @Test
    void isStopping_thenItFollowsTheLifecycle() {
        shutdown.start();
        assertThat(shutdown.isRunning()).isTrue();
        assertThat(shutdown.isStopping()).isFalse();

        shutdown.stop();

        assertThat(shutdown.isRunning()).isFalse();
        assertThat(shutdown.isStopping()).isTrue();
    }
}
