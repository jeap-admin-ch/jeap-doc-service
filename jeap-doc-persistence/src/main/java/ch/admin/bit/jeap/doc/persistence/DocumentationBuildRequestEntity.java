package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The pending request to publish one part of one site. The pair is the primary key, which is what makes "at
 * most one request per part" a property of the schema rather than of the code that writes it.
 */
@Entity
@Table(name = "documentation_build_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class DocumentationBuildRequestEntity {

    @EmbeddedId
    private RequestId id;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false)
    private BuildTrigger trigger;

    /** The full publication this request is part of, or null where it is not part of one. */
    @Column(name = "publication_id")
    private String publicationId;

    @Column(name = "publication_requested_at")
    private Instant publicationRequestedAt;

    /**
     * Whether the build this leads to may be skipped by its content digest. Its own column rather than a
     * reading of {@code trigger_kind}, which records who asked <b>first</b>: an ask that may not be skipped
     * raises this on the request it finds pending.
     */
    @Column(name = "forced", nullable = false)
    private boolean forced;

    /** Which part of which site the request is for, as the primary key of the table. */
    @Embeddable
    record RequestId(
            @Column(nullable = false, updatable = false) String site,
            @Column(nullable = false, updatable = false) String part) {
    }
}
