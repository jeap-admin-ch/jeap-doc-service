package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The files of the documentation sets.
 */
interface CustomPageJpaRepository extends JpaRepository<CustomPageEntity, CustomPageEntity.Key> {

    /**
     * The files of the given sets.
     * <p>
     * <b>One array parameter, not one parameter per identifier</b> - a derived {@code …In} query binds every
     * element separately, and the parameter count would then be the number of sets a system has. See
     * {@code ArchitectureSystemAliasJpaRepository}, where the same rule is explained at length.
     */
    @Query(value = "select * from custom_page where set_id = any(:setIds) order by chapter, file_name",
            nativeQuery = true)
    List<CustomPageEntity> findBySetIdIn(@Param("setIds") Long[] setIds);

    /** Takes the files of a set off, so the upload that replaced it can write its own. */
    @Modifying
    @Query("delete from CustomPageEntity p where p.setId = :setId")
    int deleteBySetId(@Param("setId") Long setId);
}
