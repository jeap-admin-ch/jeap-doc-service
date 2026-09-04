package ch.admin.bit.jeap.doc.persistence;

import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * A documentation build holds its lock for far longer than the lock is leased for, which only works because
     * the provider extends it in the background - so the wiring being lost would show up as two instances
     * building one site, half an hour into a deployment, and nowhere else.
     */
    @Test
    void lockProvider_isKeptAlive() {
        assertThat(lockProvider).isInstanceOf(KeepAliveLockProvider.class);
    }

    /**
     * What the keep-alive does every half lease, against the real table: it is our schema the extension writes
     * to, and a column it could not update would leave the build running without a lock.
     */
    @Test
    void extend_thenTheLeaseMovesOutInTheDatabase() {
        String name = "a-long-running-job";
        LockConfiguration configuration = new LockConfiguration(Instant.now(), name,
                Duration.ofMinutes(1), Duration.ZERO);

        SimpleLock held = extensibleProvider().lock(configuration).orElseThrow();
        Instant beforeExtension = lockUntilOf(name);

        SimpleLock extended = held.extend(Duration.ofMinutes(10), Duration.ZERO).orElseThrow();

        assertThat(lockUntilOf(name)).isAfter(beforeExtension);
        assertThat(extensibleProvider().lock(configuration)).isEmpty();
        extended.unlock();
    }

    /**
     * The other half of the same fact: once nothing extends it any more - the instance holding it was killed -
     * the lock is free again within its lease, and not within the longest a build could have taken.
     */
    @Test
    void lock_whenNothingExtendsItAnyMore_thenItIsFreeAfterItsLease() {
        Duration lease = Duration.ofMillis(500);
        LockConfiguration configuration = new LockConfiguration(Instant.now(), "a-job-of-an-instance-that-died",
                lease, Duration.ZERO);

        // Through the underlying provider, so that nothing keeps it alive: this is the lock row a killed
        // instance leaves behind.
        extensibleProvider().lock(configuration).orElseThrow();
        assertThat(extensibleProvider().lock(configuration)).isEmpty();

        // Waited for as a condition rather than for a duration: the lease is timed by the database's clock,
        // which is not this JVM's.
        SimpleLock afterTheLease = awaitLock(configuration, Duration.ofSeconds(20));

        afterTheLease.unlock();
    }

    /**
     * Takes the lock as soon as it can be taken, or fails the test. Polling a real condition instead of sleeping
     * for a guessed duration is what keeps this from being slow on a fast machine and flaky on a loaded one.
     */
    private SimpleLock awaitLock(LockConfiguration configuration, Duration atMost) {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SimpleLock> taken = extensibleProvider().lock(configuration);
            if (taken.isPresent()) {
                return taken.get();
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("The lock was still held " + atMost + " after its lease ran out.");
    }

    /**
     * The case the wiring exists for: work that runs longer than its lease keeps its lock, because the
     * keep-alive extends it from a thread of its own while the work occupies a scheduler thread. When the
     * keep-alive executor doubled as the scheduler, the work and the extension shared one thread, and a lock
     * could not be extended while the work it protected was running.
     */
    @Test
    void lock_whenTheWorkOutlivesTheLease_thenTheKeepAliveHoldsItFromItsOwnThread() throws Exception {
        String name = "a-job-that-outlives-its-lease";
        Duration lease = Duration.ofSeconds(30);
        LockConfiguration configuration = new LockConfiguration(Instant.now(), name, lease, Duration.ZERO);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.initialize();
        try {
            Future<Instant> extendedTo = scheduler.submit(() -> {
                assertThat(Thread.currentThread().getName()).startsWith("scheduling-");
                SimpleLock held = lockProvider.lock(configuration).orElseThrow();
                try {
                    Instant leasedUntil = lockUntilOf(name);
                    // The keep-alive extends at half the lease; this thread is busy the whole time, so only
                    // another thread can be doing it.
                    Instant moved = awaitLockUntilAfter(name, leasedUntil, lease);
                    assertThat(extensibleProvider().lock(configuration))
                            .as("the lock is still held while its work runs")
                            .isEmpty();
                    return moved;
                } finally {
                    held.unlock();
                }
            });
            assertThat(extendedTo.get(lease.toSeconds() + 10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            scheduler.shutdown();
        }
    }

    private Instant awaitLockUntilAfter(String name, Instant leasedUntil, Duration atMost) {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            Instant lockUntil = lockUntilOf(name);
            if (lockUntil.isAfter(leasedUntil)) {
                return lockUntil;
            }
            LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
        }
        throw new AssertionError("The lock was not extended within " + atMost + ".");
    }

    /**
     * The keep-alive refuses a lease it could not extend often enough to be useful. It is why
     * {@code jeap.doc.build.lock-lease} is checked while the service starts rather than at the first build.
     */
    @Test
    void lock_whenTheLeaseIsShorterThanTheKeepAliveAccepts_thenItIsRefused() {
        LockConfiguration tooShort = new LockConfiguration(Instant.now(), "a-job-with-too-short-a-lease",
                Duration.ofSeconds(5), Duration.ZERO);

        assertThatThrownBy(() -> lockProvider.lock(tooShort)).isInstanceOf(IllegalArgumentException.class);
    }

    private ExtensibleLockProvider extensibleProvider() {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }

    private Instant lockUntilOf(String name) {
        return jdbcTemplate.queryForObject("select lock_until from shedlock where name = ?", Instant.class, name);
    }
}
