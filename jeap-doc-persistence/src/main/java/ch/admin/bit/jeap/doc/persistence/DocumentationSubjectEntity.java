package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.SubjectKind;
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
 * What an upload documents: a system, one of its components or one of its libraries, within one site.
 */
@Entity
@Table(name = "documentation_subject")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class DocumentationSubjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "documentation_subject_seq")
    // Allocation size 1, matching the "increment by 1" of the sequence in the migration - Hibernate's default of
    // 50 would hand out identifiers in blocks, and the identifier of an upload is part of the key its bundle is
    // stored under.
    @SequenceGenerator(name = "documentation_subject_seq", sequenceName = "documentation_subject_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String site;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubjectKind kind;

    @Column(name = "system_name", nullable = false)
    private String system;

    private String name;

    @Column(nullable = false)
    private Instant createdAt;
}
