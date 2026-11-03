package ch.admin.bit.jeap.doc.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * What the last governance import of one kind (SYSTEM or RULE) did.
 */
@Entity
@Table(name = "governance_import")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class GovernanceImportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String kind;

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
     * What the last run did, as the name of a {@code GovernanceImportOutcome}. A string rather than an enum
     * mapping, so that a value written by a newer version reads as something rather than fail the whole row.
     */
    @Column(name = "last_outcome")
    private String lastOutcome;

    @Column(name = "failure_reason")
    private String failureReason;
}
