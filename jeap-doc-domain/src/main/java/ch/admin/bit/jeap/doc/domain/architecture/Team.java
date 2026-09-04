package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * The team owning a system or a component.
 *
 * @param name            what the team is called
 * @param contactAddress  where to write to them, or null
 * @param jiraLink        their Jira project, or null
 * @param confluenceLink  their Confluence space, or null
 */
public record Team(String name, String contactAddress, String jiraLink, String confluenceLink) {
}
