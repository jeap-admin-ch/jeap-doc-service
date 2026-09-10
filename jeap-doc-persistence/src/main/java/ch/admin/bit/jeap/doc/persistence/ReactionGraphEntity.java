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
 * One replicated reaction graph.
 * <p>
 * The system, component or message type is named rather than referenced: the model is replaced wholesale on
 * every import, and a foreign key into it would take the graph down with it.
 * <p>
 * <b>No {@code toString} that reaches the graph.</b> Lombok generates none here, and one written by hand would
 * put a whole system's reaction graph in a log line.
 */
@Entity
@Table(name = "reaction_graph")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ReactionGraphEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reaction_graph_seq")
    @SequenceGenerator(name = "reaction_graph_seq", sequenceName = "reaction_graph_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String environment;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String variant;

    @Column(name = "system_name", nullable = false, updatable = false)
    private String systemName;

    @Column(name = "upstream_name", nullable = false)
    private String upstreamName;

    @Column(nullable = false)
    private String etag;

    @Column
    private String fingerprint;

    @Column(name = "graph_data", nullable = false)
    private byte[] graphData;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "drawable_nodes", nullable = false)
    private int drawableNodes;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
