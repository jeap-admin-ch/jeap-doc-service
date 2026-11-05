package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;


/** Validates either API and reads stored snapshots with an explicit format envelope. */
final class FullReactionGraphReader {
    private static final String ACTION = "ACTION";
    private static final String TRIGGER = "TRIGGER";
    private static final String MESSAGE = "MESSAGE";
    private static final String REACTION = "REACTION";

    private static final String NODES = "nodes";
    private static final String EDGES = "edges";
    private static final String INTERFACE = "INTERFACE";
    private static final String V2_FORMAT = "observer-v2";
    private FullReactionGraphReader() {}

    static ObservedReactions read(JsonNode stored) {
        require(stored != null && stored.isObject(), "A stored graph needs a format envelope");
        String format = text(stored, "sourceFormat");
        require(format.equals("observer-v1") || format.equals(V2_FORMAT), "Unknown stored graph format");
        return read(stored.path("graph"), format.equals(V2_FORMAT));
    }

    static ObservedReactions read(JsonNode graph, boolean v2) {
        if (v2) {
            return readV2(graph);
        }
        require(graph != null && graph.path(NODES).isArray() && graph.path(EDGES).isArray(),
                "A graph needs node and edge arrays");
        var interfaces = new ArrayList<ObservedInterface>();
        var reactions = new ArrayList<ObservedReaction>();
        Set<Identity> identities = new HashSet<>();
        for (JsonNode node : graph.path(NODES)) {
            String type = text(node, "nodeType");
            require(type.equals(MESSAGE) || type.equals(REACTION), "Unsupported V1 node type");
            Identity identity = new Identity(type, key(node, "id"));
            require(identities.add(identity), "Duplicate node identity");
            if (type.equals(MESSAGE)) {
                interfaces.add(new ObservedMessage(identity, text(node, "messageType"), optionalText(node, "variant")));
            } else {
                JsonNode median = node.path("median");
                require(median.isNumber(), "A reaction needs a numeric daily median");
                BigDecimal value = median.decimalValue();
                require(value.signum() >= 0, "A daily median cannot be negative");
                reactions.add(new ObservedReaction(identity, text(node, "component"), value));
            }
        }
        var edges = new ArrayList<Edge>();
        Set<Edge> distinct = new HashSet<>();
        Set<Identity> triggeredReactions = new HashSet<>();
        for (JsonNode edge : graph.path(EDGES)) {
            String type = text(edge, "edgeType");
            Edge mapped = switch (type) {
                case TRIGGER -> new Edge(type, new Identity(MESSAGE, key(edge, "sourceId")),
                        new Identity(REACTION, key(edge, "targetReactionId")));
                case ACTION -> new Edge(type, new Identity(REACTION, key(edge, "sourceReactionId")),
                        new Identity(MESSAGE, key(edge, "targetId")));
                default -> throw new IllegalArgumentException("Unsupported V1 edge type");
            };
            require(identities.contains(mapped.source()) && identities.contains(mapped.target()),
                    "An edge refers to a missing node");
            require(distinct.add(mapped), "Duplicate edge");
            requireUniqueTrigger(mapped, triggeredReactions);
            edges.add(mapped);
        }
        return observations("observer-v1", interfaces, reactions, edges);
    }

    private static ObservedReactions readV2(JsonNode graph) {
        require(graph != null && graph.path(NODES).isArray() && graph.path(EDGES).isArray(),
                "A graph needs node and edge arrays");
        var interfaces = new ArrayList<ObservedInterface>();
        var reactions = new ArrayList<ObservedReaction>();
        Map<String, Identity> identities = new HashMap<>();
        for (JsonNode node : graph.path(NODES)) {
            String type = text(node, "nodeType");
            String id = text(node, "id");
            boolean reaction = type.equals(REACTION);
            Identity identity = new Identity(reaction ? REACTION : INTERFACE, id);
            require(identities.putIfAbsent(id, identity) == null, "Duplicate V2 node id");
            if (reaction) {
                reactions.add(reaction(node, identity));
            } else {
                interfaces.add(observedInterface(node, identity, type));
            }
        }
        var edges = new ArrayList<Edge>();
        Set<Edge> distinct = new HashSet<>();
        Set<Identity> triggeredReactions = new HashSet<>();
        for (JsonNode edge : graph.path(EDGES)) {
            String type = text(edge, "edgeType");
            if (!Set.of(TRIGGER, ACTION, "PREDECESSOR").contains(type)) {
                continue; // A future relationship must not acquire execution-flow semantics by accident.
            }
            Edge mapped = v2Edge(edge, type, identities);
            require(distinct.add(mapped), "Duplicate edge");
            requireUniqueTrigger(mapped, triggeredReactions);
            edges.add(mapped);
        }
        return observations(V2_FORMAT, interfaces, reactions, edges);
    }

    private static void requireUniqueTrigger(Edge edge, Set<Identity> triggeredReactions) {
        if (edge.edgeType().equals(TRIGGER)) {
            require(triggeredReactions.add(edge.target()), "A reaction cannot have multiple triggers");
        }
    }

    private static ObservedReaction reaction(JsonNode node, Identity identity) {
        BigDecimal median = number(node, "median");
        require(median.signum() >= 0, "A daily median cannot be negative");
        return new ObservedReaction(identity, owner(node), text(node, "component"), median, status(node),
                modelVersion(node), unidentifiedActionsRatio(node), optionalText(node, "groupKey"));
    }

    private static ReactionStatus status(JsonNode node) {
        String status = optionalText(node, "status");
        return switch (status == null ? "" : status) {
            case "ACTIVE" -> ReactionStatus.ACTIVE;
            case "SUPERSEDED" -> ReactionStatus.SUPERSEDED;
            default -> ReactionStatus.UNKNOWN;
        };
    }

    private static Integer modelVersion(JsonNode node) {
        if (!present(node, "reactionModelVersion")) {
            return null;
        }
        JsonNode value = node.path("reactionModelVersion");
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 1,
                "Invalid reaction model version");
        return value.intValue();
    }

    private static BigDecimal unidentifiedActionsRatio(JsonNode node) {
        if (!present(node, "unidentifiedActionsRatio")) {
            return null;
        }
        BigDecimal ratio = number(node, "unidentifiedActionsRatio");
        require(ratio.signum() >= 0 && ratio.compareTo(BigDecimal.ONE) <= 0, "Invalid unidentified actions ratio");
        return ratio;
    }

    private static ObservedInterface observedInterface(JsonNode node, Identity identity, String type) {
        String interfaceType = text(node, "interfaceType");
        String fqn = text(node, "fqn");
        return switch (type) {
            case MESSAGE -> new ObservedMessage(identity, interfaceType, fqn,
                    text(node, "messageType"), optionalText(node, "variant"));
            case "HTTP_OPERATION" -> new ObservedHttpOperation(identity, interfaceType, fqn,
                    text(node, "provider"), text(node, "method"), text(node, "path"));
            default -> new ObservedGenericInterface(identity, type, interfaceType, fqn);
        };
    }

    private static ObservedReactions observations(String format, List<ObservedInterface> interfaces,
                                                  List<ObservedReaction> reactions, List<Edge> edges) {
        var triggers = new ArrayList<ObservedTrigger>();
        var actions = new ArrayList<ObservedAction>();
        var predecessors = new ArrayList<ObservedPredecessor>();
        for (Edge edge : edges) {
            switch (edge.edgeType()) {
                case TRIGGER -> triggers.add(new ObservedTrigger(edge.source(), edge.target()));
                case ACTION -> actions.add(new ObservedAction(edge.source(), edge.target()));
                case "PREDECESSOR" -> predecessors.add(new ObservedPredecessor(edge.source(), edge.target()));
                default -> throw new IllegalArgumentException("Unsupported edge type");
            }
        }
        return new ObservedReactions(format, interfaces, reactions, triggers, actions, predecessors);
    }

    /** Only the adapter reads the upstream's edge names and endpoint fields. */
    private record Edge(String edgeType, Identity source, Identity target) {}

    private static Edge v2Edge(JsonNode edge, String type, Map<String, Identity> identities) {
        Identity source = identities.get(text(edge, "sourceId"));
        Identity target = identities.get(text(edge, "targetId"));
        require(source != null && target != null, "An edge refers to a missing node");
        String sourceKind = type.equals(TRIGGER) ? INTERFACE : REACTION;
        String targetKind = type.equals(ACTION) ? INTERFACE : REACTION;
        require(source.nodeType().equals(sourceKind) && target.nodeType().equals(targetKind), "Invalid edge direction");
        return new Edge(type, source, target);
    }

    private static String owner(JsonNode node) {
        String system = optionalText(node, "system");
        return system == null || system.isBlank() ? null : system;
    }

    private static boolean present(JsonNode node, String field) {
        return !node.path(field).isMissingNode() && !node.path(field).isNull();
    }

    private static BigDecimal number(JsonNode node, String field) {
        require(node.path(field).isNumber(), "Missing numeric " + field);
        return node.path(field).decimalValue();
    }

    private static String key(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber(), "A V1 identity needs an integer");
        return value.asString();
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        require(value != null && !value.isBlank(), "Missing " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isMissingNode() || value.isNull() || value.isString(), "Invalid " + field);
        return value.isString() ? value.stringValue() : null;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
