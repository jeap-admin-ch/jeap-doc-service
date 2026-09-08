package ch.admin.bit.jeap.doc.domain.port;

import java.time.Duration;
import java.time.Instant;

/**
 * A full publication of a site that is over: when it was asked for, when its last part finished, and how many
 * parts it went through.
 * <p>
 * <b>Over</b> means no part of it is still building and none is still owed a build. Until then its elapsed time
 * is not a number to publish - it would read as a publication getting slower while it is simply not finished.
 *
 * @param id          the identifier every part of the publication carried
 * @param requestedAt when the publication was asked for
 * @param finishedAt  when its last part finished
 * @param parts       how many parts it went through, generated or found unchanged
 */
public record CompletedPublication(String id, Instant requestedAt, Instant finishedAt, int parts) {

    /** How long the publication took, start to finish - the number an operator actually waits for. */
    public Duration duration() {
        return Duration.between(requestedAt, finishedAt);
    }
}
