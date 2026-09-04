package ch.admin.bit.jeap.doc.domain.architecture.imports;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor every architecture import runs on - the scheduled ones and the catch-up at startup alike.
 * <p>
 * <b>One thread, and a queue.</b> An import of one environment takes minutes, and the cron fires for every
 * environment at the same minute. Run inline on the scheduler, four of them held every scheduler thread and the
 * build poll waited for the last of them; run in parallel, they would load four architecture repositories and
 * this JVM's heap at once, which nothing is sized for. So they queue, and run one after the other - which is
 * what happened before, only now without holding a scheduler thread while they do.
 * <p>
 * The queue is bounded and a cron that finds it full is dropped with a warning rather than queued behind an
 * import that is already late: the next quarter hour comes round anyway, and an unbounded queue would only
 * hide a repository that has become too slow to import at all.
 */
@Slf4j
public final class ArchitectureImportExecutor {

    /** More than the environments an instance could reasonably have, so a full queue means something is wrong. */
    static final int QUEUE_CAPACITY = 16;

    static final String THREAD_PREFIX = "architecture-import-";

    private ArchitectureImportExecutor() {
    }

    /**
     * Configured but not initialized: as a bean, Spring initializes it, and a test calls
     * {@link ThreadPoolTaskExecutor#initialize()} itself.
     */
    public static ThreadPoolTaskExecutor create() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(THREAD_PREFIX);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setDaemon(true);
        // Not waited for: an import that is cut short by a shutdown is abandoned, and the stored landscape
        // serves on. Waiting minutes for it would only spend the shutdown budget the build needs.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler((task, pool) -> log.warn(
                "An architecture import was not started: {} imports are already waiting on the one import "
                + "thread. It is dropped rather than queued further; the next schedule imports what this one "
                + "did not.", pool.getQueue().size()));
        return executor;
    }
}
