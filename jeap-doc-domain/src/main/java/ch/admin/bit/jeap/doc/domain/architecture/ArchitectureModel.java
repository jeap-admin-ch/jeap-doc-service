package ch.admin.bit.jeap.doc.domain.architecture;

import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The architecture landscape of one environment, as a generation run reads it.
 * <p>
 * The whole landscape, not one system at a time. A system's context view cannot be computed from that system
 * alone: in the architecture repository a relation belongs to the system that <i>defines</i> it, so a system
 * that only consumes another system's events exports no relations while its context view has to show them.
 * {@link SystemContext} reads across every system, and this is what it reads across.
 *
 * @param systems every system of the landscape, in the order they are documented
 */
public record ArchitectureModel(List<DocumentedSystem> systems) {


    public ArchitectureModel {
        systems = List.copyOf(systems);
    }

    public static ArchitectureModel of(List<DocumentedSystem> systems) {
        List<DocumentedSystem> sorted = new ArrayList<>(systems);
        sorted.sort(Comparator.comparing(DocumentedSystem::slug));
        return new ArchitectureModel(sorted);
    }

    public static ArchitectureModel empty() {
        return of(List.of());
    }

    public boolean isEmpty() {
        return systems.isEmpty();
    }

    /**
     * Every active relation of the landscape, wherever it was defined.
     */
    public List<SystemRelation> relations() {
        return systems.stream().flatMap(system -> system.relations().stream()).toList();
    }

    /**
     * The system a component belongs to, by the component's name - what a relation's counterpart has to be
     * resolved through when the export did not name it.
     */
    public Optional<DocumentedSystem> systemOf(String componentName) {
        if (componentName == null || componentName.isBlank()) {
            return Optional.empty();
        }
        return systems.stream()
                .filter(system -> system.components().stream()
                        .anyMatch(component -> component.name().equalsIgnoreCase(componentName)))
                .findFirst();
    }

    /**
     * Every system that has a component of the given name. More than one is possible: two systems may each
     * call a component {@code gateway}, and whoever links to one has to know which - see {@link #systemNamed}.
     */
    public List<DocumentedSystem> systemsOf(String componentName) {
        if (componentName == null || componentName.isBlank()) {
            return List.of();
        }
        return systems.stream().filter(system -> system.hasComponent(componentName)).toList();
    }

    /**
     * A system by its name or one of its aliases, ignoring case - what a contract names, which is the
     * architecture repository's spelling and not the slug.
     */
    public Optional<DocumentedSystem> systemNamed(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return systems.stream()
                .filter(system -> system.name().equalsIgnoreCase(name)
                                  || system.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(name)))
                .findFirst();
    }

    /**
     * A system by the slug it is served under.
     */
    public Optional<DocumentedSystem> find(String slug) {
        return systems.stream().filter(system -> system.slug().equals(slug)).findFirst();
    }

    /**
     * Whether a system of this landscape is served under the given slug - what a link may be written to.
     */
    public boolean documents(String slug) {
        return find(slug).isPresent();
    }
}
