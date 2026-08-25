package ch.admin.bit.jeap.doc.persistence;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled jobs of the doc service run on one instance at a time, which rests on a table this repository
 * creates and a provider that expects it to look exactly so.
 * <p>
 * Nothing else would notice a wrong column: the lock is taken at night, on one instance, and a provider that
 * cannot write its table fails there and not here. So it is taken here, against the real database.
 */
class LockProviderIT extends PostgresTestContainerBase {

    @Autowired
    private LockProvider lockProvider;

    @Test
    void lock_whenItIsHeld_thenNobodyElseGetsIt() {
        LockConfiguration configuration = new LockConfiguration(Instant.now(), "a-job-of-the-doc-service",
                Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> held = lockProvider.lock(configuration);
        Optional<SimpleLock> refused = lockProvider.lock(configuration);

        assertThat(held).isPresent();
        assertThat(refused).isEmpty();

        held.orElseThrow().unlock();
        Optional<SimpleLock> afterwards = lockProvider.lock(configuration);
        assertThat(afterwards).isPresent();
        afterwards.orElseThrow().unlock();
    }
}
