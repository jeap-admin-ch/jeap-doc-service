package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Removes what index runs left behind that nobody is served.
 * <p>
 * {@link SearchIndexing} removes the index it superseded as soon as it has published its own, so the ordinary
 * case needs nothing from here. <b>What needs it is a run that never got that far</b>: an instance killed
 * between writing its files and recording that it had leaves a prefix in the object storage and a row that
 * says it is still running, and neither is reachable from anything - the row is never published, so it is
 * never superseded, so nothing ever offers it for removal.
 * <p>
 * That is the same shape of leftover a killed build leaves, and the same rule applies to the fix: <b>it only
 * ever touches runs that were never published.</b> What a site is served from is the newest published run, and
 * nothing here can reach one - which is what makes deleting by age safe here where
 * {@code docs/operating-the-bucket.md} explains at length that it is not safe over the sites.
 * <p>
 * Of several instances, one runs it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexHousekeeping {

    /** How long the lock of this nightly job survives an instance that dies holding it. */
    private static final Duration HOUSEKEEPING_LEASE = Duration.ofMinutes(30);

    private final SearchIndexRepository indexes;
    private final SitePublicationStorage storage;
    private final SearchProperties properties;
    private final Clock clock;
    private final ExclusiveWork exclusiveWork;

    /**
     * When this runs is decided by {@link DocumentationBuildScheduling}, on the same schedule as the other
     * clean-ups.
     */
    public void removeAbandonedRuns() {
        if (!properties.isEnabled()) {
            // An instance that does not index has none of its own. It may still have the leftovers of a
            // version that did, and the instance that indexes now is the one that should remove them.
            return;
        }
        exclusiveWork.underLock("searchIndexHousekeeping", HOUSEKEEPING_LEASE, this::removeAbandonedRunsNow);
    }

    private void removeAbandonedRunsNow() {
        Instant now = clock.instant();
        List<AbandonedSearchIndex> abandoned = indexes.abandoned(
                now.minus(properties.getAbandonedAfter()),
                now.minus(properties.getFailureRetention()));
        int removed = 0;
        for (AbandonedSearchIndex run : abandoned) {
            // The files first and the row second. The other way round, a delete that failed would leave a
            // prefix nothing names any more - which is exactly the leak this job exists to clear.
            try {
                storage.delete(SearchIndex.prefixOf(run.site(), run.id()));
                indexes.forget(run.id());
                removed++;
            } catch (RuntimeException e) {
                // The row stays and the next run offers it again. A prefix that will not delete costs storage,
                // and giving up on the rest of the list over it would cost more of it.
                log.warn("The abandoned search index {} of {} could not be removed.", run.id(), run.site(), e);
            }
        }
        if (removed > 0) {
            log.info("Removed {} abandoned search index run(s) and whatever they had written. A run is "
                     + "abandoned when it was still running {} ago or failed more than {} ago.",
                    removed, properties.getAbandonedAfter(), properties.getFailureRetention());
        } else {
            log.debug("No abandoned search index run to remove.");
        }
    }
}
