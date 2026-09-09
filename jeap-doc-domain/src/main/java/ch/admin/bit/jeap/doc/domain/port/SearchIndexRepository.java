package ch.admin.bit.jeap.doc.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What has been indexed for each site.
 * <p>
 * The newest published index of a site is the one served, and there is no second place saying so - so there is
 * no second place that could disagree with it. An index that is being built is a row too, which is what lets an
 * operator see that one is running and what stops a failure being silent.
 */
public interface SearchIndexRepository {

    /**
     * Records that an index of the given site is being built, and hands back its identifier - which is the
     * path segment its files are published under.
     */
    long start(String site, String instance, Instant startedAt);

    /**
     * Records that it was built and published. From this moment it is the index the site serves.
     */
    void published(long id, String objectPrefix, int records, Instant finishedAt);

    /**
     * Records that it could not be built. What was published before goes on being served.
     */
    void failed(long id, String reason, Instant finishedAt);

    /** The index a site is served from: the newest one that was published. */
    Optional<PublishedSearchIndex> currentOf(String site);

    /**
     * The published indexes of a site that are no longer the current one, oldest first, keeping the newest
     * {@code keep} of them.
     * <p>
     * <b>Not just the ones that are not current.</b> A reader whose browser has the manifest of the index that
     * was current a minute ago is still fetching its chunks by name, so the one it replaced has to outlive the
     * swap - see {@link ch.admin.bit.jeap.doc.domain.SearchIndex}.
     */
    List<PublishedSearchIndex> supersededOf(String site, int keep);

    /**
     * The runs that produced nothing anyone is served and that the service has no further use for: one still
     * recorded as running long after any live run could be, and one that failed long enough ago.
     * <p>
     * <b>Never a published one</b>, whatever its age. What is published is what the site is served from, and
     * the newest of them is the current index - so an age rule over them would take the search off a site
     * nobody has had to republish.
     *
     * @param runningStartedBefore a run still recorded as running that started before this was interrupted or
     *                             lost its instance; it has to be far enough back that no live run is caught
     * @param failedFinishedBefore a run that failed before this has said what it had to say
     */
    List<AbandonedSearchIndex> abandoned(Instant runningStartedBefore, Instant failedFinishedBefore);

    /** Forgets one, once its files are gone. */
    void forget(long id);
}
