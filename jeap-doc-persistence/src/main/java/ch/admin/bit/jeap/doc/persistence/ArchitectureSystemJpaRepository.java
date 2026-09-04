package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The systems of one environment. Everything below them cascades from here.
 */
interface ArchitectureSystemJpaRepository extends JpaRepository<ArchitectureSystemEntity, Long> {


    List<ArchitectureSystemEntity> findByEnvironmentOrderBySlug(String environment);

    /**
     * Removes the systems of one environment, in <b>one</b> statement.
     * <p>
     * Written out rather than derived: {@code @Modifying} is only honoured on a {@code @Query}, so a derived
     * {@code deleteBy...} selects every row and calls {@code remove} on each - which fires the cascade of
     * every child table once per system, on every import of every environment. The children go with it through
     * the {@code on delete cascade} of the schema either way.
     */
    @Modifying
    @Query("delete from ArchitectureSystemEntity s where s.environment = :environment")
    int deleteByEnvironment(@Param("environment") String environment);
}
