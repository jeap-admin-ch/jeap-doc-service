package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One documentation set a team uploaded, as it is currently published.
 */
@Entity
@Table(name = "custom_set")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class CustomSetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "custom_set_seq")
    // Allocation size 1, matching the "increment by 1" of the sequence in the migration.
    @SequenceGenerator(name = "custom_set_seq", sequenceName = "custom_set_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String site;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubjectKind kind;

    @Column(name = "system_name", nullable = false)
    private String systemName;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_format", nullable = false)
    private SourceFormat sourceFormat;

    @Column(nullable = false)
    private String template;

    private String location;

    private String topic;

    @Column(nullable = false)
    private long revision;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "source_repository", nullable = false)
    private String sourceRepository;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "source_revision", nullable = false)
    private String sourceRevision;

    @Column(name = "source_timestamp", nullable = false)
    private Instant sourceTimestamp;

    private String version;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
