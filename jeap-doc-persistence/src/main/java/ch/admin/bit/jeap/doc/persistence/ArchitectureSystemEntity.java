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
 * One system of one environment's landscape.
 * <p>
 * The children hang off this by a plain foreign key rather than by a mapped association. A generation run reads
 * a whole landscape at once, and one query per table beats an association that turns forty-nine systems into
 * five hundred round trips.
 */
@Entity
@Table(name = "architecture_system")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureSystemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_system_seq")
    @SequenceGenerator(name = "architecture_system_seq", sequenceName = "architecture_system_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String environment;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    private String description;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;
}
