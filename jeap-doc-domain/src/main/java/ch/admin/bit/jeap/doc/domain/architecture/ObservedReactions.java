package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One reaction graph, read: which messages were observed, what reacted to them, and what those reactions
 * published in answer.
 * <p>
 * The shape the reaction observer serves, in this service's own words. It is not what a page draws - that is
 * {@link ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView}, which decides labels, clusters and
 * links - and it is not what is stored either, which is the observer's bytes as they arrived.
 *
 * @param messages  the message nodes, each with the identifier the observer gave it
 * @param reactions the reaction nodes: one component reacting, identified by the observer's own row id
 * @param triggers  which message makes which reaction happen, and how often it was seen
 * @param actions   which message a reaction publishes in answer
 */
public record ObservedReactions(
        List<ObservedMessage> messages,
        List<ObservedReaction> reactions,
        List<ObservedTrigger> triggers,
        List<ObservedAction> actions) {

    public ObservedReactions {
        messages = messages == null ? List.of() : List.copyOf(messages);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static ObservedReactions empty() {
        return new ObservedReactions(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Whether there is anything to draw. An empty graph is not content: a system nothing has been observed
     * reacting to gets no runtime view at all rather than a page saying so.
     */
    public boolean isEmpty() {
        return messages.isEmpty() && reactions.isEmpty();
    }

    /**
     * A message the observer saw.
     *
     * @param messageType the message type name, as the publisher declared it
     * @param variant     the variant, or null when it has none
     */
    public record ObservedMessage(long id, String messageType, String variant) {
    }

    /**
     * One component reacting. <b>The identifier is the observer's row id</b>, and it is what a deep link into
     * a drawn graph addresses - the observer's graph carries no other name for a reaction.
     *
     * @param median how many times it was seen in the observer's statistics window, or null where the
     *               observer sent none. It is counted per reaction, so it is on the node as well as on the
     *               trigger edge - and on the node only, for a reaction that no message triggered
     */
    public record ObservedReaction(long id, String component, Integer median) {
    }

    /**
     * @param median how many times it was seen in the observer's statistics window, or null when it kept no
     *               number. It is what makes a busy trigger a thicker arrow
     */
    public record ObservedTrigger(long messageId, long reactionId, Integer median) {
    }

    public record ObservedAction(long reactionId, long messageId) {
    }
}
