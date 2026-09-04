package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One upload of documentation, with everything the generator needs to pick it up: what it documents, where its
 * bundle lies and how far it got.
 * <p>
 * {@code id} comes from a sequence and is what the bundle is stored under; {@code uploadId} is the identifier
 * the client chose and is unique, which is what makes a retry a retry instead of a second upload.
 */
@Entity
@Table(name = "documentation_upload")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class DocumentationUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "documentation_upload_seq")
    // Allocation size 1, matching the "increment by 1" of the sequence in the migration - Hibernate's default of
    // 50 would hand out identifiers in blocks, and the identifier of an upload is part of the key its bundle is
    // stored under.
    @SequenceGenerator(name = "documentation_upload_seq", sequenceName = "documentation_upload_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID uploadId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private DocumentationSubjectEntity subject;

    @Column(nullable = false)
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceFormat sourceFormat;

    private String location;
    private String topic;
    private String label;
    private String version;

    @Column(nullable = false)
    private String sourceRepository;

    @Column(nullable = false)
    private String sourceRevision;

    @Column(nullable = false)
    private String sourceRef;

    @Column(nullable = false)
    private Instant sourceTimestamp;

    private String buildUrl;
    private Instant generatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadState state;

    private String objectKey;

    private String bundleSha256;

    @Column(nullable = false)
    private long sizeInBytes;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant completedAt;
    private String failureReason;
}
