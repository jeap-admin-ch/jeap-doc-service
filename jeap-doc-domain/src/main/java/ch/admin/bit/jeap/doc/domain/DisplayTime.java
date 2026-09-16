package ch.admin.bit.jeap.doc.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * How a timestamp is written where a person reads it, on a generated page or in the layout.
 * <p>
 * <b>One place, because it is one decision.</b> Every generated tree, the About page and the layout of every
 * site show the same kind of timestamp, and three copies of the pattern are three things that drift.
 * <p>
 * <b>In the JVM's default time zone, to the second, and without a zone name</b>: {@code 2026-09-15 07:45:52}.
 * That zone is the one the schedules are evaluated in, so a page and a schedule read alike. Whoever needs the
 * exact instant reads the ISO-8601 value that the front matter and {@code site.json} carry beside it.
 */
public final class DisplayTime {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private DisplayTime() {
    }

    /** The timestamp as a reader sees it. */
    public static String of(TemporalAccessor when) {
        return FORMAT.format(when);
    }

    /** The same, and the empty string where there is no timestamp to show. */
    public static String orEmpty(Instant when) {
        return when == null ? "" : of(when);
    }
}
