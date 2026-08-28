package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

interface DocumentationBuildRequestJpaRepository extends JpaRepository<DocumentationBuildRequestEntity, String> {

    List<DocumentationBuildRequestEntity> findAllByOrderByRequestedAtAsc();

    /**
     * Asks for a build, and does nothing at all when one is already pending - <b>in one statement</b>, which is
     * what makes several triggers one request even when they arrive at the same moment on different instances.
     * <p>
     * The alternative, reading first and inserting when nothing is there, loses that race: the loser's insert
     * violates the primary key, and a PostgreSQL transaction that has seen an error cannot be committed - so the
     * exception cannot be caught and turned into "somebody else asked first" without giving up the transaction.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "insert into documentation_build_request (site, requested_at, trigger_kind) "
                   + "values (:site, :requestedAt, :trigger) on conflict (site) do nothing", nativeQuery = true)
    int requestIfAbsent(@Param("site") String site, @Param("requestedAt") Instant requestedAt,
                        @Param("trigger") String trigger);

    /**
     * Clears the pending request of a site in one statement, so that of two instances reaching this at the same
     * moment exactly one learns that there was something to do.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentationBuildRequestEntity r where r.site = :site")
    int clear(@Param("site") String site);
}
