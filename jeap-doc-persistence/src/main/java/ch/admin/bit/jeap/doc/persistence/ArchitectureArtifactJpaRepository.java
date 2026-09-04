package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The replicated OpenAPI specifications and database schemas.
 * <p>
 * <b>Every query that addresses one artifact folds the two names, and folds them the way the unique index
 * does.</b> The model and these rows carry the spellings of two different exports of the same upstream - the
 * orphan sweep below already joins them folded - so a case-sensitive comparison would answer nothing where
 * they differ, and a lookup that folded differently from the index would decide to insert where the index
 * refuses, failing the whole import. The queries are written out rather than derived because Spring Data
 * renders {@code IgnoreCase} as {@code upper(...)}, which no index on {@code lower(...)} can serve.
 */
interface ArchitectureArtifactJpaRepository extends JpaRepository<ArchitectureArtifactEntity, Long> {


    List<ArchitectureArtifactRefView> findByEnvironmentAndKindOrderBySystemNameAscComponentNameAsc(String environment,
                                                                                       String kind);

    /** One artifact, to decide whether a fetched one is an insert or a replacement of what is stored. */
    @Query("""
            select a from ArchitectureArtifactEntity a
            where a.environment = :environment and a.kind = :kind
              and lower(a.systemName) = lower(:systemName)
              and lower(a.componentName) = lower(:componentName)""")
    Optional<ArchitectureArtifactEntity> findOne(@Param("environment") String environment,
                                                 @Param("kind") String kind,
                                                 @Param("systemName") String systemName,
                                                 @Param("componentName") String componentName);

    /** Every artifact of one system, for the generation run that documents it. */
    @Query("""
            select a from ArchitectureArtifactEntity a
            where a.environment = :environment and a.kind = :kind
              and lower(a.systemName) = lower(:systemName)
            order by a.componentName asc""")
    List<ArchitectureArtifactEntity> findAllOfSystem(@Param("environment") String environment,
                                                     @Param("kind") String kind,
                                                     @Param("systemName") String systemName);

    long countByEnvironmentAndKind(String environment, String kind);

    @Modifying
    @Query("""
            update ArchitectureArtifactEntity a set a.checkedAt = :checkedAt
            where a.environment = :environment and a.kind = :kind
              and lower(a.systemName) = lower(:systemName)
              and lower(a.componentName) = lower(:componentName)""")
    int confirm(@Param("environment") String environment, @Param("kind") String kind,
                @Param("systemName") String systemName, @Param("componentName") String componentName,
                @Param("checkedAt") Instant checkedAt);

    /**
     * Removes one artifact, in one statement.
     * <p>
     * One statement per artifact rather than an {@code in} over the two names joined into a key: a system may
     * be called {@code Order Fulfilment}, so any separator that can occur in a name makes the key ambiguous -
     * a system {@code a/b} with a component {@code c} and a system {@code a} with a component {@code b/c}
     * produce the same key, and one prune then deletes the neighbour's artifact. A prune removes nothing on
     * almost every run and a handful on the rest, so the loop costs nothing worth buying an ambiguity for.
     */
    @Modifying
    @Query("""
            delete from ArchitectureArtifactEntity a
            where a.environment = :environment and a.kind = :kind
              and lower(a.systemName) = lower(:systemName)
              and lower(a.componentName) = lower(:componentName)""")
    int removeOne(@Param("environment") String environment, @Param("kind") String kind,
                  @Param("systemName") String systemName, @Param("componentName") String componentName);

    /**
     * Artifacts naming a system or a component the stored model of this environment does not have. Called
     * at the end of a model import, in its transaction.
     */
    @Modifying
    @Query("""
            delete from ArchitectureArtifactEntity a
            where a.environment = :environment
              and not exists (
                select 1 from ArchitectureComponentEntity c, ArchitectureSystemEntity s
                where c.systemId = s.id
                  and s.environment = a.environment
                  and lower(s.name) = lower(a.systemName)
                  and lower(c.name) = lower(a.componentName))""")
    int removeOrphans(@Param("environment") String environment);
}
