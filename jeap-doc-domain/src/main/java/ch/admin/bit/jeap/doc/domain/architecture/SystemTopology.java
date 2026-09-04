package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One system as the architecture repository's per-system resource has it: everything of a
 * {@link DocumentedSystem} except two things.
 * <p>
 * The slug is missing because it is this service's decision and not the architecture repository's, and the
 * messages are missing because they are a resource of their own. The importer puts all three together.
 *
 * @param name        the system name as the architecture repository has it
 * @param description what the system is, or null
 * @param aliases     further names it is known under
 * @param team        the team owning it, or null
 * @param components  its components
 * @param relations   the active relations this system defines - see {@link ArchitectureModel}
 */
public record SystemTopology(
        String name,
        String description,
        List<String> aliases,
        Team team,
        List<DocumentedComponent> components,
        List<SystemRelation> relations) {

    public SystemTopology {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        components = components == null ? List.of() : List.copyOf(components);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}
