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
 * A team owning systems and components, as one environment's architecture repository has it.
 */
@Entity
@Table(name = "architecture_team")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for jpa
class ArchitectureTeamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "architecture_team_seq")
    @SequenceGenerator(name = "architecture_team_seq", sequenceName = "architecture_team_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String environment;

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_address")
    private String contactAddress;

    @Column(name = "jira_link")
    private String jiraLink;

    @Column(name = "confluence_link")
    private String confluenceLink;
}
