package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Keeps a job to one instance, with ShedLock over the {@code shedlock} table of this database.
 * <p>
 * The lock is timed by the database rather than by the instances, which do not share a clock, and it is
 * <b>kept alive</b> while it is held (see {@link DocPersistenceConfiguration}) - so a lease says how long a lock
 * survives an instance that dies holding it, and not how long the work may take.
 */
@Component
@RequiredArgsConstructor
class ShedLockExclusiveWork implements ExclusiveWork {

    private final LockingTaskExecutor lockingTaskExecutor;

    @Override
    public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
        // The wall clock of this instance, and deliberately not the domain's Clock: the provider is configured
        // usingDbTime(), so when the lock really expires is decided by the database - which is the point, since
        // instances do not share a clock. What is passed here is only ShedLock's note of when it was asked for.
        LockConfiguration lock = new LockConfiguration(Instant.now(), name, lease,
                // Released the moment the work is over. A minimum lease would make the next run wait for a lock
                // rather than for the work - and a documentation build that finishes in twenty seconds has to be
                // followed immediately by the run the triggers arriving during it asked for.
                Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<T> result =
                    lockingTaskExecutor.executeWithLock((LockingTaskExecutor.TaskWithResult<T>) work::get, lock);
            return Optional.ofNullable(result.getResult());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            // The lock provider's signature allows anything; nothing this runs throws a checked exception, so
            // this keeps the port's signature honest rather than handling a case that occurs.
            throw new IllegalStateException("The work '%s' failed unexpectedly.".formatted(name), e);
        }
    }
}
