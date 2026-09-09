package ch.admin.bit.jeap.doc.domain.port;

/**
 * An index run that produced nothing anyone is served: it was interrupted, its instance was killed, or it
 * failed long enough ago to stop being evidence.
 * <p>
 * It carries no object prefix, because a run that never got as far as publishing never recorded one - and it
 * does not need to. <b>A run's prefix follows from its identifier</b>
 * ({@link ch.admin.bit.jeap.doc.domain.SearchIndex#prefixOf}), which is what lets whatever it managed to write
 * be found and removed.
 *
 * @param id   the run, and the last segment of the prefix it wrote under
 * @param site the site it was indexing
 */
public record AbandonedSearchIndex(long id, String site) {
}
