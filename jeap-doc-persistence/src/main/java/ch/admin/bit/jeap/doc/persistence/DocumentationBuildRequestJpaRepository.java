package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface DocumentationBuildRequestJpaRepository
        extends JpaRepository<DocumentationBuildRequestEntity, DocumentationBuildRequestEntity.RequestId> {

    List<DocumentationBuildRequestEntity> findAllByOrderByRequestedAtAsc();

    /**
     * Asks for a build of one part, and does nothing at all when one is already pending - <b>in one
     * statement</b>, which is what makes several triggers one request even when they arrive at the same moment
     * on different instances.
     * <p>
     * The alternative, reading first and inserting when nothing is there, loses that race: the loser's insert
     * violates the primary key, and a PostgreSQL transaction that has seen an error cannot be committed - so the
     * exception cannot be caught and turned into "somebody else asked first" without giving up the transaction.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "insert into documentation_build_request (site, part, requested_at, trigger_kind, "
                   + "                                        publication_id, publication_requested_at, forced) "
                   + "values (:site, :part, :requestedAt, :trigger, :publicationId, :publicationRequestedAt, "
                   + "        :forced) "
                   + "on conflict (site, part) do nothing",
            nativeQuery = true)
    int requestIfAbsent(@Param("site") String site, @Param("part") String part,
                        @Param("requestedAt") Instant requestedAt, @Param("trigger") String trigger,
                        @Param("publicationId") String publicationId,
                        @Param("publicationRequestedAt") Instant publicationRequestedAt,
                        @Param("forced") boolean forced);

    /**
     * Raises the forced flag on a request that is already pending, and reports whether it was this call that
     * raised it.
     * <p>
     * <b>What makes a forced publication reach a part that was already owed a build.</b> Two asks for one part
     * are one row - the primary key says so - so an ask that may not be skipped cannot write a row of its own
     * and must raise the flag on the one it finds. Idempotent, and one statement: two operators forcing at the
     * same moment is one forced request.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentationBuildRequestEntity r set r.forced = true "
           + "where r.id.site = :site and r.id.part = :part and r.forced = false")
    int force(@Param("site") String site, @Param("part") String part);

    /**
     * Puts a request that is already pending into a publication, where it belongs to none - and reports
     * whether it was this call that did it.
     * <p>
     * <b>Otherwise the publication reads as finished while one of its parts is still owed a build.</b> A part
     * already owed one when a publication is asked for is built by that publication's pass and is one of its
     * parts; leaving its row with a null publication takes it out of the two {@code not exists} clauses that
     * decide whether the publication is over, because neither can join a row that carries no identifier. The
     * wall clock then stops before the last part finished - which is the one number the publication gauges
     * exist to report.
     * <p>
     * Only where there is none. A request that already belongs to a publication stays in it: the first ask is
     * the one whose wait is being measured.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentationBuildRequestEntity r set r.publicationId = :publicationId, "
           + "    r.publicationRequestedAt = :publicationRequestedAt "
           + "where r.id.site = :site and r.id.part = :part and r.publicationId is null")
    int adoptIntoPublication(@Param("site") String site, @Param("part") String part,
                             @Param("publicationId") String publicationId,
                             @Param("publicationRequestedAt") Instant publicationRequestedAt);

    /**
     * Clears the pending request of one part in one statement, so that of two instances reaching this at the
     * same moment exactly one learns that there was something to do.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentationBuildRequestEntity r where r.id.site = :site and r.id.part = :part")
    int clear(@Param("site") String site, @Param("part") String part);

    /** The oldest request of a site, whichever part it is for - what the age gauge reads. */
    @Query("select min(r.requestedAt) from DocumentationBuildRequestEntity r where r.id.site = :site")
    Optional<Instant> oldestRequestOf(@Param("site") String site);

    @Query("select count(r) from DocumentationBuildRequestEntity r where r.id.site = :site")
    int countOfSite(@Param("site") String site);
}
