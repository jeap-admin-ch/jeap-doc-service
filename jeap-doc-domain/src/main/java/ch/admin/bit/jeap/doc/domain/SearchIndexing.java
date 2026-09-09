package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.port.BuiltSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.ExclusiveWork;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexBuilder;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Indexes the documentation for the search box.
 * <p>
 * <b>Once per publication, over the whole site, and after the parts have been published.</b> A build is one
 * part, so an index built inside one would cover one part and a reader inside one system would search only
 * that system; and indexing the whole site once per part would do the work fifty-two times for one
 * publication. The end of a build pass is the first moment at which everything that was owed has been
 * published - and since the parts of one publication are built by several instances, each of which ends its
 * pass at its own time, a run that finds the current index already newer than the newest publication builds
 * nothing.
 * <p>
 * <b>It cannot fail a publication.</b> The parts are already served by the time this runs, so a site whose
 * index could not be built goes on being searched with the index it had before - recorded on a row, logged at
 * error, and put right by the next pass that publishes something.
 * <p>
 * What it costs a reader is a short window: while a pass is running, the pages it has already published are
 * not findable yet. That is bounded by the pass rather than by a clock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexing {

    /** One lock per site: two sites may be indexed at once, one site may not be indexed twice. */
    private static final String LOCK_PREFIX = "searchIndex-";

    private final SearchIndexBuilder builder;
    private final SearchIndexRepository indexes;
    private final DocumentationBuildRepository builds;
    private final SitePublicationStorage storage;
    private final SearchProperties properties;
    private final Clock clock;
    private final ExclusiveWork exclusiveWork;

    /**
     * Indexes one site, if no other instance is already doing it and the site is not indexed already.
     *
     * @return whether this instance was the one that took the site's lock - which is not the same as having
     *         built an index, since a run that finds the current one up to date builds none
     */
    public boolean index(Site site) {
        if (!properties.isEnabled()) {
            log.debug("Not indexing {}: jeap.doc.search.enabled is off.", site.id());
            return false;
        }
        return exclusiveWork.underLock(LOCK_PREFIX + site.id(), properties.getLockLease(),
                () -> indexUnderLock(site));
    }

    private void indexUnderLock(Site site) {
        if (isAlreadyCovered(site)) {
            return;
        }
        long id = indexes.start(site.id(), instanceName(), clock.instant());
        BuiltSearchIndex built = null;
        boolean published = false;
        try {
            built = builder.build(site, SitePart.wholeSiteOf(site));
            String prefix = SearchIndex.prefixOf(site.id(), id);
            // The bundle has no shared files of its own: what a part build writes to the site's shared prefix
            // is the site's assets, and an index is neither. Both prefixes are its own.
            storage.publish(new PartPublication(prefix, prefix), built.directory());
            indexes.published(id, prefix, built.records(), clock.instant());
            published = true;
            log.info("The documentation of {} is searchable: {} page(s) indexed in {} ms, published as {}.",
                    site.id(), built.records(), built.millis(), id);
        } catch (RuntimeException e) {
            // Recorded and swallowed. What was published before goes on being served, and an index that could
            // not be built is not a reason to fail a schedule that will come round again.
            log.error("The documentation of {} could not be indexed. The index published before it is still "
                      + "being served.", site.id(), e);
            indexes.failed(id, e.getMessage(), clock.instant());
        } finally {
            if (built != null) {
                builder.discard(built);
            }
        }
        if (published) {
            // Outside the try, because it comes after the publication and must not be able to unrecord it: in
            // there, a database blip while reading the superseded rows would have flipped the row that was
            // just published to FAILED - taking the site back to the index before it and leaving housekeeping
            // to delete a bundle that was serving.
            removeWhatIsNoLongerNeeded(site);
        }
    }

    /**
     * Whether the index the site is served from was already built over everything the site has published.
     * <p>
     * <b>The lock alone does not answer this.</b> It is taken and released per run, and the parts of one
     * publication are built by several instances - so the instance whose pass ends last takes the freed lock a
     * moment after the first one has indexed, and would index the same content again and supersede a bundle
     * that is minutes old. What decides is the content: a run that <i>started</i> after the newest publication
     * of the site read every page of it.
     * <p>
     * Started rather than finished, because the content is written at the beginning of a run: an index that
     * began before a part was published may not hold that part, whatever time it finished at.
     */
    private boolean isAlreadyCovered(Site site) {
        Optional<Instant> lastPublication = builds.lastSuccessAt(site.id());
        Optional<Instant> indexedAt = indexes.currentOf(site.id()).map(PublishedSearchIndex::startedAt);
        if (lastPublication.isEmpty() || indexedAt.isEmpty()
            || !indexedAt.get().isAfter(lastPublication.get())) {
            return false;
        }
        log.debug("Not indexing {}: the index it is served from was built after its newest publication ({} "
                  + "after {}).", site.id(), indexedAt.get(), lastPublication.get());
        return true;
    }

    /**
     * Removes the indexes this site no longer needs - keeping the current one and the ones a reader may still
     * be fetching chunks of.
     * <p>
     * After the publication rather than before it, and never in the same breath as the swap: the index that
     * has just been replaced is exactly the one somebody is still searching in.
     */
    private void removeWhatIsNoLongerNeeded(Site site) {
        List<PublishedSearchIndex> superseded;
        try {
            superseded = indexes.supersededOf(site.id(), properties.getRetention());
        } catch (RuntimeException e) {
            // Nothing is wrong with what is served; the next run offers the same rows again.
            log.warn("The superseded search indexes of {} could not be read, so none were removed.",
                    site.id(), e);
            return;
        }
        for (PublishedSearchIndex old : superseded) {
            try {
                storage.delete(old.objectPrefix());
                indexes.forget(old.id());
            } catch (RuntimeException e) {
                // The row stays, so the next run offers it again. A prefix that could not be deleted costs
                // storage; failing the index run over it would cost the search.
                log.warn("The superseded search index {} of {} could not be removed.", old.id(), site.id(), e);
            }
        }
        if (!superseded.isEmpty()) {
            log.debug("Removed {} superseded search index(es) of {}.", superseded.size(), site.id());
        }
    }


    /**
     * Which instance ran it, so that its log can be found - the same host name a build records.
     */
    private static String instanceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.debug("The host name of this instance could not be read.", e);
            return "unknown";
        }
    }
}
