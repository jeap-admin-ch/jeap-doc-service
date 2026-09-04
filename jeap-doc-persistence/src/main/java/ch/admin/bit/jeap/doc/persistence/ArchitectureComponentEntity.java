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
 * One component of a system, with the references to the artifacts it publishes.
 */
@Entity
@Table(name = "architecture_component")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureComponentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_component_seq")
    @SequenceGenerator(name = "architecture_component_seq", sequenceName = "architecture_component_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "system_id", nullable = false)
    private Long systemId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    private String description;

    @Column(nullable = false)
    private String type;

    @Column(name = "team_id")
    private Long teamId;

    private String importer;

    @Column(name = "last_seen")
    private Instant lastSeen;

    @Column(name = "last_seen_zone")
    private String lastSeenZone;

    @Column(name = "openapi_version")
    private String openApiVersion;

    @Column(name = "openapi_server_url")
    private String openApiServerUrl;

    @Column(name = "openapi_content_url")
    private String openApiContentUrl;

    @Column(name = "openapi_swagger_url")
    private String openApiSwaggerUrl;

    @Column(name = "db_schema_version")
    private String dbSchemaVersion;

    @Column(name = "db_schema_content_url")
    private String dbSchemaContentUrl;
}
