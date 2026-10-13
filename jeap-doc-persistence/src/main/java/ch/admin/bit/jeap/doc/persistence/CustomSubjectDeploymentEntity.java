package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.custom.SubjectDeployment;
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

import java.time.Instant;

/**
 * Whether a system or component documentation was uploaded for has been seen deployed. Written only by the
 * statements of {@link CustomSubjectDeploymentJpaRepository}.
 */
@Entity
@Table(name = "custom_subject_deployment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class CustomSubjectDeploymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "custom_subject_deployment_seq")
    // Allocation size 1, matching the "increment by 1" of the sequence in the migration.
    @SequenceGenerator(name = "custom_subject_deployment_seq", sequenceName = "custom_subject_deployment_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String site;

    @Column(name = "system_name", nullable = false)
    private String systemName;

    /** The component, null for the system itself. */
    private String component;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubjectDeployment.State state;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    SubjectDeployment toDomain() {
        return new SubjectDeployment(site, systemName, component, state);
    }
}
