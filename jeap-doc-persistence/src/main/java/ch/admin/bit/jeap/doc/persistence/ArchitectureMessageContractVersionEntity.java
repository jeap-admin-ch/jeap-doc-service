package ch.admin.bit.jeap.doc.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;

/**
 * One version a component has contracted for.
 */
@Entity
@Table(name = "architecture_message_contract_version")
@IdClass(ArchitectureMessageContractVersionEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureMessageContractVersionEntity implements Persistable<ArchitectureMessageContractVersionEntity.Key> {

    @Id
    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Id
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String version;


    /**
     * Whether this row is an insert.
     * <p>
     * <b>Without it every one of these rows is written with a {@code merge}, which is a {@code select} before
     * every {@code insert}.</b> Spring Data decides insert-versus-update from the identifier, and an
     * {@code @IdClass} whose second component is a primitive {@code int} is never null - so it reads as an
     * entity that already exists, on every row of the four largest tables, on every import of every
     * environment. Declaring it here answers the question instead of having the database answer it.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean justCreated = true;

    @Override
    public Key getId() {
        Key key = new Key();
        key.setContractId(contractId);
        key.setOrdinal(ordinal);
        return key;
    }

    @Override
    public boolean isNew() {
        return justCreated;
    }

    @PostLoad
    @PostPersist
    void stored() {
        justCreated = false;
    }

    /** The composite key, as {@code @IdClass} needs it. */
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    static class Key implements Serializable {

        private Long contractId;

        private int ordinal;
    }
}
