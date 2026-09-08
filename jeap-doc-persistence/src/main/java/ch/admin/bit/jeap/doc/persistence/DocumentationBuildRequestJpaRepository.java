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
     * Asks for a build of one part, in <b>one statement</b>: it writes the request, or merges what this ask
     * adds into the one that is already pending, and answers whether it was this call that created the row.
     * <p>
     * One statement because three lost an ask. A read-then-insert loses the race outright - the loser's insert
     * violates the primary key, and a PostgreSQL transaction that has seen an error cannot be committed. An
     * insert followed by two updates loses it more quietly: another instance claiming the row in between
     * leaves both updates matching nothing, so a forced publication is skipped by digest and a publication
     * reads as over without that part.
     * <p>
     * A request that is joined keeps its instant and its trigger, so the age of a request stays the age of the
     * oldest unserved ask. What the ask does add is the two things that are about the build rather than about
     * who asked for it: it may no longer be skipped, and it belongs to a publication.
     * <p>
     * {@code xmax = 0} is what says the row is new - PostgreSQL leaves it at zero for a row this statement
     * inserted, and sets it on one it updated.
     */
    @Query(value = "insert into documentation_build_request (site, part, requested_at, trigger_kind, "
                   + "                                        publication_id, publication_requested_at, forced) "
                   + "values (:site, :part, :requestedAt, :trigger, :publicationId, :publicationRequestedAt, "
                   + "        :forced) "
                   + "on conflict (site, part) do update "
                   + "   set forced = documentation_build_request.forced or excluded.forced, "
                   + "       publication_id = coalesce(documentation_build_request.publication_id, "
                   + "                                 excluded.publication_id), "
                   + "       publication_requested_at = coalesce("
                   + "               documentation_build_request.publication_requested_at, "
                   + "               excluded.publication_requested_at) "
                   + "returning (xmax = 0)",
            nativeQuery = true)
    boolean requestOrJoin(@Param("site") String site, @Param("part") String part,
                          @Param("requestedAt") Instant requestedAt, @Param("trigger") String trigger,
                          @Param("publicationId") String publicationId,
                          @Param("publicationRequestedAt") Instant publicationRequestedAt,
                          @Param("forced") boolean forced);

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
