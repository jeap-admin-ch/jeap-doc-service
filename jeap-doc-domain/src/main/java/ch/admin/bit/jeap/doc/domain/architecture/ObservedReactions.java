package ch.admin.bit.jeap.doc.domain.architecture;

import java.math.BigDecimal;
import java.util.List;

/**
 * What was observed at runtime: interfaces, the reactions they trigger, and the actions those reactions take.
 * One complete environment snapshot, independent of its JSON representation and of what a page draws.
 * History is kept separately from execution flow.
 */
public record ObservedReactions(String identityScheme, List<ObservedInterface> interfaces,
                                List<ObservedReaction> reactions, List<ObservedTrigger> triggers,
                                List<ObservedAction> actions, List<ObservedPredecessor> predecessors) {
    public ObservedReactions {
        interfaces = List.copyOf(interfaces);
        reactions = List.copyOf(reactions);
        triggers = List.copyOf(triggers);
        actions = List.copyOf(actions);
        predecessors = List.copyOf(predecessors);
    }

    public ObservedReactions(List<ObservedInterface> interfaces, List<ObservedReaction> reactions,
                             List<ObservedTrigger> triggers, List<ObservedAction> actions) {
        this("observer-v1", interfaces, reactions, triggers, actions, List.of());
    }

    public static ObservedReactions empty() {
        return new ObservedReactions(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return interfaces.isEmpty() && reactions.isEmpty();
    }

    public List<ObservedMessage> messages() {
        return interfaces.stream().filter(ObservedMessage.class::isInstance).map(ObservedMessage.class::cast).toList();
    }

    /** Opaque, namespaced identity. An interface keeps its identity when its representation changes. */
    public record Identity(String nodeType, String key) {}

    public sealed interface ObservedInterface permits ObservedMessage, ObservedHttpOperation, ObservedGenericInterface {
        Identity identity();
        String interfaceType();
        String fqn();
    }

    /**
     * A message type as named by its publisher.
     *
     * @param variant the observer's exact variant, or null or empty when unqualified
     */
    public record ObservedMessage(Identity identity, String interfaceType, String fqn,
                                  String messageType, String variant) implements ObservedInterface {
        /** V1 supplies no interface type or FQN. */
        public ObservedMessage(Identity identity, String messageType, String variant) {
            this(identity, null, null, messageType, variant);
        }
    }

    public record ObservedHttpOperation(Identity identity, String interfaceType, String fqn,
                                        String provider, String method, String path) implements ObservedInterface {}

    /** Unknown interface representations retain their identity, semantics and connections. */
    public record ObservedGenericInterface(Identity identity, String representation, String interfaceType,
                                           String fqn) implements ObservedInterface {}

    public enum ReactionStatus { ACTIVE, SUPERSEDED, UNKNOWN }

    /**
     * One component reacting, identified by the observer's opaque identity.
     *
     * @param median the daily median within the observer's statistics window, shared by all trigger and
     *               action connections; missing values are invalid, never interpreted as zero
     */
    public record ObservedReaction(Identity identity, String system, String component, BigDecimal median,
                                   ReactionStatus status, Integer reactionModelVersion,
                                   BigDecimal unidentifiedActionsRatio, String groupKey) {
        /** V1 supplies only a component and a median. */
        public ObservedReaction(Identity identity, String component, BigDecimal median) {
            this(identity, null, component, median, ReactionStatus.UNKNOWN, null, null, null);
        }
    }

    public record ObservedTrigger(Identity interfaceId, Identity reactionId) {}
    public record ObservedAction(Identity reactionId, Identity interfaceId) {}
    /** History, never an observed execution-flow connection. */
    public record ObservedPredecessor(Identity successor, Identity predecessor) {}
}
