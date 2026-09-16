package ch.admin.bit.jeap.doc.reactionobserver;

import tools.jackson.databind.JsonNode;

/**
 * What the observer's payload calls its nodes and edges, and which of them this version can draw.
 * <p>
 * <b>One place, because two answers must not drift.</b> {@link ReactionObserverGraphContent} reads a stored
 * graph while a page is generated, and {@link ReactionObserverUpstream} counts the same nodes while storing it
 * so that a build can know whether a graph has a page without reading it. A counter that counted a node the
 * reader then drops is a link to a page nobody wrote.
 */
final class ReactionObserverNodes {

    static final String MESSAGE = "MESSAGE";
    static final String REACTION = "REACTION";
    static final String TRIGGER = "TRIGGER";
    static final String ACTION = "ACTION";

    private ReactionObserverNodes() {
    }

    /**
     * Whether a node is one this version draws: a kind it knows, named.
     * <p>
     * The name matters as much as the kind. A label is what a node is drawn as, so the view drops a node
     * without one - and a graph of nothing but nameless nodes draws nothing, whatever its node count says.
     */
    static boolean isDrawable(JsonNode node) {
        return switch (node.path("nodeType").asString("")) {
            case MESSAGE -> isNamed(node, "messageType");
            case REACTION -> isNamed(node, "component");
            default -> false;
        };
    }

    /** A string field with something in it, which is what a name has to be. */
    static boolean isNamed(JsonNode node, String field) {
        String value = text(node, field);
        return value != null && !value.isBlank();
    }

    /** A string field, or null where the observer sent none - a message with no variant sends {@code null}. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /** A number field, or null where the observer sent none - a reaction it counted nothing for sends none. */
    static Integer number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
