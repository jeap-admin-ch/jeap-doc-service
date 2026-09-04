package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * How long an import step may go on fetching, and whether it still should.
 * <p>
 * It is asked between items rather than enforced by an interrupt. The work is a sequence of independent
 * requests, and cutting one in half buys nothing that stopping cleanly after it does not.
 * <p>
 * Two things end a run, and a step reports which: the budget it was given, and an <b>instance that is
 * stopping</b>. The second is why this is asked at all on a deployment - an import of a whole landscape takes
 * minutes, every deployment interrupts one, and a run that notices between two requests leaves the shutdown
 * nothing to interrupt.
 */
final class Deadline {

    private final BooleanSupplier expired;
    private final BooleanSupplier stopping;

    /** Whether it is over at all, so that a supplier is asked no further once it is. */
    private volatile boolean over;

    /** Whether what ended it was the shutdown rather than the budget - see {@link #reason()}. */
    private volatile boolean shutdown;

    private Deadline(BooleanSupplier expired, BooleanSupplier stopping) {
        this.expired = expired;
        this.stopping = stopping;
    }

    public static Deadline of(Duration budget) {
        return of(budget, () -> false);
    }

    /**
     * The budget, and the instance it runs on: the run is over when either the time is up or the instance has
     * begun to stop.
     */
    public static Deadline of(Duration budget, BooleanSupplier stopping) {
        long expiresAtNanos = System.nanoTime() + budget.toNanos();
        return new Deadline(() -> System.nanoTime() - expiresAtNanos >= 0, stopping);
    }

    /** A deadline that never expires, for a test that is not about the deadline. */
    public static Deadline none() {
        return new Deadline(() -> false, () -> false);
    }

    /**
     * A deadline that has expired once it has been asked the given number of times, for a test that is about
     * what a truncated run leaves behind - and must not depend on how fast the machine running it is.
     */
    public static Deadline afterChecks(int checks) {
        AtomicInteger asked = new AtomicInteger();
        return new Deadline(() -> asked.incrementAndGet() >= checks, () -> false);
    }

    /** A deadline that is over because the instance is stopping, for a test about a shutdown. */
    public static Deadline stopping() {
        return new Deadline(() -> false, () -> true);
    }

    public boolean hasExpired() {
        if (over) {
            return true;
        }
        // The shutdown first: where both are true, what an operator has to be told is that somebody deployed,
        // not that a run that was being stopped anyway also ran out of time.
        if (stopping.getAsBoolean()) {
            shutdown = true;
            over = true;
        } else if (expired.getAsBoolean()) {
            over = true;
        }
        return over;
    }

    /** Whether it was the instance stopping that ended the run, rather than the budget it was given. */
    public boolean isBecauseOfShutdown() {
        return shutdown;
    }

    /**
     * Why a run stopped early, as one clause - so that the log line and the reason recorded with the outcome
     * say the same thing. Only meaningful once {@link #hasExpired()} has answered true.
     */
    public String reason() {
        return shutdown ? "this instance is stopping" : "it ran out of its deadline";
    }
}
