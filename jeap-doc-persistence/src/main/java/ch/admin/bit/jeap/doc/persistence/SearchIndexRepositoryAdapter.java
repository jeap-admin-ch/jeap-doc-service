package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.port.AbandonedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.PublishedSearchIndex;
import ch.admin.bit.jeap.doc.domain.port.SearchIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The record of the search index runs, on PostgreSQL.
 * <p>
 * Moving a run to {@link SearchIndexState#PUBLISHED} is the publication of its index, the way a build's success
 * is the publication of its part: one row in one transaction, and no second table that could disagree with it.
 */
@Repository
@RequiredArgsConstructor
class SearchIndexRepositoryAdapter implements SearchIndexRepository {

    /**
     * How much of a failure reason is kept, as {@code DocumentationBuildRepositoryAdapter} keeps one: the whole
     * transcript is in the log of the instance that ran it.
     */
    static final int MAX_FAILURE_REASON = 8192;

    /**
     * How many superseded indexes are ever looked at in one go. A site keeps a handful; a bound is here so a
     * housekeeping query cannot become a table scan if something has gone wrong and thousands have piled up.
     */
    private static final int MOST_EVER_KEPT = 100;

    /**
     * How many abandoned runs one pass removes. A bound rather than a limit anybody should reach: each of them
     * is a delete of a whole prefix, and a job that found thousands would otherwise hold its lock for hours.
     * What it does not remove is offered again the next night.
     */
    private static final int MOST_EVER_REMOVED = 200;

    private final SearchIndexJpaRepository indexes;

    @Override
    @Transactional
    public long start(String site, String instance, Instant startedAt) {
        SearchIndexEntity entity = new SearchIndexEntity();
        entity.setSite(site);
        entity.setState(SearchIndexState.RUNNING);
        entity.setInstance(instance);
        entity.setStartedAt(startedAt);
        return indexes.save(entity).getId();
    }

    @Override
    @Transactional
    public void published(long id, String objectPrefix, int records, Instant finishedAt) {
        SearchIndexEntity entity = indexes.getReferenceById(id);
        entity.setObjectPrefix(objectPrefix);
        entity.setRecords(records);
        entity.setFinishedAt(finishedAt);
        // Last, and the only one of these that changes what is served.
        entity.setState(SearchIndexState.PUBLISHED);
    }

    @Override
    @Transactional
    public void failed(long id, String reason, Instant finishedAt) {
        SearchIndexEntity entity = indexes.getReferenceById(id);
        entity.setState(SearchIndexState.FAILED);
        entity.setFinishedAt(finishedAt);
        entity.setFailureReason(shortened(reason));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedSearchIndex> currentOf(String site) {
        return published(site, Limit.of(1)).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishedSearchIndex> supersededOf(String site, int keep) {
        List<PublishedSearchIndex> all = published(site, Limit.of(MOST_EVER_KEPT));
        return all.size() <= keep ? List.of() : List.copyOf(all.subList(keep, all.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbandonedSearchIndex> abandoned(Instant runningStartedBefore, Instant failedFinishedBefore) {
        return indexes.findAbandoned(runningStartedBefore, failedFinishedBefore, Limit.of(MOST_EVER_REMOVED))
                .stream()
                .map(entity -> new AbandonedSearchIndex(entity.getId(), entity.getSite()))
                .toList();
    }

    @Override
    @Transactional
    public void forget(long id) {
        indexes.deleteById(id);
    }

    private List<PublishedSearchIndex> published(String site, Limit limit) {
        return indexes.findBySiteAndStateOrderByIdDesc(site, SearchIndexState.PUBLISHED, limit).stream()
                .map(entity -> new PublishedSearchIndex(entity.getId(), entity.getObjectPrefix(),
                        entity.getRecords() == null ? 0 : entity.getRecords(), entity.getStartedAt(),
                        entity.getFinishedAt()))
                .toList();
    }

    private static String shortened(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_FAILURE_REASON ? reason
                : reason.substring(reason.length() - MAX_FAILURE_REASON);
    }
}
