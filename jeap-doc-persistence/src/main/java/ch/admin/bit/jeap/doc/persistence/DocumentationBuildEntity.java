package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.BuildState;
import ch.admin.bit.jeap.doc.domain.BuildTrigger;
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
 * One run of the documentation generator.
 * <p>
 * {@code id} comes from a sequence and is a path segment twice over - of the build's workspace and of the prefix
 * its site is published under - so it is handed out one at a time rather than in blocks.
 */
@Entity
@Table(name = "documentation_build")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class DocumentationBuildEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "documentation_build_seq")
    @SequenceGenerator(name = "documentation_build_seq", sequenceName = "documentation_build_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String site;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, updatable = false)
    private BuildTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildState state;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false, updatable = false)
    private String instance;

    @Column(name = "object_prefix")
    private String objectPrefix;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "docusaurus_millis", nullable = false)
    private long docusaurusMillis;

    @Column(name = "failure_reason")
    private String failureReason;
}
