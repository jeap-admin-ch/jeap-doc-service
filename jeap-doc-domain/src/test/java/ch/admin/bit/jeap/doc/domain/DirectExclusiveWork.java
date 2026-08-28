package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Work that is simply done, for the tests of what the work does. That only one instance does it is the
 * adapter's job and is tested there.
 */
public class DirectExclusiveWork implements ExclusiveWork {

    @Override
    public <T> Optional<T> underLock(String name, Duration lease, Supplier<T> work) {
        return Optional.ofNullable(work.get());
    }
}
