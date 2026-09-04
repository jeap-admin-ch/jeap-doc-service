package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
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
 * The replicated Avro schemas of one message type version.
 * <p>
 * The system and the message type are named rather than referenced, like the artifacts and for the same reason:
 * the model is replaced wholesale on every import, and a foreign key into it would take these rows with it.
 * <p>
 * A row is <b>replaced in place</b> when the upstream serves a version again with different content. It is
 * addressed by the unique index on the environment, the folded system and message type names and the version,
 * so storing a version twice updates the row rather than inserting a second one - which the unique index would
 * refuse, failing the whole import kind for as long as the upstream keeps offering it.
 * <p>
 * The four columns that identify a version are {@code updatable = false}: a replacement was matched on them, so
 * writing them back is at best a no-op and at worst a rename nothing intended.
 */
@Entity
@Table(name = "architecture_message_schema")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureMessageSchemaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_message_schema_seq")
    @SequenceGenerator(name = "architecture_message_schema_seq",
            sequenceName = "architecture_message_schema_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String environment;

    @Column(name = "system_name", nullable = false, updatable = false)
    private String systemName;

    @Column(name = "message_name", nullable = false, updatable = false)
    private String messageName;

    @Column(nullable = false, updatable = false)
    private String version;

    @Column(name = "compatibility_mode")
    private String compatibilityMode;

    @Column(name = "compatible_version")
    private String compatibleVersion;

    @Column(name = "key_schema_name")
    private String keySchemaName;

    @Column(name = "key_schema_url")
    private String keySchemaUrl;

    /**
     * The rendering, not the file - see {@code MessageSchema}. Text rather than varchar: a resolved schema with
     * every import inlined is thousands of characters, and nothing about it is worth a length nobody chose.
     */
    @Column(name = "key_schema")
    private String keySchema;

    @Column(name = "value_schema_name")
    private String valueSchemaName;

    @Column(name = "value_schema_url")
    private String valueSchemaUrl;

    @Column(name = "value_schema")
    private String valueSchema;

    /**
     * The tag the upstream served these bytes under, sent back as {@code If-None-Match} by the next run that
     * reaches this version. Null where the upstream served none, which makes that run ask unconditionally.
     */
    @Column(name = "etag")
    private String etag;

    @Column(name = "replicated_at", nullable = false)
    private Instant replicatedAt;

    /** When the version was last stored or confirmed - what a run orders its revalidations by, oldest first. */
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
