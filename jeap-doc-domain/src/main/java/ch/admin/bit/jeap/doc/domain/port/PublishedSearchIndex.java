package ch.admin.bit.jeap.doc.domain.port;

import java.time.Instant;

/**
 * A search index that has been built and published.
 *
 * @param id           the identifier its prefix is named after
 * @param objectPrefix where its files are
 * @param records      how many pages it holds
 * @param startedAt    when its run began, which is <b>what its content is as of</b>: the pages are written at
 *                     the beginning of a run, so this and not {@link #builtAt} says what a publication it has
 *                     to cover is compared against
 * @param builtAt      when it was built - what a reader is told when the index lags the site
 */
public record PublishedSearchIndex(long id, String objectPrefix, int records, Instant startedAt,
                                   Instant builtAt) {
}
