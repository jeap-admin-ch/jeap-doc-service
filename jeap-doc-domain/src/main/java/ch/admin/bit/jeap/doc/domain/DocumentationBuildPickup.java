package ch.admin.bit.jeap.doc.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the build passes of this instance: the one the poll interval asks for, and the one a trigger asks for.
 * <p>
 * <b>A pass runs here rather than on the task scheduler.</b> A pass lasts as long as the builds in it, and the
 * scheduler has one thread for every job of this service - so a pass on it would hold up the architecture
 * import and the nightly clean-up for as long as it ran.
 * <p>
 * <b>A trigger starts a pass at once.</b> The poll interval is the latency an idle instance costs; paying it on
 * an upload is paying it on the one instance that already knows there is work. What the poll interval is still
 * for is the other instances, which nothing told.
 * <p>
 * <b>A wake-up is advisory.</b> One that is lost, refused while stopping or overtaken by another instance costs
 * nothing at all: the request stands and the next poll serves it. Nothing may depend on it having happened.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentationBuildPickup implements SmartLifecycle {

    /**
     * Above the phase the build shutdown stops in, because stopping runs in descending order of phase: once
     * that has begun giving up on the build in flight, no further pass may be queued behind it.
     */
    static final int PHASE = DocumentationBuildShutdown.PHASE + 1;

    private final DocumentationBuildRunner runner;
    private final BuildProperties properties;

    /** One thread, so that a burst of triggers is one pass and never a pass each. */
    private final ExecutorService passes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "documentation-build-pickup");
        thread.setDaemon(true);
        return thread;
    });

    /** Whether a pass is already waiting to start, which is what makes a burst of triggers one pass. */
    private final AtomicBoolean queued = new AtomicBoolean();

    private volatile boolean running;

    /** The poll: what an instance that nothing told does. */
    void poll() {
        start(false);
    }

    /**
     * Looks for work now, because something has just asked for a build. Returns immediately - an upload writes
     * its request on a request thread, and what would follow it here is a Docusaurus build.
     */
    public void whenAskedFor() {
        if (!properties.isPickUpOnTrigger()) {
            return;
        }
        start(true);
    }

    private void start(boolean askedFor) {
        if (!running) {
            return;
        }
        if (!queued.compareAndSet(false, true)) {
            // One is already waiting, and a pass reads what is owed for itself: a second would find nothing.
            return;
        }
        try {
            passes.execute(() -> pass(askedFor));
        } catch (RejectedExecutionException e) {
            queued.set(false);
            log.debug("This instance is stopping and starts no further build pass.");
        }
    }

    /**
     * Clears the flag before the pass rather than after it. A trigger that arrives while a pass is running has
     * to be able to queue the next one, because that pass may already have read what is owed.
     */
    private void pass(boolean askedFor) {
        queued.set(false);
        try {
            if (runner.runOnce() && askedFor) {
                log.debug("A build pass ran because a build had just been asked for.");
            }
        } catch (RuntimeException e) {
            // Nothing above this catches: the pass would otherwise take the pickup thread's next task with it.
            log.error("A build pass ended in a way it did not cover itself. The next poll tries again.", e);
        }
    }

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
     * Stops queueing passes, without waiting for the one that may be running: it is
     * {@link DocumentationBuildShutdown}, one phase below this, that gives up on the build in flight and waits
     * for its bookkeeping. Shutting down rather than interrupting, because an interrupt is what that
     * bookkeeping cannot survive - it needs the database for all three of its writes.
     */
    @Override
    public void stop() {
        running = false;
        passes.shutdown();
    }
}
