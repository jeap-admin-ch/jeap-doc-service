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
 * <b>In the service's own time zone, and it says which.</b> That zone is what the instances are configured
 * with - the same one the publication schedules are evaluated in, and the one the configuration documents them
 * as using - so a reader comparing a page against a schedule is comparing like with like. Without the
 * designator the number would be unreadable for anybody who does not already know the container's {@code TZ},
 * and it would change under them the day somebody set it. Whoever wants the instant reads the ISO-8601 value
 * that the front matter and {@code site.json} carry.
 */
public final class DisplayTime {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            // English, like everything else this service publishes: the zone designator is a name, and the
            // default locale of a container is not a decision anybody made.
            .ofPattern("yyyy-MM-dd HH:mm:ss zzz", Locale.ENGLISH)
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
