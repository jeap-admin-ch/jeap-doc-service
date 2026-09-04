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
 * One active relation between two components, belonging to the system that defines it.
 */
@Entity
@Table(name = "architecture_relation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_relation_seq")
    @SequenceGenerator(name = "architecture_relation_seq", sequenceName = "architecture_relation_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String kind;

    @Column(name = "consumer_system")
    private String consumerSystem;

    private String consumer;

    @Column(name = "provider_system")
    private String providerSystem;

    private String provider;

    @Column(name = "message_type")
    private String messageType;

    private String method;

    private String path;

    @Column(name = "pact_url")
    private String pactUrl;
}
