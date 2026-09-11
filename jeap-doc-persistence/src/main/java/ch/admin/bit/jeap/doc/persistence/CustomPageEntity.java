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
 * One file of a documentation set. Written and removed with its set, never addressed on its own.
 */
@Entity
@Table(name = "custom_page")
@IdClass(CustomPageEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class CustomPageEntity implements Persistable<CustomPageEntity.Key> {

    @Id
    @Column(name = "set_id", nullable = false)
    private Long setId;

    @Id
    @Column(nullable = false)
    private String chapter;

    @Id
    @Column(name = "file_name", nullable = false)
    private String fileName;

    private String title;

    @Column(name = "sidebar_position", nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean asset;

    /**
     * Whether this row is an insert.
     * <p>
     * Without it every row is written with a {@code merge}, which is a {@code select} before every
     * {@code insert}: Spring Data decides insert-versus-update from the identifier, and an {@code @IdClass}
     * is never null. Declaring it here answers the question instead of asking the database.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean justCreated = true;

    @Override
    public Key getId() {
        Key key = new Key();
        key.setSetId(setId);
        key.setChapter(chapter);
        key.setFileName(fileName);
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

        private Long setId;

        private String chapter;

        private String fileName;
    }
}
