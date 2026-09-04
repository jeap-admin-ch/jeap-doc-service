package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The versions of the messages.
 */
interface ArchitectureMessageVersionJpaRepository
        extends JpaRepository<ArchitectureMessageVersionEntity, ArchitectureMessageVersionEntity.Key> {

    /**
     * The rows of this table belonging to the given parents, in their stored order.
     * <p>
     * <b>One array parameter, not one parameter per identifier.</b> A derived {@code …In} query binds every
     * element of the collection separately, so the parameter count of this statement was the row count of the
     * table above it - and PostgreSQL's protocol allows 65535 of them. A landscape that grew past that would
     * not have got slower, it would have failed every build from then on. As an array it is one parameter and
     * one query plan whatever the size of the landscape.
     */
    @Query(value = "select * from architecture_message_version where message_id = any(:messageIds) order by ordinal",
            nativeQuery = true)
    List<ArchitectureMessageVersionEntity> findByMessageIdInOrderByOrdinal(@Param("messageIds") Long[] messageIds);
}
