package ch.admin.bit.jeap.doc.domain;

import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a schedule fires next.
 * <p>
 * Every schedule of this service is a Spring six-field cron expression read in the time zone of the service,
 * and <b>an empty one means never</b> - a site that configures no publication schedule is published only when
 * something is uploaded to it, and an empty import cron means the architecture repository is not imported at
 * all. So this answers with an empty value for those, rather than with a date nothing will happen on.
 * <p>
 * It is here rather than in whatever needs it because two things do: the page that publishes the schedules to a
 * reader, and the log line that could say the same at startup. A cron expression parsed in two places is a cron
 * expression that means two things.
 */
public final class NextOccurrence {

    private NextOccurrence() {
    }

    /**
     * The next firing of the given cron expression after now, in the time zone of the clock - or empty when
     * there is no schedule, or when what is configured is not a cron expression this service can read.
     * <p>
     * <b>It does not throw on a value it cannot parse.</b> A schedule that fails the startup checks never
     * reaches here, and one that reaches here and cannot be read is worth a page saying nothing about when the
     * documentation changes next - not a build that fails over a string.
     */
    public static Optional<Instant> of(String cron, Clock clock) {
        if (cron == null || cron.isBlank()) {
            return Optional.empty();
        }
        try {
            ZonedDateTime next = CronExpression.parse(cron.strip()).next(ZonedDateTime.now(clock));
            return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
        } catch (IllegalArgumentException notACronExpression) {
            return Optional.empty();
        }
    }
}
