package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The resources read from the Governance Service's {@code /docs-api}.
 * <p>
 * <b>Every query that addresses one resource folds the key, and folds it the way the unique index does</b> -
 * {@code coalesce(resourceKey, '')}, so that the two index rows (whose key is null) are found by the same
 * comparison as a system or a rule. The queries are written out rather than derived because Spring Data renders
 * {@code IgnoreCase} as {@code upper(...)}, which no index on {@code lower(...)} can serve.
 */
interface GovernanceResourceJpaRepository extends JpaRepository<GovernanceResourceEntity, Long> {

    List<GovernanceResourceRefView> findByKindOrderByResourceKeyAsc(String kind);

    @Query("""
            select g from GovernanceResourceEntity g
            where g.kind = :kind and lower(coalesce(g.resourceKey, '')) = lower(coalesce(:key, ''))""")
    Optional<GovernanceResourceEntity> findOne(@Param("kind") String kind, @Param("key") String key);

    @Modifying
    @Query("""
            update GovernanceResourceEntity g set g.checkedAt = :checkedAt
            where g.kind = :kind and lower(coalesce(g.resourceKey, '')) = lower(coalesce(:key, ''))""")
    int confirm(@Param("kind") String kind, @Param("key") String key, @Param("checkedAt") Instant checkedAt);

    /**
     * Removes one resource, in one statement - see {@code ArchitectureArtifactJpaRepository#removeOne} for why
     * this is not a batch delete over the keys joined into one {@code in}.
     */
    @Modifying
    @Query("""
            delete from GovernanceResourceEntity g
            where g.kind = :kind and lower(coalesce(g.resourceKey, '')) = lower(coalesce(:key, ''))""")
    int removeOne(@Param("kind") String kind, @Param("key") String key);
}
