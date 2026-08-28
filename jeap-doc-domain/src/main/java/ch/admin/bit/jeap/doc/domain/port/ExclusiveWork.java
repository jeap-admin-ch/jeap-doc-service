package ch.admin.bit.jeap.doc.domain.port;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Work that only one instance may be doing at a time, across all of them.
 * <p>
 * A port rather than a locking library in the domain: what the domain says is <i>only one instance may publish
 * this site</i>, and how that is enforced - a row in a table, timed by the database because instances do not
 * share a clock - is the adapter's business.
 * <p>
 * The lease is <b>how long the lock survives an instance that dies holding it</b>, not how long the work may
 * take: an adapter is expected to extend a lease it still holds. A caller therefore chooses it by how long a
 * site may stay blocked after a container is killed, and not by how slow the work is.
 */
public interface ExclusiveWork {

    /**
     * Runs the given work if this instance can take the named lock, and reports what the work returned.
     * <p>
     * An empty result means another instance holds the lock, <b>not</b> that the work returned nothing. The
     * caller is expected to leave whatever asked for the work standing, so that the instance holding the lock
     * serves it - dropping it here would lose the request.
     *
     * @param name  what is being done, so that two different jobs do not wait for each other
     * @param lease how long the lock survives an instance that stops without releasing it
     * @param work  what to do while holding it
     */
    <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work);

    /**
     * The same, for work with nothing to report. Reports whether this instance was the one that ran it.
     */
    default boolean underLock(String name, Duration lease, Runnable work) {
        return underLock(name, lease, () -> {
            work.run();
            return Boolean.TRUE;
        }).isPresent();
    }
}
