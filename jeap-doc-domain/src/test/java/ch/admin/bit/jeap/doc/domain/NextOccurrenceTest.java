package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class NextOccurrenceTest {

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    /** Half past nine on a Thursday morning, in the time zone the service reads its schedules in. */
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-03T07:30:00Z"), ZURICH);

    @Test
    void of_thenTheNextFiringInTheTimeZoneOfTheService() {
        assertThat(NextOccurrence.of("0 5 6-20 * * *", NOW))
                .contains(Instant.parse("2026-09-03T08:05:00Z"));
        assertThat(NextOccurrence.of("0 45 5-19 * * *", NOW))
                .contains(Instant.parse("2026-09-03T07:45:00Z"));
    }

    /**
     * Tomorrow, where today's last firing has gone: the working-day schedules stop in the evening, and a page
     * read at midnight must not say the documentation changes in a moment.
     */
    @Test
    void of_whenTodaysLastFiringHasGone_thenTomorrows() {
        Clock lateAtNight = Clock.fixed(Instant.parse("2026-09-03T21:30:00Z"), ZURICH);

        assertThat(NextOccurrence.of("0 45 5-19 * * *", lateAtNight))
                .contains(Instant.parse("2026-09-04T03:45:00Z"));
    }

    /**
     * <b>An empty schedule means never</b>, which is a legitimate configuration - a site published only when
     * something is uploaded to it, and an instance that imports no architecture repository. It answers with
     * nothing rather than with a date nothing will happen on.
     */
    @Test
    void of_whenThereIsNoSchedule_thenNothing() {
        assertThat(NextOccurrence.of(null, NOW)).isEmpty();
        assertThat(NextOccurrence.of("", NOW)).isEmpty();
        assertThat(NextOccurrence.of("   ", NOW)).isEmpty();
    }

    /**
     * A value that is not a cron expression this service can read is worth a page that says nothing about when
     * the documentation changes next - not a build that fails over a string. The startup checks are what refuse
     * such a value in the first place.
     */
    @Test
    void of_whenTheValueIsNoCronExpression_thenNothingRatherThanAFailure() {
        assertThat(NextOccurrence.of("every other tuesday", NOW)).isEmpty();
        assertThat(NextOccurrence.of("0 5 6-20 * *", NOW)).describedAs("five fields, not six").isEmpty();
    }

    /** Whitespace around a value somebody wrote in YAML must not decide whether the page says anything. */
    @Test
    void of_whenTheValueIsPadded_thenItIsStillRead() {
        assertThat(NextOccurrence.of("  0 45 5-19 * * *  ", NOW))
                .contains(Instant.parse("2026-09-03T07:45:00Z"));
    }
}
