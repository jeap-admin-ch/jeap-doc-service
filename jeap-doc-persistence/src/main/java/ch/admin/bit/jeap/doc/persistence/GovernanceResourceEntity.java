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
 * One resource read from the Governance Service's {@code /docs-api}: a systems or rules index, or one system
 * or rule resource one of those indexes named.
 */
@Entity
@Table(name = "governance_resource")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class GovernanceResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "governance_resource_seq")
    @SequenceGenerator(name = "governance_resource_seq", sequenceName = "governance_resource_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(name = "resource_key", updatable = false)
    private String resourceKey;

    @Column(nullable = false)
    private String etag;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
