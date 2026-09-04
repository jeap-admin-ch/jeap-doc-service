package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The replicated schemas of the message type versions of one environment.
 * <p>
 * <b>Every query that addresses a version folds the two names, and folds them the way the unique index does.</b>
 * The model and these rows carry the spellings of two different exports of the same upstream, so a
 * case-sensitive comparison would answer nothing where they differ - and a lookup that folded differently from
 * the index would decide to insert where the index refuses, failing the whole import. The queries are written
 * out rather than derived because Spring Data renders {@code IgnoreCase} as {@code upper(...)}, which no index
 * on {@code lower(...)} can serve.
 */
interface ArchitectureMessageSchemaJpaRepository extends JpaRepository<ArchitectureMessageSchemaEntity, Long> {

    /** The stored versions of one environment, as a projection: never the renderings - see the view. */
    List<MessageVersionRefView> findByEnvironment(String environment);

    /** One version, to decide whether a fetched one is an insert or a replacement of what is stored. */
    @Query("""
            select s from ArchitectureMessageSchemaEntity s
            where s.environment = :environment
              and lower(s.systemName) = lower(:systemName)
              and lower(s.messageName) = lower(:messageName)
              and s.version = :version""")
    Optional<ArchitectureMessageSchemaEntity> findOne(@Param("environment") String environment,
                                                      @Param("systemName") String systemName,
                                                      @Param("messageName") String messageName,
                                                      @Param("version") String version);

    /** Every schema of one system, for the generation run that documents it. */
    @Query("""
            select s from ArchitectureMessageSchemaEntity s
            where s.environment = :environment and lower(s.systemName) = lower(:systemName)
            order by s.messageName asc, s.version asc""")
    List<ArchitectureMessageSchemaEntity> findAllOfSystem(@Param("environment") String environment,
                                                          @Param("systemName") String systemName);

    /** Records that a version was revalidated, without rewriting the row's renderings. */
    @Modifying
    @Query("""
            update ArchitectureMessageSchemaEntity s set s.checkedAt = :checkedAt
            where s.environment = :environment
              and lower(s.systemName) = lower(:systemName)
              and lower(s.messageName) = lower(:messageName)
              and s.version = :version""")
    int confirm(@Param("environment") String environment, @Param("systemName") String systemName,
                @Param("messageName") String messageName, @Param("version") String version,
                @Param("checkedAt") Instant checkedAt);

    /**
     * Removes one version, in one statement.
     * <p>
     * One statement per version rather than an {@code in} over the three names joined into a key: a system may
     * be called {@code Order Fulfilment}, so any separator that can occur in a name makes the key ambiguous and
     * one delete can take a neighbour with it. A prune removes nothing on almost every run and a handful on the
     * rest, so the loop costs nothing worth buying an ambiguity for.
     */
    @Modifying
    @Query("""
            delete from ArchitectureMessageSchemaEntity s
            where s.environment = :environment
              and lower(s.systemName) = lower(:systemName)
              and lower(s.messageName) = lower(:messageName)
              and s.version = :version""")
    int removeOne(@Param("environment") String environment, @Param("systemName") String systemName,
                  @Param("messageName") String messageName, @Param("version") String version);
}
