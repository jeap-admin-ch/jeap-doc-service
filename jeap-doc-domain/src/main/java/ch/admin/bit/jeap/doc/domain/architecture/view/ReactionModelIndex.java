package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The landscape indexed by the names a reaction graph carries: a message type, and a component.
 * <p>
 * <b>Built once per environment, not once per graph.</b> A graph names its nodes and nothing else - no slug, no
 * system - so every node has to be looked up in the model, and an environment has a graph per system, per
 * component and per message variant. Walking the model per node made that the product of two large numbers:
 * fifteen hundred graphs against a landscape of thousands of messages is hundreds of millions of comparisons
 * for a picture, every build.
 * <p>
 * <b>Folded, because two exports of one upstream spell one name two ways.</b> The observer stores a system name
 * lower-cased and a component name as the publisher sent it; the model has its own spellings. Everything that
 * joins the two folds, so this does too - and it is the same fold the reaction graphs are stored under.
 * <p>
 * Where a name is ambiguous the index says so rather than choosing: {@link #systemsOfComponent} answers both
 * systems that call a component {@code gateway}, and a link is then not written at all.
 */
public final class ReactionModelIndex {

    private final Map<String, DocumentedSystem> systemByMessage;
    private final Map<String, DocumentedMessage> messageByName;
    private final Map<String, List<DocumentedSystem>> systemsByComponent;
    private final Map<String, Map<String, String>> componentSlugs;

    private ReactionModelIndex(Map<String, DocumentedSystem> systemByMessage,
                               Map<String, DocumentedMessage> messageByName,
                               Map<String, List<DocumentedSystem>> systemsByComponent,
                               Map<String, Map<String, String>> componentSlugs) {
        this.systemByMessage = systemByMessage;
        this.messageByName = messageByName;
        this.systemsByComponent = systemsByComponent;
        this.componentSlugs = componentSlugs;
    }

    /** An index of one landscape. The systems are walked once, in the order the model documents them. */
    public static ReactionModelIndex of(ArchitectureModel model) {
        Map<String, DocumentedSystem> systemByMessage = new LinkedHashMap<>();
        Map<String, DocumentedMessage> messageByName = new LinkedHashMap<>();
        Map<String, List<DocumentedSystem>> systemsByComponent = new LinkedHashMap<>();
        Map<String, Map<String, String>> componentSlugs = new LinkedHashMap<>();
        for (DocumentedSystem system : model.systems()) {
            for (DocumentedMessage message : system.messages()) {
                // The first system that defines a name keeps it, which is what a stream over the model in
                // its own order answered before: the order is stable, so a name two systems define resolves
                // to the same one on every build.
                systemByMessage.putIfAbsent(fold(message.name()), system);
                messageByName.putIfAbsent(fold(message.name()), message);
            }
            Map<String, String> slugs = new LinkedHashMap<>();
            for (DocumentedComponent component : system.components()) {
                systemsByComponent.computeIfAbsent(fold(component.name()), name -> new ArrayList<>())
                        .add(system);
                slugs.putIfAbsent(fold(component.name()), component.slug());
            }
            componentSlugs.put(system.slug(), slugs);
        }
        return new ReactionModelIndex(systemByMessage, messageByName, systemsByComponent, componentSlugs);
    }

    /** An index of nothing, for a run that draws no reactions. */
    public static ReactionModelIndex empty() {
        return of(ArchitectureModel.empty());
    }

    /** The system that defines a message type, or empty when this landscape does not. */
    public Optional<DocumentedSystem> systemOfMessage(String messageType) {
        return Optional.ofNullable(systemByMessage.get(fold(messageType)));
    }

    /** The message type as the model documents it, or empty when it does not. */
    public Optional<DocumentedMessage> message(String messageType) {
        return Optional.ofNullable(messageByName.get(fold(messageType)));
    }

    /**
     * Every system that has a component of this name. More than one is possible, and a caller that has to
     * point at one of them has to treat that as none - see {@link ArchitectureModel#systemsOf}.
     */
    public List<DocumentedSystem> systemsOfComponent(String component) {
        return systemsByComponent.getOrDefault(fold(component), List.of());
    }

    /** The page segment of a component of one system, or empty where that system has no such component. */
    public Optional<String> componentSlug(DocumentedSystem system, String component) {
        return Optional.ofNullable(componentSlugs.getOrDefault(system.slug(), Map.of()).get(fold(component)));
    }

    private static String fold(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
