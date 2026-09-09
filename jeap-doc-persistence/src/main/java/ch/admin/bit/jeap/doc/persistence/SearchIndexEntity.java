package ch.admin.bit.jeap.doc.persistence;

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
 * One run of the search indexer.
 * <p>
 * {@code id} is a path segment - the prefix an index is published under - so it is handed out one at a time
 * rather than in blocks, as the build's is.
 */
@Entity
@Table(name = "documentation_search_index")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class SearchIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "documentation_search_index_seq")
    @SequenceGenerator(name = "documentation_search_index_seq",
            sequenceName = "documentation_search_index_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String site;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SearchIndexState state;

    @Column(name = "object_prefix")
    private String objectPrefix;

    private Integer records;

    @Column(nullable = false, updatable = false)
    private String instance;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_reason")
    private String failureReason;
}
