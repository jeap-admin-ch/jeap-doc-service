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
 * That a component produces or consumes a message, on which topic.
 */
@Entity
@Table(name = "architecture_message_contract")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureMessageContractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_message_contract_seq")
    @SequenceGenerator(name = "architecture_message_contract_seq", sequenceName = "architecture_message_contract_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String role;

    @Column(name = "component_name")
    private String componentName;

    @Column(name = "system_name")
    private String systemName;

    private String topic;
}
