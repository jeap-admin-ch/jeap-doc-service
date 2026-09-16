package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayTimeTest {

    private static final Instant WHEN = Instant.parse("2026-09-15T05:45:52.262519Z");

    /** To the second, in the JVM's default time zone, and nothing after it. */
    @Test
    void of_writesTheLocalTimeToTheSecond() {
        String expected = LocalDateTime.ofInstant(WHEN, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        assertThat(DisplayTime.of(WHEN)).isEqualTo(expected).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    /** A time with a zone of its own is shown in the default zone too, so every timestamp on a site agrees. */
    @Test
    void of_whenTheTimeCarriesAnotherZone_thenItIsShownInTheDefaultZone() {
        ZonedDateTime elsewhere = WHEN.atZone(ZoneOffset.ofHours(-7));

        assertThat(DisplayTime.of(elsewhere)).isEqualTo(DisplayTime.of(WHEN));
    }

    @Test
    void orEmpty_whenThereIsNoTime_thenEmpty() {
        assertThat(DisplayTime.orEmpty(null)).isEmpty();
    }
}
