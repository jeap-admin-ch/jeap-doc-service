package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.SiteBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Gives up on the build this instance is running, while the service is stopping and while it still can.
 * <p>
 * <b>Where this runs is the whole point.</b> A context closes in four steps: it publishes its closed event, it
 * stops the lifecycle beans, it destroys the singletons, and it closes the bean factory. The connection pool is
 * destroyed in the third; a build interrupted there is writing its terminal state against a pool that may
 * already be closing, which is exactly the state this exists to avoid. Stopping in the second step means the
 * database is still there, and the thread running the build has not been interrupted yet.
 * <p>
 * <b>None of this is what makes the recovery correct.</b> An instance that is killed writes nothing at all, and
 * the build it lost is recovered from the row it left behind, by whichever instance polls after its lock
 * expires. What this buys is that the ordinary case - a deployment - costs a second instead of a lock lease,
 * and that {@code jeap.doc.build.abandoned} keeps meaning <i>something went wrong</i> rather than
 * <i>somebody deployed</i>. It is allowed to fail; it is not allowed to hang.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentationBuildShutdown implements SmartLifecycle {

    /**
     * Above the task scheduler's phase, because stopping runs in descending order of phase. At the scheduler's
     * phase or below, its own stop would go first - and it waits for the running task, bounded by
     * {@code spring.lifecycle.timeout-per-shutdown-phase}, so the entire budget would be spent waiting for the
     * build that this is trying to cut short.
     */
    static final int PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE + 1024;

    private final DocumentationBuildRunner runner;
    private final SiteBuilder siteBuilder;
    private final BuildProperties properties;

    private volatile boolean running;

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops accepting builds, destroys the site generator, and waits for the build thread to finish its
     * bookkeeping - for at most {@code jeap.doc.build.shutdown-timeout}.
     * <p>
     * The wait is a hard bound rather than a target. Overrunning the phase timeout would let the context destroy
     * its beans while the build thread is still writing, which is the one way this design could recreate the
     * problem it removes.
     */
    @Override
    public void stop() {
        running = false;
        if (!runner.stopAcceptingBuilds()) {
            return;
        }
        Duration budget = properties.getShutdownTimeout();
        long startedAt = System.nanoTime();
        try {
            // Destroying the generator rather than interrupting the thread: the thread has a terminal state to
            // write, a lock to give back and a request to put back, and it needs the database for all three.
            siteBuilder.abortCurrentBuild();
            if (runner.awaitIdle(budget)) {
                log.info("No documentation build is left running on this instance; it stopped cleanly in {}.",
                        Duration.ofNanos(System.nanoTime() - startedAt));
            } else {
                log.warn("A documentation build was still finishing after {}; this instance stops without "
                         + "waiting further. Its site is built again by the next instance to poll, once the "
                         + "lock this one holds has run out.", budget);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Stopping the documentation build was interrupted; the build is recovered from its record.");
        } catch (RuntimeException e) {
            // Never out of a stop: an exception here would abandon the phase and take the rest of the shutdown
            // with it, including the beans that still have something to close.
            log.warn("The documentation build of this instance could not be stopped cleanly.", e);
        }
    }
}
