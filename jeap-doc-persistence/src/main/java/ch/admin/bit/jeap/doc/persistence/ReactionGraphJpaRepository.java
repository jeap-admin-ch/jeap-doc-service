package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** Storage of complete snapshots; the legacy address columns are always empty. */
interface ReactionGraphJpaRepository extends JpaRepository<ReactionGraphEntity, Long> {
    Optional<ReactionGraphRefView> findByEnvironmentAndKindAndNameAndSystemNameAndVariant(
            String environment, String kind, String name, String systemName, String variant);

    /** One graph, to decide whether a fetched one is an insert or a replacement of what is stored. */
    @Query("""
            select g from ReactionGraphEntity g
            where g.environment = :environment and g.kind = :kind
              and lower(g.name) = lower(:name) and lower(g.systemName) = lower(:system)
              and g.variant = :variant""")
    Optional<ReactionGraphEntity> findOne(@Param("environment") String environment,
                                          @Param("kind") String kind, @Param("name") String name,
                                          @Param("system") String system,
                                          @Param("variant") String variant);

    long countByEnvironmentAndKind(String environment, String kind);

    @Modifying
    @Query("""
            update ReactionGraphEntity g set g.checkedAt = :checkedAt
            where g.environment = :environment and g.kind = :kind
              and lower(g.name) = lower(:name) and lower(g.systemName) = lower(:system)
              and g.variant = :variant""")
    int confirm(@Param("environment") String environment, @Param("kind") String kind,
                @Param("name") String name, @Param("system") String system,
                @Param("variant") String variant, @Param("checkedAt") Instant checkedAt);

}
