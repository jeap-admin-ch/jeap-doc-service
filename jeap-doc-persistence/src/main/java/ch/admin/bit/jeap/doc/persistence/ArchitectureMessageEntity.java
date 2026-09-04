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

/**
 * An event or a command defined by a system.
 */
@Entity
@Table(name = "architecture_message")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_message_seq")
    @SequenceGenerator(name = "architecture_message_seq", sequenceName = "architecture_message_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String name;

    /**
     * Nullable for the rows written before the column existed. The importer fills it on every write, and the
     * model is replaced whole on every import, so the first import after the deployment leaves no null behind.
     */
    private String slug;

    @Column(nullable = false)
    private String kind;

    private String scope;

    private String topic;

    private String description;

    @Column(name = "descriptor_url")
    private String descriptorUrl;

    @Column(name = "documentation_url")
    private String documentationUrl;
}
