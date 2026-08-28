package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The pending request to publish one site. The site is the primary key, which is what makes "at most one request
 * per site" a property of the schema rather than of the code that writes it.
 */
@Entity
@Table(name = "documentation_build_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class DocumentationBuildRequestEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String site;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false)
    private BuildTrigger trigger;
}
