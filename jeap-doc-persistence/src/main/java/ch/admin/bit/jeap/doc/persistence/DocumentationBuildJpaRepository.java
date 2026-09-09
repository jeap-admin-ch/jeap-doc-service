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
     * What is published for one part: the newest build of it that succeeded. By id rather than by
     * {@code finished_at}, because the sequence is monotonic where the clocks of two instances are not.
     */
    Optional<DocumentationBuildEntity> findFirstBySiteAndPartAndStateOrderByIdDesc(String site, String part,
                                                                                   BuildState state);

    List<DocumentationBuildEntity> findBySiteAndPartAndStateOrderByIdDesc(String site, String part,
                                                                          BuildState state, Limit limit);

    /**
     * The newest succeeded build of a site, whichever part it is of - what says how long ago this documentation
     * was last published at all.
     */
    Optional<DocumentationBuildEntity> findFirstBySiteAndStateOrderByIdDesc(String site, BuildState state);

    /**
     * The newest build of a site in any of the given states - what "last confirmed current" is read by, over
     * SUCCEEDED and SKIPPED together.
     */
    Optional<DocumentationBuildEntity> findFirstBySiteAndStateInOrderByIdDesc(String site,
                                                                             Collection<BuildState> states);

    /** The history of one site - every part of it - for the administration API, newest first. */
    List<DocumentationBuildEntity> findBySiteOrderByIdDesc(String site, Limit limit);

    /** The history of one part, newest first. */
    List<DocumentationBuildEntity> findBySiteAndPartOrderByIdDesc(String site, String part, Limit limit);

    /**
     * What is published for every part of a site, as the projection that serving a request needs: the newest
     * succeeded build of each part.
     * <p>
     * The subquery is what makes it *the newest per part* rather than the newest of the site. It is one
     * statement and it runs behind the publication cache, so a request pays for it only when that cache is
     * cold.
     */
    @Query("""
            select new ch.admin.bit.jeap.doc.domain.port.PublishedPart(
                       b.part, b.objectPrefix, b.finishedAt, b.contentDigest, b.pageCount)
              from DocumentationBuildEntity b
             where b.site = :site
               and b.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED
               and b.id = (select max(o.id) from DocumentationBuildEntity o
                            where o.site = b.site and o.part = b.part
                              and o.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED)
            """)
    List<ch.admin.bit.jeap.doc.domain.port.PublishedPart> findPublishedParts(@Param("site") String site);

    /**
     * What the published parts of a site add up to: how many there are, their pages and their size.
     */
    @Query("""
            select new ch.admin.bit.jeap.doc.domain.port.PublicationTotals(
                       cast(count(b) as integer), cast(coalesce(sum(b.pageCount), 0) as integer),
                       coalesce(sum(b.sizeInBytes), 0))
              from DocumentationBuildEntity b
             where b.site = :site
               and b.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED
               and b.id = (select max(o.id) from DocumentationBuildEntity o
                            where o.site = b.site and o.part = b.part
                              and o.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED)
            """)
    ch.admin.bit.jeap.doc.domain.port.PublicationTotals findPublishedTotals(@Param("site") String site);

    /**
     * The full publications of a site that are <b>over</b>, newest first - what the publication gauges read.
     * <p>
     * Over means two things, and both are a {@code not exists}: no build of that publication is still running,
     * and no part of it is still owed one. Without the second, a publication whose parts are queued behind
     * another instance's work would report the elapsed time of the few that had finished.
     * <p>
     * The elapsed time is measured from when the publication was <b>asked for</b> rather than from when its
     * first part started, because the queueing is part of what an operator waits for. And {@code min} of it
     * although every row of one publication carries the same instant: it is an aggregate query, and the
     * grouping needs one.
     * <p>
     * <b>Parts and not builds.</b> A part aborted by a deployment is asked for again with the publication it
     * belonged to, so one part of one publication can have two rows - and a publication of fifty-two parts
     * would report fifty-three.
     * <p>
     * <b>Bounded by {@code since}.</b> This is read on every scrape and only the newest answer is ever used,
     * but the grouping and the two anti-joins run over every publication row the retention still holds -
     * the retention times the parts of the site. What is wanted is the last publication that is <i>over</i>, so
     * the window only has to reach back past the one that may still be running - see
     * {@link #newestPublicationRequestedAt}, which is where it starts.
     */
    @Query("""
            select new ch.admin.bit.jeap.doc.domain.port.CompletedPublication(
                       b.publicationId, min(b.publicationRequestedAt), max(b.finishedAt),
                       cast(count(distinct b.part) as integer))
              from DocumentationBuildEntity b
             where b.site = :site
               and b.publicationId is not null
               and b.publicationRequestedAt > :since
               and b.finishedAt is not null
               and not exists (select running.id from DocumentationBuildEntity running
                                where running.publicationId = b.publicationId
                                  and running.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING)
               and not exists (select owed.id from DocumentationBuildRequestEntity owed
                                where owed.publicationId = b.publicationId)
             group by b.publicationId
             order by min(b.publicationRequestedAt) desc
            """)
    List<ch.admin.bit.jeap.doc.domain.port.CompletedPublication> findCompletedPublications(
            @Param("site") String site, @Param("since") Instant since, Limit limit);

    /**
     * When the newest publication of a site was asked for, whichever state it is in - the bound the query
     * above is read from.
     * <p>
     * Relative to the site's own history and not to a clock: what is wanted is the last publication that is
     * over, so the window has to start a little before the newest one rather than a little before now. A site
     * published once a week and a site published hourly then both answer, and a test's fixture does not go out
     * of range as it ages.
     */
    @Query("select max(b.publicationRequestedAt) from DocumentationBuildEntity b "
           + "where b.site = :site and b.publicationId is not null")
    Optional<Instant> newestPublicationRequestedAt(@Param("site") String site);

    /**
     * When the oldest published part of a site was published. It is what says whether a part has quietly
     * stopped being rebuilt, which the newest publication cannot.
     */
    @Query("""
            select min(b.finishedAt) from DocumentationBuildEntity b
             where b.site = :site
               and b.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED
               and b.id = (select max(o.id) from DocumentationBuildEntity o
                            where o.site = b.site and o.part = b.part
                              and o.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED)
            """)
    Optional<Instant> findOldestPublicationOf(@Param("site") String site);

    /** One build of one site - by both, because the sequence handing out the identifiers is shared. */
    Optional<DocumentationBuildEntity> findByIdAndSite(long id, String site);

    /** The running builds in full, where {@link #findRunningIds()} is the projection the clean-up asks for. */
    List<DocumentationBuildEntity> findByStateOrderByIdDesc(BuildState state);

    @Query("select b.id from DocumentationBuildEntity b where b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING")
    List<Long> findRunningIds();

    /**
     * The parts owing a build because one of theirs never finished. Distinct, because an instance that died
     * twice leaves two rows for one part and the caller wants the part once.
     */
    @Query("select distinct new ch.admin.bit.jeap.doc.domain.PartKey(b.site, b.part) "
           + "from DocumentationBuildEntity b "
           + "where b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING")
    List<ch.admin.bit.jeap.doc.domain.PartKey> findPartsWithRunningBuilds();

    /** What is about to be abandoned, read before the update so that the caller learns what it gave up on. */
    List<DocumentationBuildEntity> findBySiteAndPartAndState(String site, String part, BuildState state);

    /**
     * Gives up on the builds of a part that are still marked as running. The caller holds that part's lock, so
     * their lease has expired and whatever is still writing has lost its claim.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DocumentationBuildEntity b
               set b.state = ch.admin.bit.jeap.doc.domain.BuildState.ABANDONED,
                   b.finishedAt = :finishedAt,
                   b.failureReason = 'The instance running this build stopped, and its lock has since expired.'
             where b.site = :site and b.part = :part
               and b.state = ch.admin.bit.jeap.doc.domain.BuildState.RUNNING
            """)
    int abandonRunning(@Param("site") String site, @Param("part") String part,
                       @Param("finishedAt") Instant finishedAt);

    /**
     * Forgets where a build's site was, once its objects have been removed. Without it the retention offers the
     * same prefixes again on every build, and each one costs a listing of a prefix that is already empty.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentationBuildEntity b set b.objectPrefix = null where b.objectPrefix = :objectPrefix")
    int forgetObjectPrefix(@Param("objectPrefix") String objectPrefix);

    /**
     * Deletes the finished builds older than the given instant, except the newest succeeded one of each part -
     * the row that is that part's publication.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from DocumentationBuildEntity b
            where b.finishedAt is not null
              and b.finishedAt < :finishedBefore
              and (b.state <> ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED
                   or exists (select 1 from DocumentationBuildEntity newer
                              where newer.site = b.site and newer.part = b.part
                                and newer.state = ch.admin.bit.jeap.doc.domain.BuildState.SUCCEEDED
                                and newer.id > b.id))
            """)
    int deleteFinishedBefore(@Param("finishedBefore") Instant finishedBefore);

    /**
     * Removes every build of one part. Written out rather than derived, because a derived {@code deleteBy...}
     * selects each row before deleting it.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentationBuildEntity b where b.site = :site and b.part = :part")
    int deletePart(@Param("site") String site, @Param("part") String part);
}
