package ch.admin.bit.jeap.doc.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One replicated OpenAPI specification or database schema.
 * <p>
 * The system and the component are named rather than referenced. The model is replaced wholesale on every
 * import, and a foreign key into it would either take the blob down with it or stop the model from being
 * replaceable at all.
 */
@Entity
@Table(name = "architecture_artifact")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_artifact_seq")
    @SequenceGenerator(name = "architecture_artifact_seq", sequenceName = "architecture_artifact_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String environment;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "system_name", nullable = false, updatable = false)
    private String systemName;

    @Column(name = "component_name", nullable = false, updatable = false)
    private String componentName;

    @Column
    private String version;

    @Column(nullable = false)
    private String etag;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "modified_at")
    private Instant modifiedAt;

    @Column(name = "replicated_at", nullable = false)
    private Instant replicatedAt;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
