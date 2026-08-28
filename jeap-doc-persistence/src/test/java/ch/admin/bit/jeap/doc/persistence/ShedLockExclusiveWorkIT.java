package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the domain asks for when it says <i>only one instance may do this</i>, against the real table.
 * <p>
 * The domain's own tests use a stand-in that simply does the work; that only one instance does it is decided
 * here, in SQL, and is what this asserts.
 */
class ShedLockExclusiveWorkIT extends PostgresTestContainerBase {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private ExclusiveWork exclusiveWork;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void underLock_thenTheWorkRunsAndWhatItReturnsIsReported() {
        Optional<String> result = exclusiveWork.underLock("a-job", LEASE, () -> "done");

        assertThat(result).contains("done");
    }

    /**
     * The distinction the whole port rests on: nothing to report is not the same as another instance holding
     * the lock, and a caller that confused the two would drop the request it should have left standing.
     */
    @Test
    void underLock_whenAnotherInstanceHoldsIt_thenEmptyRatherThanTheWorkRunningTwice() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        Thread holder = new Thread(() -> exclusiveWork.underLock("a-contested-job", LEASE, () -> {
            ran.add("first");
            inside.countDown();
            await(release);
            return "first";
        }));
        holder.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        Optional<String> refused = exclusiveWork.underLock("a-contested-job", LEASE, () -> {
            ran.add("second");
            return "second";
        });

        assertThat(refused).describedAs("the second attempt should not have run the work").isEmpty();
        assertThat(ran).containsExactly("first");

        release.countDown();
        holder.join(10_000);
    }

    /**
     * Released the moment the work is over rather than held for a minimum: a documentation build that finishes
     * in twenty seconds has to be followed immediately by the run the triggers arriving during it asked for.
     */
    @Test
    void underLock_whenTheWorkIsOver_thenTheLockIsFreeAtOnce() {
        exclusiveWork.underLock("a-quick-job", LEASE, () -> "done");

        assertThat(exclusiveWork.underLock("a-quick-job", LEASE, () -> "again")).contains("again");
    }

    @Test
    void underLock_thenTheLockIsInTheTableUnderTheGivenName() {
        exclusiveWork.underLock("a-named-job", LEASE, () -> "done");

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = ?", Integer.class, "a-named-job");
        assertThat(rows).isOne();
    }

    /**
     * What the work throws is what the caller sees - a lock is not a place for a failure to disappear into.
     */
    @Test
    void underLock_whenTheWorkFails_thenTheFailureReachesTheCallerAndTheLockIsReleased() {
        assertThatThrownBy(() -> exclusiveWork.underLock("a-failing-job", LEASE, () -> {
            throw new IllegalStateException("the work went wrong");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the work went wrong");

        assertThat(exclusiveWork.underLock("a-failing-job", LEASE, () -> "afterwards")).contains("afterwards");
    }

    @Test
    void underLock_whenTheWorkHasNothingToReport_thenItStillSaysWhetherItRan() {
        assertThat(exclusiveWork.underLock("a-job-without-a-result", LEASE, () -> {
            // Nothing to report; the boolean is the answer.
        })).isTrue();
    }

    /**
     * A thread that already holds a lock may take it again. It is ShedLock's behaviour rather than a decision
     * made here, and it is written down because it is surprising: only a <b>second thread</b> - which is what a
     * second instance looks like - is refused. Nothing in the doc service takes a lock inside a lock.
     */
    @Test
    void underLock_whenTheSameThreadTakesItAgain_thenItIsGranted() {
        Optional<String> outer = exclusiveWork.underLock("a-reentrant-job", LEASE, () ->
                exclusiveWork.underLock("a-reentrant-job", LEASE, () -> "inner").orElse("refused"));

        assertThat(outer).contains("inner");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The holder was never released.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
