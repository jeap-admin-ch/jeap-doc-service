package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The deployment state of the systems and components documentation was uploaded for.
 * <p>
 * Every statement compares a missing component as an empty one, the way the unique index folds it - two nulls
 * would otherwise never match. The component is cast because a null parameter of a native statement carries no
 * type PostgreSQL could infer.
 */
interface CustomSubjectDeploymentJpaRepository extends JpaRepository<CustomSubjectDeploymentEntity, Long> {

    List<CustomSubjectDeploymentEntity> findBySiteOrderBySystemNameAscComponentAsc(String site);

    /**
     * Records an upload for a system or component: a new or undeployed one is awaiting deployment, and one
     * awaiting deployment or deployed stays as it is. In one statement, so that it takes the row's lock and
     * waits for a removal that is deciding about the same row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into custom_subject_deployment (id, site, system_name, component, state, changed_at)
            values (nextval('custom_subject_deployment_id_seq'), :site, :system, cast(:component as varchar),
                    'AWAITING_DEPLOYMENT', :at)
            on conflict (site, system_name, coalesce(component, ''))
            do update set state = 'AWAITING_DEPLOYMENT',
                          changed_at = excluded.changed_at
            where custom_subject_deployment.state = 'UNDEPLOYED'
            """, nativeQuery = true)
    void recordUpload(@Param("site") String site, @Param("system") String system,
                      @Param("component") String component, @Param("at") Instant at);

    /** Marks a system or component awaiting deployment or undeployed system or component as deployed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update custom_subject_deployment
            set state = 'DEPLOYED', changed_at = :at
            where site = :site and system_name = :system
              and coalesce(component, '') = coalesce(cast(:component as varchar), '')
              and state <> 'DEPLOYED'
            """, nativeQuery = true)
    int markDeployed(@Param("site") String site, @Param("system") String system,
                     @Param("component") String component, @Param("at") Instant at);

    /** The state of a system or component, locked until the transaction ends. */
    @Query(value = """
            select state from custom_subject_deployment
            where site = :site and system_name = :system
              and coalesce(component, '') = coalesce(cast(:component as varchar), '')
            for update
            """, nativeQuery = true)
    Optional<String> lockState(@Param("site") String site, @Param("system") String system,
                               @Param("component") String component);

    /**
     * Locks the rows of a system and of all its components until the transaction ends - the system's first,
     * the order an upload takes them in - and answers their states.
     */
    @Query(value = """
            select state from custom_subject_deployment
            where site = :site and system_name = :system
            order by coalesce(component, '')
            for update
            """, nativeQuery = true)
    List<String> lockSystem(@Param("site") String site, @Param("system") String system);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update custom_subject_deployment
            set state = 'UNDEPLOYED', changed_at = :at
            where site = :site and system_name = :system and component = :component
            """, nativeQuery = true)
    void undeployComponent(@Param("site") String site, @Param("system") String system,
                           @Param("component") String component, @Param("at") Instant at);

    /** The system's own state and those of all its components: everything documented for it is gone. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update custom_subject_deployment
            set state = 'UNDEPLOYED', changed_at = :at
            where site = :site and system_name = :system
            """, nativeQuery = true)
    void undeploySystem(@Param("site") String site, @Param("system") String system, @Param("at") Instant at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            delete from custom_subject_deployment
            where site = :site and system_name = :system and component = :component
            """, nativeQuery = true)
    void deleteComponent(@Param("site") String site, @Param("system") String system,
                         @Param("component") String component);

    /** The system's own state and those of all its components. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from custom_subject_deployment where site = :site and system_name = :system",
            nativeQuery = true)
    void deleteSystem(@Param("site") String site, @Param("system") String system);
}
