package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationBuild;
import ch.admin.bit.jeap.doc.domain.port.ContainerMemory;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
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
 * Moving a build to {@link BuildState#SUCCEEDED} is the publication of its site: there is no second table saying
 * which site is served, so the state and the prefix cannot disagree, and the switch is one row in one
 * transaction - the only thing about publishing a site that S3 cannot make atomic.
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
    public DocumentationBuild start(String site, BuildTrigger trigger, String instance, Instant startedAt) {
        DocumentationBuildEntity entity = new DocumentationBuildEntity();
        entity.setSite(site);
        entity.setTrigger(trigger);
        entity.setState(BuildState.RUNNING);
        entity.setStartedAt(startedAt);
        entity.setInstance(instance);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild succeeded(long id, String objectPrefix, int pageCount, long sizeInBytes,
                                        long docusaurusMillis, ContainerMemory.Peak memoryPeak,
                                        Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.SUCCEEDED);
        // A build whose lease was lost may have been marked ABANDONED by another instance while it was still
        // running. It succeeded after all, and a succeeded build must not carry a reason saying otherwise.
        entity.setFailureReason(null);
        entity.setObjectPrefix(objectPrefix);
        entity.setPageCount(pageCount);
        entity.setSizeInBytes(sizeInBytes);
        entity.setDocusaurusMillis(docusaurusMillis);
        setMemoryPeak(entity, memoryPeak);
        entity.setFinishedAt(finishedAt);
        return toDomain(builds.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public DocumentationBuild failed(long id, String failureReason, ContainerMemory.Peak memoryPeak,
                                     Instant finishedAt) {
        DocumentationBuildEntity entity = require(id);
        entity.setState(BuildState.FAILED);
        entity.setFailureReason(shortened(failureReason));
        setMemoryPeak(entity, memoryPeak);
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
    public List<DocumentationBuild> abandonRunning(String site, Instant finishedAt) {
        // Read before the update, because a bulk update reports a count and the caller needs to know what it
        // gave up on: a run that was itself a recovery attempt is not retried again.
        List<DocumentationBuild> running = builds.findBySiteAndState(site, BuildState.RUNNING).stream()
                .map(DocumentationBuildRepositoryAdapter::toDomain)
                .toList();
        if (running.isEmpty()) {
            return List.of();
        }
        builds.abandonRunning(site, finishedAt);
        return running.stream()
                .map(build -> build.abandonedAt(finishedAt))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> sitesWithRunningBuilds() {
        return new LinkedHashSet<>(builds.findSitesWithRunningBuilds());
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
    public Optional<DocumentationBuild> find(String site, long id) {
        return builds.findByIdAndSite(id, site).map(DocumentationBuildRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentationBuild> published(String site) {
        return builds.findFirstBySiteAndStateOrderByIdDesc(site, BuildState.SUCCEEDED)
                .map(DocumentationBuildRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastSuccessAt(String site) {
        return builds.findFirstBySiteAndStateOrderByIdDesc(site, BuildState.SUCCEEDED)
                .map(DocumentationBuildEntity::getFinishedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> prefixesBeyondRetention(String site, int keep) {
        // The ones to keep plus a bounded window after them, so a site with a long history does not read all of
        // it to delete two objects. It also bounds how much one build cleans up: a retention that was lowered by
        // a lot is worked off over the next few builds rather than in one.
        List<DocumentationBuildEntity> succeeded =
                builds.findBySiteAndStateOrderByIdDesc(site, BuildState.SUCCEEDED,
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

    /**
     * Stores what the build did to the memory of its container, or leaves the three columns as they are.
     * <p>
     * A container whose memory cannot be read gives no peak, and a row that has none is not the same as a row
     * that says zero - so nothing is written rather than a zero that would read as a build that used no
     * memory at all.
     */
    private static void setMemoryPeak(DocumentationBuildEntity entity, ContainerMemory.Peak peak) {
        if (peak == null) {
            return;
        }
        entity.setMemoryPeakBytes(peak.usedBytes());
        // The port says -1 for a container nothing names a limit for, and the column is nullable: writing the
        // sentinel would leave rows that skew any average an operator takes over the column.
        entity.setMemoryLimitBytes(peak.limitBytes() > 0 ? peak.limitBytes() : null);
        entity.setMemoryPeakExact(peak.exact());
    }

    /**
     * The peak as the domain reads it. All three columns are written together, so the used bytes being there is
     * what says the row has one.
     */
    private static ContainerMemory.Peak memoryPeakOf(DocumentationBuildEntity entity) {
        if (entity.getMemoryPeakBytes() == null) {
            return null;
        }
        return new ContainerMemory.Peak(entity.getMemoryPeakBytes(),
                entity.getMemoryLimitBytes() == null ? -1 : entity.getMemoryLimitBytes(),
                Boolean.TRUE.equals(entity.getMemoryPeakExact()));
    }

    private static DocumentationBuild toDomain(DocumentationBuildEntity entity) {
        return new DocumentationBuild(entity.getId(), entity.getSite(), entity.getTrigger(), entity.getState(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getInstance(), entity.getObjectPrefix(),
                entity.getPageCount(), entity.getSizeInBytes(), entity.getDocusaurusMillis(),
                memoryPeakOf(entity), entity.getFailureReason());
    }
}
