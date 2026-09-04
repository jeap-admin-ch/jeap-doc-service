package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The teams of one environment, replaced with the landscape that names them.
 */
interface ArchitectureTeamJpaRepository extends JpaRepository<ArchitectureTeamEntity, Long> {


    List<ArchitectureTeamEntity> findByEnvironment(String environment);

    /**
     * Removes the teams of one environment, in <b>one</b> statement - see
     * {@link ArchitectureSystemJpaRepository#deleteByEnvironment}. A system and a component each reference a
     * team without a cascade, so the database checks per deleted team that nothing points at it any more;
     * doing that once per row rather than once was the second half of the same cost.
     */
    @Modifying
    @Query("delete from ArchitectureTeamEntity t where t.environment = :environment")
    int deleteByEnvironment(@Param("environment") String environment);
}
