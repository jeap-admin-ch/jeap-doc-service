package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A lock table shared by the runners of a test, for the tests about two instances working the same queue.
 * <p>
 * It refuses a lock that is held instead of waiting for it, which is what the ShedLock adapter does and what
 * the runner is written against. That the real one is timed by the database is
 * {@code ShedLockExclusiveWorkIT}'s business.
 */
public class OneAtATimeExclusiveWork implements ExclusiveWork {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    @Override
    public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
        if (!held.add(name)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            held.remove(name);
        }
    }
}
