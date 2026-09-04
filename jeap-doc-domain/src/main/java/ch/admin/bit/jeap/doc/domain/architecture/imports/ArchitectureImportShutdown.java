package ch.admin.bit.jeap.doc.domain.architecture.imports;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.stereotype.Component;

/**
 * Tells the architecture import that this instance is stopping, early enough for it to stop by itself.
 * <p>
 * <b>Why it exists.</b> An import of one environment takes minutes and a deployment interrupts one every time.
 * Without this, the shutdown ran its course instead: the import executor's own stop waited the whole phase
 * timeout for a run that could not finish in it, the context then interrupted the thread, the request in flight
 * failed as <i>the architecture repository could not be reached</i>, and the steps after it failed one after
 * another against a thread whose interrupt flag was still set - four lines of {@code WARN} and {@code ERROR} per
 * deployment, none of which anybody had to act on. Asked between two requests instead, an import stops in about
 * the time one request takes, and there is nothing left to interrupt.
 * <p>
 * <b>The phase is the whole point</b>, as it is for {@link ch.admin.bit.jeap.doc.domain.DocumentationBuildShutdown}:
 * lifecycle beans stop in descending order of phase, so this has to stop <i>above</i> the executors - at their
 * phase or below, the executor's own stop would already have spent the budget waiting.
 * <p>
 * Nothing here is what makes the import correct. An instance that is killed writes nothing, and what it did not
 * import is imported by the next schedule from the state row it left behind. What this buys is a shutdown that
 * costs a second, and a log in which a failed import means something.
 */
@Slf4j
@Component
public class ArchitectureImportShutdown implements SmartLifecycle {

    /** Above the executors, so that this runs before the one the imports queue on stops - see the class comment. */
    static final int PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE + 1024;

    private volatile boolean running;

    /**
     * Set by the stop rather than derived from {@link #isRunning()}: a context that has not started its
     * lifecycle beans yet is not running either, and reading that as <i>stopping</i> would have an import give
     * up before the first one has begun.
     * <p>
     * Cleared by the start, because a context can be started again - a test, an actuator restart, any
     * {@link SmartLifecycle} restart. Left set, every import of the restarted context would give up before its
     * first step and log that this instance is stopping, for ever.
     */
    private volatile boolean stopping;

    /** Whether this instance has begun to stop, and an import in flight should give up between two requests. */
    public boolean isStopping() {
        return stopping;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        stopping = false;
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        stopping = true;
        running = false;
    }
}
