package ch.admin.bit.jeap.doc.domain.architecture.imports;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a step is told about why it has to stop - which decides whether the line it logs is something to act on.
 */
class DeadlineTest {

    @Test
    void hasExpired_whenThereIsTimeLeft_thenTheRunGoesOn() {
        Deadline deadline = Deadline.of(Duration.ofMinutes(5), () -> false);

        assertThat(deadline.hasExpired()).isFalse();
        assertThat(deadline.isBecauseOfShutdown()).isFalse();
    }

    @Test
    void hasExpired_whenTheBudgetIsUp_thenItSaysSo() {
        Deadline deadline = Deadline.of(Duration.ZERO, () -> false);

        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isFalse();
        assertThat(deadline.reason()).isEqualTo("it ran out of its deadline");
    }

    @Test
    void hasExpired_whenTheInstanceIsStopping_thenItSaysSoWhateverBudgetIsLeft() {
        AtomicBoolean stopping = new AtomicBoolean();
        Deadline deadline = Deadline.of(Duration.ofMinutes(5), stopping::get);
        assertThat(deadline.hasExpired()).isFalse();

        stopping.set(true);

        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isTrue();
        assertThat(deadline.reason()).isEqualTo("this instance is stopping");
    }

    /**
     * Where both are true, what an operator has to be told is that somebody deployed - not that a run that was
     * being stopped anyway also ran out of time.
     */
    @Test
    void hasExpired_whenBothEndedIt_thenTheShutdownIsWhatIsReported() {
        Deadline deadline = Deadline.of(Duration.ZERO, () -> true);

        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isTrue();
    }

    /** Over is over: the suppliers are asked no further, so a step reports the same reason all the way out. */
    @Test
    void hasExpired_onceItIsOver_thenItStaysOverAndKeepsItsReason() {
        AtomicBoolean stopping = new AtomicBoolean(true);
        Deadline deadline = Deadline.of(Duration.ofMinutes(5), stopping::get);
        assertThat(deadline.hasExpired()).isTrue();

        stopping.set(false);

        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isTrue();
    }

    @Test
    void none_thenNothingEndsTheRun() {
        Deadline deadline = Deadline.none();

        assertThat(deadline.hasExpired()).isFalse();
    }

    @Test
    void afterChecks_thenItIsOverOnTheGivenCheck() {
        Deadline deadline = Deadline.afterChecks(3);

        assertThat(deadline.hasExpired()).isFalse();
        assertThat(deadline.hasExpired()).isFalse();
        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isFalse();
    }

    @Test
    void stopping_thenItIsOverBecauseOfTheShutdown() {
        Deadline deadline = Deadline.stopping();

        assertThat(deadline.hasExpired()).isTrue();
        assertThat(deadline.isBecauseOfShutdown()).isTrue();
    }
}
