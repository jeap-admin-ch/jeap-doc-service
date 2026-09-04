package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * What the last import of one environment and kind did.
 */
@Entity
@Table(name = "architecture_import")
@IdClass(ArchitectureImportEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureImportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String environment;

    @Id
    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "index_etag")
    private String indexEtag;

    @Column(nullable = false)
    private boolean complete;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    /**
     * What the last run did, as the name of an {@code ImportOutcome}. A string rather than an enum mapping, for
     * the same reason the kind is one: a value written by a newer version has to read as something rather than
     * fail the whole row.
     */
    @Column(name = "last_outcome")
    private String lastOutcome;

    @Column(name = "failure_reason")
    private String failureReason;

    /** The composite key, as {@code @IdClass} needs it. */
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    static class Key implements Serializable {

        private String environment;

        private String kind;
    }
}
