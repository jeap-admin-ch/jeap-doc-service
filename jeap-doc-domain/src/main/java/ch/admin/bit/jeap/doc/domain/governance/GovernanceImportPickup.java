package ch.admin.bit.jeap.doc.domain.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the governance import of this instance: the one the schedule asks for, and the one the webhook asks for.
 * <p>
 * The same shape as {@link ch.admin.bit.jeap.doc.domain.DocumentationBuildPickup}: <b>an import already
 * running absorbs a further ask rather than starting beside it</b> - the requests are merged, as a build's
 * are. A webhook call that arrives while a run is already
 * fetching the Governance Service must not be dropped - the scoring it reports may not be in the data that run
 * started reading - so it queues the next run instead.
 * <p>
 * <b>The same holds across instances.</b> When the webhook reaches an instance while another one holds the
 * import's lock, that other run may also have read the indexes too early. So an import asked for by the webhook
 * that finds the lock taken is tried again after 30 seconds, until it runs. A scheduled import that
 * finds the lock taken is not: every instance's schedule fires at once, and one of them importing is enough.
 * <p>
 * <b>The import runs here and not on the scheduler's or the request's thread.</b> The schedule's thread is
 * shared with every other job of this service, and a webhook call answers {@code 202} before the import has
 * even started - both of which need the work handed off rather than run in place.
 */
@Slf4j
@Component
public class GovernanceImportPickup implements SmartLifecycle {

    /** Long enough for another instance's run to finish, which is a handful of small JSON resources. */
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final GovernanceImportJob job;
    private final Duration retryDelay;

    /** One thread, so that a burst of triggers is one run and never a run each. */
    private final ScheduledThreadPoolExecutor imports = newExecutor();

    /** Whether a run is already waiting to start, which is what makes a burst of triggers one run. */
    private final AtomicBoolean queued = new AtomicBoolean();

    /** Whether the webhook asked for an import that has not run yet on any instance this one knows of. */
    private final AtomicBoolean notified = new AtomicBoolean();

    /** Whether a retry is already waiting, so that several webhook calls wait for one retry and not one each. */
    private final AtomicBoolean retryPending = new AtomicBoolean();

    private volatile boolean running;

    private volatile boolean stopped;

    @Autowired
    public GovernanceImportPickup(GovernanceImportJob job) {
        this(job, RETRY_DELAY);
    }

    GovernanceImportPickup(GovernanceImportJob job, Duration retryDelay) {
        this.job = job;
        this.retryDelay = retryDelay;
    }

    /** The schedule: what an instance that nothing told does. */
    void poll() {
        request();
    }

    /**
     * Looks for work now, because the webhook has just reported a completed scoring run. Returns immediately -
     * the caller answers {@code 202} before the import that follows has even started.
     * <p>
     * The web server takes calls before this starts and after it stops. A call before the start is remembered
     * and runs when it starts; a call after the stop cannot run on this instance any more.
     *
     * @return whether the import will run - false only once this instance is stopping
     */
    public boolean whenNotifiedByWebhook() {
        notified.set(true);
        return !stopped && (!running || request());
    }

    /**
     * Named apart from {@link #start()}, the lifecycle method this class also has to implement.
     *
     * @return whether an import is queued, a new one or one already waiting
     */
    private boolean request() {
        if (!running) {
            return false;
        }
        if (!queued.compareAndSet(false, true)) {
            // One is already waiting, and a run reads the current indexes for itself: a second would read
            // the same thing again before anything could have changed.
            return true;
        }
        try {
            imports.execute(this::runOnce);
            return true;
        } catch (RejectedExecutionException e) {
            queued.set(false);
            log.debug("This instance is stopping and starts no further governance import.");
            return false;
        }
    }

    /**
     * Clears the flag before the run rather than after it. A webhook call that arrives while a run is already
     * fetching the Governance Service has to be able to queue the next one, because that run may already have
     * read the index it reported changed.
     */
    private void runOnce() {
        queued.set(false);
        boolean servesTheWebhook = notified.getAndSet(false);
        boolean ran;
        try {
            ran = job.importGovernanceData();
        } catch (RuntimeException e) {
            // Nothing above this catches: a run that escapes here would take the pickup thread's next task
            // with it, and GovernanceImportJob and GovernanceImport are themselves meant to report what went
            // wrong rather than throw - so reaching here is a defect in one of them.
            log.error("A governance import ended in a way it did not cover itself. The next schedule tries "
                      + "again.", e);
            return;
        }
        if (!ran && servesTheWebhook) {
            retryLater();
        }
    }

    private void retryLater() {
        notified.set(true);
        if (!running || !retryPending.compareAndSet(false, true)) {
            return;
        }
        log.debug("Another instance holds the governance import's lock. The import the webhook asked for is "
                  + "tried again in {}.", retryDelay);
        try {
            imports.schedule(this::retry, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            retryPending.set(false);
            log.debug("This instance is stopping and starts no further governance import.");
        }
    }

    private void retry() {
        retryPending.set(false);
        request();
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "governance-import-pickup");
            thread.setDaemon(true);
            return thread;
        });
        // A stopping instance starts no further import, and a retry still waiting is one.
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override
    public void start() {
        running = true;
        if (notified.get()) {
            request();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops queueing imports, without waiting for the one that may be running - it is a handful of small JSON
     * resources, not a build, so there is no bookkeeping to wait for the way a build's shutdown waits.
     */
    @Override
    public void stop() {
        stopped = true;
        running = false;
        imports.shutdown();
    }
}
