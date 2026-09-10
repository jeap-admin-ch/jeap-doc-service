package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a stored reaction graph: the observer's nodes and edges, in this service's own words.
 * <p>
 * <b>The polymorphism is read by hand rather than by binding it.</b> The observer's payload discriminates its
 * nodes on {@code nodeType} and its edges on {@code edgeType}, and a binding would refuse a whole graph over a
 * kind of node this version has never heard of. Here an unknown one is skipped and the rest of the graph is
 * drawn, which is the same rule the rest of this service applies to an upstream enum.
 */
@Slf4j
@RequiredArgsConstructor
class ReactionObserverGraphContent implements ReactionGraphContent {

    private final JsonMapper json;

    @Override
    public ObservedReactions read(StoredReactionGraph graph) {
        JsonNode parsed;
        try {
            parsed = json.readTree(graph.data());
        } catch (JacksonException e) {
            // One page loses its graph, and the site is still generated. The bytes are the observer's, and
            // nothing this service does at generation time can correct them.
            log.warn("The stored {} of {} in the environment {} is not readable, so its runtime view is not "
                     + "written.", graph.kind(), graph.name(), graph.environment(), e);
            return ObservedReactions.empty();
        }
        List<ObservedReactions.ObservedMessage> messages = new ArrayList<>();
        List<ObservedReactions.ObservedReaction> reactions = new ArrayList<>();
        for (JsonNode node : parsed.path("nodes")) {
            switch (node.path("nodeType").asString("")) {
                case ReactionObserverNodes.MESSAGE -> messages.add(new ObservedReactions.ObservedMessage(
                        node.path("id").asLong(), ReactionObserverNodes.text(node, "messageType"),
                        ReactionObserverNodes.text(node, "variant")));
                case ReactionObserverNodes.REACTION -> reactions.add(new ObservedReactions.ObservedReaction(
                        node.path("id").asLong(), ReactionObserverNodes.text(node, "component")));
                default -> log.debug("A node of a kind this version does not draw is left out of the {} of {}.",
                        graph.kind(), graph.name());
            }
        }
        List<ObservedReactions.ObservedTrigger> triggers = new ArrayList<>();
        List<ObservedReactions.ObservedAction> actions = new ArrayList<>();
        for (JsonNode edge : parsed.path("edges")) {
            switch (edge.path("edgeType").asString("")) {
                case ReactionObserverNodes.TRIGGER -> triggers.add(new ObservedReactions.ObservedTrigger(
                        edge.path("sourceId").asLong(), edge.path("targetReactionId").asLong(),
                        edge.path("median").isNumber() ? edge.path("median").asInt() : null));
                case ReactionObserverNodes.ACTION -> actions.add(new ObservedReactions.ObservedAction(
                        edge.path("sourceReactionId").asLong(), edge.path("targetId").asLong()));
                default -> log.debug("An edge of a kind this version does not draw is left out of the {} of {}.",
                        graph.kind(), graph.name());
            }
        }
        return new ObservedReactions(messages, reactions, triggers, actions);
    }
}
