package ch.admin.bit.jeap.doc.domain.architecture;

import java.math.BigDecimal;
import java.util.List;

/** A complete observed network, independent of its transport and rendering. */
public record ReactionNetwork(String identityScheme, List<Node> nodes, List<Edge> edges) {
    public ReactionNetwork {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public ReactionNetwork(List<Node> nodes, List<Edge> edges) {
        this("observer-v1", nodes, edges);
    }

    /** V2 interfaces retain their identity when their representation becomes more specific. */
    public record Identity(String nodeType, String key) {}

    public record Node(Identity identity, String nodeType, InterfaceDetails interfaceDetails,
                       ReactionDetails reaction) {
        /** Legacy observations do not supply interface semantics or reaction metadata. */
        public Node(Identity identity, String messageType, String variant, String component, BigDecimal median) {
            this(identity, identity.nodeType(), messageType == null ? null :
                    new InterfaceDetails(null, null, new Message(messageType, variant), null),
                    component == null ? null : new ReactionDetails(null, component, median,
                            ReactionStatus.UNKNOWN, null, null, null));
        }
    }

    public record InterfaceDetails(String interfaceType, String fqn, Message message, HttpOperation http) {}
    public record Message(String messageType, String variant) {}
    public record HttpOperation(String provider, String method, String path) {}
    public enum ReactionStatus { ACTIVE, SUPERSEDED, UNKNOWN }
    public record ReactionDetails(String system, String component, BigDecimal median, ReactionStatus status,
                                  Integer reactionModelVersion, BigDecimal unidentifiedActionsRatio,
                                  String groupKey) {}

    public record Edge(String edgeType, Identity source, Identity target) {
        /** History is retained, but never traversed as an observed execution flow. */
        public boolean participatesInFlow() {
            return edgeType.equals("TRIGGER") || edgeType.equals("ACTION");
        }
    }
}
