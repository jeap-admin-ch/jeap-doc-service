package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;

import java.util.List;
import java.util.Optional;

/**
 * Turns the names the reaction observer uses into the names the model uses.
 * <p>
 * <b>The observer's names are not the model's.</b> It stores a system name lower-cased and a component name as
 * the publisher sent it, while the model has its own spellings and its aliases - so a graph resolved by an
 * exact match is a graph a reader never finds. Every other importer of the architecture repository resolves a
 * system through name-or-alias; the reaction importer there is the one that does not, and its symptom is a
 * system with reactions whose page has no graph and one log line nobody reads.
 * <p>
 * Resolving happens <b>while importing</b> and not while generating: what is stored is the model's spelling, so
 * a page is written without a lookup that can fail, and a name that resolves to nothing is not stored at all.
 */
final class ReactionNameResolver {

    private final ArchitectureModel model;

    ReactionNameResolver(ArchitectureModel model) {
        this.model = model;
    }

    /**
     * The same reference under the model's spelling, or empty when the model does not have what the observer
     * named. Empty is the normal case for a system that reacts to messages on a platform whose model does not
     * carry it - it is counted and reported, not stored.
     */
    Optional<ReactionGraphRef> resolve(ReactionGraphRef listed) {
        return switch (listed.kind()) {
            case SYSTEM_REACTIONS -> model.systemNamed(listed.name())
                    .map(system -> listed.withName(system.name(), null));
            case COMPONENT_REACTIONS -> resolveComponent(listed);
            case MESSAGE_REACTIONS -> resolveMessage(listed);
            case MODEL, OPENAPI_SPEC, DATABASE_SCHEMA, MESSAGE_SCHEMA -> Optional.empty();
        };
    }

    /**
     * A component, with the system it belongs to.
     * <p>
     * The observer's index says which system the reactions were published under, so that is tried first -
     * through name-or-alias, because the observer's system name is a Kafka publisher's. Where it does not
     * resolve, a component name that only one system in the model has is unambiguous and is taken; a name
     * two systems have is <b>not</b>, and is left unresolved rather than filed under whichever came first.
     * A component graph is drawn on a page below one system, so a graph whose system cannot be decided has
     * nowhere to go.
     */
    private Optional<ReactionGraphRef> resolveComponent(ReactionGraphRef listed) {
        Optional<DocumentedSystem> published = model.systemNamed(listed.system())
                .filter(system -> system.hasComponent(listed.name()));
        if (published.isPresent()) {
            return published.map(system -> listed.withName(componentOf(system, listed.name()), system.name()));
        }
        List<DocumentedSystem> systems = model.systemsOf(listed.name());
        if (systems.size() != 1) {
            return Optional.empty();
        }
        DocumentedSystem system = systems.getFirst();
        return Optional.of(listed.withName(componentOf(system, listed.name()), system.name()));
    }

    /**
     * A message type, by name ignoring case. The systems are in a stable order, so a name two of them define
     * resolves to the same one on every run.
     */
    private Optional<ReactionGraphRef> resolveMessage(ReactionGraphRef listed) {
        return model.systems().stream()
                .flatMap(system -> system.messages().stream())
                .filter(message -> message.name().equalsIgnoreCase(listed.name()))
                .findFirst()
                .map(DocumentedMessage::name)
                .map(name -> listed.withName(name, null));
    }

    /** The model's spelling of a component the system is known to have. */
    private static String componentOf(DocumentedSystem system, String name) {
        return system.components().stream()
                .filter(component -> component.name().equalsIgnoreCase(name))
                .findFirst()
                .map(DocumentedComponent::name)
                .orElse(name);
    }
}
