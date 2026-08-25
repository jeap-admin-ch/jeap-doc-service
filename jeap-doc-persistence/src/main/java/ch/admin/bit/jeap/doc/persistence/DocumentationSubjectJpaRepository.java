package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.SubjectKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface DocumentationSubjectJpaRepository extends JpaRepository<DocumentationSubjectEntity, Long> {

    /**
     * Looks the subject up the way the unique index of its identity is built - a missing name compared as an
     * empty one - so the index covers the whole condition.
     */
    @Query("""
            select s from DocumentationSubjectEntity s
             where s.site = :site and s.kind = :kind and s.system = :system
               and coalesce(s.name, '') = coalesce(:name, '')
            """)
    Optional<DocumentationSubjectEntity> find(@Param("site") String site, @Param("kind") SubjectKind kind,
                                              @Param("system") String system, @Param("name") String name);

    /**
     * Inserts the subject unless it is already there, in one statement, so two uploads naming the same subject
     * at the same moment cannot create it twice. The conflict is inferred from the unique index of the identity,
     * which treats a missing name as an empty one - in PostgreSQL two null names would not conflict.
     */
    @Modifying
    @Query(value = """
            insert into documentation_subject (id, site, kind, system_name, name, created_at)
            values (nextval('documentation_subject_id_seq'), :site, :kind, :system, :name, :createdAt)
            on conflict (site, kind, system_name, coalesce(name, '')) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("site") String site, @Param("kind") String kind, @Param("system") String system,
                        @Param("name") String name, @Param("createdAt") Instant createdAt);
}
