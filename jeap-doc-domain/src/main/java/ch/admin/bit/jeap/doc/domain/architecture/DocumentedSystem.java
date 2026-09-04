package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One system of the landscape, with everything the architecture repository exports about it.
 * <p>
 * The slug and the name are two fields on purpose. The architecture repository stores names as people write
 * them, such as {@code ORDERS}, while every path segment is a slug. The slug has to be the one an upload names,
 * or a team's documentation and the generated documentation land in different trees.
 *
 * @param name          the system name as the architecture repository has it
 * @param slug          the same name, lower-cased: the path segment it is served under
 * @param description   what the system is, or null
 * @param aliases       further names it is known under
 * @param team          the team owning it, or null
 * @param components    its components, sorted by name
 * @param relations     the active relations this system <b>defines</b> - see {@link ArchitectureModel}
 * @param messages      the events and commands it defines
 */
public record DocumentedSystem(
        String name,
        String slug,
        String description,
        List<String> aliases,
        Team team,
        List<DocumentedComponent> components,
        List<SystemRelation> relations,
        List<DocumentedMessage> messages) {

    public DocumentedSystem {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        components = components == null ? List.of() : List.copyOf(components);
        relations = relations == null ? List.of() : List.copyOf(relations);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * The same system with its messages joined to what was replicated about their versions. A generation run
     * does this per system, so that a landscape's renderings are never all held at once.
     */
    public DocumentedSystem withMessages(List<DocumentedMessage> messages) {
        return new DocumentedSystem(name, slug, description, aliases, team, components, relations, messages);
    }

    /**
     * The same system with its components replaced. The import uses it to compare a landscape without letting
     * a component's clock decide the answer - see {@link DocumentedComponent#seenByTheDay()}.
     */
    public DocumentedSystem withComponents(List<DocumentedComponent> components) {
        return new DocumentedSystem(name, slug, description, aliases, team, components, relations, messages);
    }

    public List<DocumentedMessage> messagesOfKind(MessageKind kind) {
        return messages.stream().filter(message -> message.kind() == kind).toList();
    }

    public List<DocumentedMessage> events() {
        return messagesOfKind(MessageKind.EVENT);
    }

    public List<DocumentedMessage> commands() {
        return messagesOfKind(MessageKind.COMMAND);
    }

    /**
     * A component of this system by name, ignoring case - what a relation's end has to be resolved through.
     */
    public boolean hasComponent(String componentName) {
        return componentName != null && components.stream()
                .anyMatch(component -> component.name().equalsIgnoreCase(componentName));
    }
}
