package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface CustomSetJpaRepository extends JpaRepository<CustomSetEntity, Long> {

    /**
     * The set under one key. The three nullable parts are compared as empty ones, the way the unique index
     * folds them - two nulls would otherwise never match.
     */
    @Query("""
            select s from CustomSetEntity s
            where s.site = :site and s.kind = :kind and s.systemName = :system
              and coalesce(s.name, '') = coalesce(:name, '')
              and s.sourceFormat = :sourceFormat and s.template = :template
              and coalesce(s.location, '') = coalesce(:location, '')
              and coalesce(s.topic, '') = coalesce(:topic, '')
            """)
    Optional<CustomSetEntity> find(@Param("site") String site, @Param("kind") SubjectKind kind,
                                   @Param("system") String system, @Param("name") String name,
                                   @Param("sourceFormat") SourceFormat sourceFormat,
                                   @Param("template") String template, @Param("location") String location,
                                   @Param("topic") String topic);

    /**
     * Everything documented for one system of one site - what a build of that system's part reads.
     * <p>
     * Ordered, so that a build of one landscape generates the same site twice. Without it the rows come back
     * in whatever order the heap holds them, which changes on every re-upload.
     */
    List<CustomSetEntity> findBySiteAndSystemNameOrderByIdAsc(String site, String systemName);

    /** Every set of one subject, whatever its format or template. */
    @Query("""
            select s from CustomSetEntity s
            where s.site = :site and s.kind = :kind and s.systemName = :system
              and coalesce(s.name, '') = coalesce(:name, '')
            """)
    List<CustomSetEntity> findSubject(@Param("site") String site, @Param("kind") SubjectKind kind,
                                      @Param("system") String system, @Param("name") String name);

    /**
     * The same row, locked, so that what is read of it is still true when it is written.
     * <p>
     * Taken before a set is replaced: the object key it names is about to be overwritten, and it is the last
     * moment the object it stood for can still be found. A row that is not there takes no lock, which is the
     * one case the upsert below has to resolve on its own.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from CustomSetEntity s
            where s.site = :site and s.kind = :kind and s.systemName = :system
              and coalesce(s.name, '') = coalesce(:name, '')
              and s.sourceFormat = :sourceFormat and s.template = :template
              and coalesce(s.location, '') = coalesce(:location, '')
              and coalesce(s.topic, '') = coalesce(:topic, '')
            """)
    Optional<CustomSetEntity> findForUpdate(@Param("site") String site, @Param("kind") SubjectKind kind,
                                            @Param("system") String system, @Param("name") String name,
                                            @Param("sourceFormat") SourceFormat sourceFormat,
                                            @Param("template") String template,
                                            @Param("location") String location, @Param("topic") String topic);

    /**
     * Every subject a site holds documentation of, as four columns rather than as rows.
     * <p>
     * It is asked while a request is served - the site partition adds these to the systems of the architecture
     * model - so it stays a projection: the provenance, the digests and the sizes of every set of a site have
     * no business being read to answer which subjects there are.
     */
    @Query("""
            select distinct new ch.admin.bit.jeap.doc.domain.custom.CustomSubject(
                    s.site, s.kind, s.systemName, s.name)
            from CustomSetEntity s
            where s.site = :site
            """)
    List<CustomSubject> subjectsOf(@Param("site") String site);

    /** Everything documented for one system: its own sets, and those of its components and libraries. */
    List<CustomSetEntity> findBySiteAndSystemName(String site, String systemName);

    @Query("select s.objectKey from CustomSetEntity s")
    List<String> objectKeys();

    /**
     * Writes the set under its key in one statement, and answers nothing.
     * <p>
     * <b>One statement, because the row is also the lock.</b> A read followed by a write would let two uploads
     * of one set interleave: both would delete the pages of the set and both would insert their own, and the
     * set would end up carrying the pages of two uploads under one revision. {@code on conflict do update}
     * takes the row's lock, so the upload that arrives second blocks here - before it deletes anything - and
     * its delete then sees the rows the first one wrote.
     * <p>
     * The conflict is inferred from the unique index of the identity, which compares the three nullable parts
     * as empty ones: in PostgreSQL two nulls would not conflict.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into custom_set (id, site, kind, system_name, name, source_format, template, location,
                                    topic, revision, object_key, sha256, size_in_bytes, source_repository,
                                    source_ref, source_revision, source_timestamp, version, uploaded_at)
            values (nextval('custom_set_id_seq'), :site, :kind, :system, :name, :sourceFormat, :template,
                    :location, :topic, :revision, :objectKey, :sha256, :sizeInBytes, :sourceRepository,
                    :sourceRef, :sourceRevision, :sourceTimestamp, :version, :uploadedAt)
            on conflict (site, kind, system_name, coalesce(name, ''), source_format, template,
                         coalesce(location, ''), coalesce(topic, ''))
            do update set revision = excluded.revision,
                          object_key = excluded.object_key,
                          sha256 = excluded.sha256,
                          size_in_bytes = excluded.size_in_bytes,
                          source_repository = excluded.source_repository,
                          source_ref = excluded.source_ref,
                          source_revision = excluded.source_revision,
                          source_timestamp = excluded.source_timestamp,
                          version = excluded.version,
                          uploaded_at = excluded.uploaded_at
            """, nativeQuery = true)
    void upsert(@Param("site") String site, @Param("kind") String kind, @Param("system") String system,
                @Param("name") String name, @Param("sourceFormat") String sourceFormat,
                @Param("template") String template, @Param("location") String location,
                @Param("topic") String topic, @Param("revision") long revision,
                @Param("objectKey") String objectKey, @Param("sha256") String sha256,
                @Param("sizeInBytes") long sizeInBytes, @Param("sourceRepository") String sourceRepository,
                @Param("sourceRef") String sourceRef, @Param("sourceRevision") String sourceRevision,
                @Param("sourceTimestamp") Instant sourceTimestamp, @Param("version") String version,
                @Param("uploadedAt") Instant uploadedAt);
}
