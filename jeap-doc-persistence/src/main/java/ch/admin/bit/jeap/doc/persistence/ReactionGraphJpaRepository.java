package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The replicated reaction graphs.
 * <p>
 * <b>Every query that addresses one graph folds the name and the system the way the unique index does</b>, and
 * leaves the variant alone for the same reason the index does: those are the model's spellings and two exports
 * of one upstream may differ in case, while a variant is the observer's string verbatim. A lookup that folded
 * differently from the index would decide to insert where the index refuses, failing the import. The queries
 * are written out rather than derived because Spring Data renders {@code IgnoreCase} as {@code upper(...)},
 * which no index on {@code lower(...)} can serve.
 */
interface ReactionGraphJpaRepository extends JpaRepository<ReactionGraphEntity, Long> {

    List<ReactionGraphRefView> findByEnvironmentAndKindOrderByNameAscVariantAsc(String environment,
                                                                               String kind);

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

    /** Every variant of one message type, folded on the name like every other lookup here. */
    @Query("""
            select g from ReactionGraphEntity g
            where g.environment = :environment and g.kind = :kind
              and lower(g.name) = lower(:name)
            order by g.variant""")
    List<ReactionGraphEntity> findVariants(@Param("environment") String environment,
                                           @Param("kind") String kind, @Param("name") String name);

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

    /**
     * Removes one graph, in one statement.
     * <p>
     * One statement per graph rather than an {@code in} over the name and the variant joined into a key: the
     * separator can occur in a message type's own name, so a key would make two different graphs one and a
     * prune would take the neighbour with it. A prune removes nothing on almost every run and a handful on the
     * rest, so the loop costs nothing worth buying an ambiguity for.
     */
    @Modifying
    @Query("""
            delete from ReactionGraphEntity g
            where g.environment = :environment and g.kind = :kind
              and lower(g.name) = lower(:name) and lower(g.systemName) = lower(:system)
              and g.variant = :variant""")
    int removeOne(@Param("environment") String environment, @Param("kind") String kind,
                  @Param("name") String name, @Param("system") String system,
                  @Param("variant") String variant);
}
