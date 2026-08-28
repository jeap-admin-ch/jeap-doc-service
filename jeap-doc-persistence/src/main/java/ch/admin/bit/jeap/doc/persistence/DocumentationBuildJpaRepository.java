package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface DocumentationBuildJpaRepository extends JpaRepository<DocumentationBuildEntity, Long> {

    /**
     * The published site of a site: the newest build that succeeded. By id rather than by {@code finished_at},
     * because the sequence is monotonic where the clocks of two instances are not.
     */
    Optional<DocumentationBuildEntity> findFirstBySiteAndStateOrderByIdDesc(String site, BuildState state);

    List<DocumentationBuildEntity> findBySiteAndStateOrderByIdDesc(String site, BuildState state, Limit limit);

    /** The history of one site for the administration API, newest first. */
    List<DocumentationBuildEntity> findBySiteOrderByIdDesc(String site, Limit limit);

    /** One build of one site - by both, because the sequence handing out the identifiers is shared. */
    Optional<DocumentationBuildEntity> findByIdAndSite(long id, String site);

    /** The running builds in full, where {@link #findRunningIds()} is the projection the clean-up asks for. */
    List<DocumentationBuildEntity> findByStateOrderByIdDesc(BuildState state);

    @Query("select b.id from DocumentationBuildEntity b where b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING")
    List<Long> findRunningIds();

    /**
     * The sites owing a build because one of theirs never finished. Distinct, because an instance that died
     * twice leaves two rows for one site and the caller wants the site once.
     */
    @Query("select distinct b.site from DocumentationBuildEntity b "
           + "where b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING")
    List<String> findSitesWithRunningBuilds();

    /** What is about to be abandoned, read before the update so that the caller learns what it gave up on. */
    List<DocumentationBuildEntity> findBySiteAndState(String site, BuildState state);

    /**
     * Gives up on the builds of a site that are still marked as running. The caller holds that site's lock, so
     * their lease has expired and whatever is still writing has lost its claim.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DocumentationBuildEntity b
               set b.state = ch.admin.bit.jeap.doc.domain.BuildState.ABANDONED,
                   b.finishedAt = :finishedAt,
                   b.failureReason = 'The instance running this build stopped, and its lock has since expired.'
             where b.site = :site and b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING
            """)
    int abandonRunning(@Param("site") String site, @Param("finishedAt") Instant finishedAt);

    /**
     * Forgets where a build's site was, once its objects have been removed. Without it the retention offers the
     * same prefixes again on every build, and each one costs a listing of a prefix that is already empty.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentationBuildEntity b set b.objectPrefix = null where b.objectPrefix = :objectPrefix")
    int forgetObjectPrefix(@Param("objectPrefix") String objectPrefix);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentationBuildEntity b where b.finishedAt is not null "
           + "and b.finishedAt < :finishedBefore and b.id not in :keep")
    int deleteFinishedBefore(@Param("finishedBefore") Instant finishedBefore,
                             @Param("keep") Collection<Long> keep);
}
