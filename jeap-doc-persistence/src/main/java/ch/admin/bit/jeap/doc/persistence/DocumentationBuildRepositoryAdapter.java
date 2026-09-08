package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.Publication;
import ch.admin.bit.jeap.doc.domain.port.CompletedPublication;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.PublicationTotals;
import ch.admin.bit.jeap.doc.domain.port.PublishedPart;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The record of the documentation builds, on PostgreSQL.
 * <p>
 * Moving a build to {@link BuildState#SUCCEEDED} is the publication of its part: there is no second table saying
 * which files are served, so the state and the prefix cannot disagree, and the switch is one row in one
 * transaction - the only thing about publishing that S3 cannot make atomic.
 */
@Repository
@RequiredArgsConstructor
class DocumentationBuildRepositoryAdapter implements DocumentationBuildRepository {

    /**
     * How much of a failure reason is kept. The generator's output can be a thousand lines of bundler stack, the
     * row is kept for as long as {@code jeap.doc.build.history-retention} says, and what an operator reads is
     * the end of it - the whole transcript is in the log of the instance that ran the build.
     */
    static final int MAX_FAILURE_REASON = 8192;

    /** How many superseded sites one build removes at most - see {@link #prefixesBeyondRetention}. */
    private static final int REMOVED_PER_BUILD = 50;

    private static final String TRUNCATION_NOTE =
            "[... truncated; the full output is in the log of the instance that ran this build]%n".formatted();

    private final DocumentationBuildJpaRepository builds;

    @Override
    @Transactional
    public DocumentationBuild start(PartKey part, BuildTrigger trigger, String instance, Instant startedAt,
                                    Publication publication) {
        DocumentationBuildEntity entity = new DocumentationBuildEntity();
        entity.setSite(part.site());
        entity.setPart(part.part());
        entity.setTrigger(trigger);
        entity.setState(BuildState.RUNNING);
        entity.setStartedAt(startedAt);
        entity.setInstance(instance);
        if (publication != null) {
            // Inherited from the request, so that the wall clock of a whole publication is readable off the
            // rows once its last part has finished - see lastCompletedPublicationOf.
            entity.setPublicationId(publication.id());
            entity.setPublicationRequestedAt(publication.requestedAt());
        }
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild succeeded(long id, String objectPrefix, int pageCount, long sizeInBytes,
                                        long docusaurusMillis, String contentDigest, Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.SUCCEEDED);
        // A build whose lease was lost may have been marked ABANDONED by another instance while it was still
        // running. It succeeded after all, and a succeeded build must not carry a reason saying otherwise.
        entity.setFailureReason(null);
        entity.setObjectPrefix(objectPrefix);
        entity.setPageCount(pageCount);
        entity.setSizeInBytes(sizeInBytes);
        entity.setDocusaurusMillis(docusaurusMillis);
        entity.setContentDigest(contentDigest);
        entity.setFinishedAt(finishedAt);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild failed(long id, String failureReason, Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.FAILED);
        entity.setFailureReason(shortened(failureReason));
        entity.setFinishedAt(finishedAt);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild skipped(long id, Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.SKIPPED);
        entity.setFinishedAt(finishedAt);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild aborted(long id, String reason, Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.ABORTED);
        entity.setFailureReason(shortened(reason));
        entity.setFinishedAt(finishedAt);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public List<DocumentationBuild> abandonRunning(PartKey part, Instant finishedAt) {
        // Read before the update, because a bulk update reports a count and the caller needs to know what it
        // gave up on: a run that was itself a recovery attempt is not retried again.
        List<DocumentationBuild> running = builds
                .findBySiteAndPartAndState(part.site(), part.part(), BuildState.RUNNING).stream()
                .map(DocumentationBuildRepositoryAdapter::toDomain)
                .toList();
        if (running.isEmpty()) {
            return List.of();
        }
        builds.abandonRunning(part.site(), part.part(), finishedAt);
        return running.stream()
                .map(build -> build.abandonedAt(finishedAt))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<PartKey> partsWithRunningBuilds() {
        return new LinkedHashSet<>(builds.findPartsWithRunningBuilds());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentationBuild> running() {
        return builds.findByStateOrderByIdDesc(BuildState.RUNNING).stream()
                .map(DocumentationBuildRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentationBuild> recent(String site, int limit) {
        // A limit of zero or less is not a query Spring Data will accept, and asking for no builds is not
        // something a caller means - the API clamps it too, and this is the last place that can.
        return builds.findBySiteOrderByIdDesc(site, Limit.of(Math.max(limit, 1))).stream()
                .map(DocumentationBuildRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentationBuild> recentOf(PartKey part, int limit) {
        return builds.findBySiteAndPartOrderByIdDesc(part.site(), part.part(), Limit.of(Math.max(limit, 1)))
                .stream()
                .map(DocumentationBuildRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentationBuild> find(String site, long id) {
        return builds.findByIdAndSite(id, site).map(DocumentationBuildRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentationBuild> published(PartKey part) {
        return builds.findFirstBySiteAndPartAndStateOrderByIdDesc(part.site(), part.part(), BuildState.SUCCEEDED)
                .map(DocumentationBuildRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishedPart> publishedPartsOf(String site) {
        return builds.findPublishedParts(site);
    }

    /**
     * One statement, and the newest of what it finds - see
     * {@link DocumentationBuildJpaRepository#findCompletedPublications}.
     */
    /**
     * How far back the last completed publication is looked for, counted from the newest publication of that
     * site rather than from now.
     * <p>
     * It has to reach past a publication that is still running, since that one is not the answer - and no
     * further, because the aggregate below groups over every row in the window. A week is several publications
     * on any site that publishes at all.
     */
    private static final java.time.Duration PUBLICATION_WINDOW = java.time.Duration.ofDays(7);

    @Override
    @Transactional(readOnly = true)
    public Optional<CompletedPublication> lastCompletedPublicationOf(String site) {
        // Two statements, and the first is one indexed max(): without a bound the second one groups over every
        // publication row the retention holds, on every scrape, to answer with a single row.
        return builds.newestPublicationRequestedAt(site)
                .flatMap(newest -> builds
                        .findCompletedPublications(site, newest.minus(PUBLICATION_WINDOW), Limit.of(1))
                        .stream().findFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastSuccessAt(String site) {
        return builds.findFirstBySiteAndStateOrderByIdDesc(site, BuildState.SUCCEEDED)
                .map(DocumentationBuildEntity::getFinishedAt);
    }

    /**
     * The two outcomes that mean <i>this part is up to date</i>, newest first. A skip is one of them, and
     * since a part whose content has not moved is never generated, it is the ordinary one.
     */
    private static final java.util.List<BuildState> CONFIRMED_CURRENT =
            java.util.List.of(BuildState.SUCCEEDED, BuildState.SKIPPED);

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastCheckAt(String site) {
        return builds.findFirstBySiteAndStateInOrderByIdDesc(site, CONFIRMED_CURRENT)
                .map(DocumentationBuildEntity::getFinishedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public PublicationTotals publishedTotalsOf(String site) {
        PublicationTotals totals = builds.findPublishedTotals(site);
        return totals == null ? PublicationTotals.none() : totals;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> oldestPublicationAt(String site) {
        return builds.findOldestPublicationOf(site);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> prefixesBeyondRetention(PartKey part, int keep) {
        // The ones to keep plus a bounded window after them, so a site with a long history does not read all of
        // it to delete two objects. It also bounds how much one build cleans up: a retention that was lowered by
        // a lot is worked off over the next few builds rather than in one.
        List<DocumentationBuildEntity> succeeded =
                builds.findBySiteAndPartAndStateOrderByIdDesc(part.site(), part.part(), BuildState.SUCCEEDED,
                        Limit.of(Math.max(keep, 0) + REMOVED_PER_BUILD));
        return succeeded.stream()
                .skip(Math.max(keep, 0))
                .map(DocumentationBuildEntity::getObjectPrefix)
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .toList();
    }

    @Override
    @Transactional
    public void forgetObjectPrefix(String objectPrefix) {
        builds.forgetObjectPrefix(objectPrefix);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> runningIds() {
        return new LinkedHashSet<>(builds.findRunningIds());
    }

    @Override
    @Transactional
    public int deleteFinishedBefore(Instant finishedBefore, Set<Long> keep) {
        // `not in ()` is not valid SQL, so an empty set is given one identifier no sequence hands out.
        return builds.deleteFinishedBefore(finishedBefore, keep.isEmpty() ? Set.of(-1L) : keep);
    }

    /**
     * The end of a reason rather than all of it: it is the last lines that say what went wrong, and an
     * unbounded column plus a ninety day retention is how a database fills up with bundler output.
     */
    static String shortened(String reason) {
        if (reason == null || reason.length() <= MAX_FAILURE_REASON) {
            return reason;
        }
        int start = reason.length() - MAX_FAILURE_REASON + TRUNCATION_NOTE.length();
        // Never between the halves of a surrogate pair: the generator's output carries box drawing and emoji,
        // and an unpaired surrogate is rejected when it is encoded to UTF-8 - turning a recorded failure into a
        // second, unrelated one.
        if (Character.isLowSurrogate(reason.charAt(start))) {
            start++;
        }
        return TRUNCATION_NOTE + reason.substring(start);
    }

    private DocumentationBuildEntity require(long id) {
        return builds.findById(id).orElseThrow(() -> new IllegalStateException(
                "The build %d is not recorded; it is written before it starts and read back by its identifier."
                        .formatted(id)));
    }

    private static DocumentationBuild toDomain(DocumentationBuildEntity entity) {
        return new DocumentationBuild(entity.getId(), entity.getSite(), entity.getPart(), entity.getTrigger(),
                entity.getState(), entity.getStartedAt(), entity.getFinishedAt(), entity.getInstance(),
                entity.getObjectPrefix(), entity.getPageCount(), entity.getSizeInBytes(),
                entity.getDocusaurusMillis(), entity.getFailureReason(), entity.getContentDigest());
    }
}
