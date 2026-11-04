package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** The browser's graph format. The domain keeps observations in their own typed collections. */
final class ReactionGraphJson {
    private ReactionGraphJson() {}

    static Graph of(ObservedReactions observations) {
        List<Node> nodes = new ArrayList<>();
        for (ObservedInterface observed : observations.interfaces()) {
            String type = switch (observed) {
                case ObservedMessage _ -> "MESSAGE";
                case ObservedHttpOperation _ -> "HTTP_OPERATION";
                case ObservedGenericInterface generic -> generic.representation();
            };
            Message message = observed instanceof ObservedMessage m ? new Message(m.messageType(), m.variant()) : null;
            HttpOperation http = observed instanceof ObservedHttpOperation h ? new HttpOperation(h.provider(), h.method(), h.path()) : null;
            nodes.add(new Node(observed.identity(), type,
                    new InterfaceDetails(observed.interfaceType(), observed.fqn(), message, http), null));
        }
        observations.reactions().stream()
                .map(reaction -> new Node(reaction.identity(), "REACTION", null,
                        new ReactionDetails(reaction.system(), reaction.component(), reaction.median(), reaction.status(),
                                reaction.reactionModelVersion(), reaction.unidentifiedActionsRatio(), reaction.groupKey())))
                .forEach(nodes::add);
        List<Edge> edges = new ArrayList<>();
        observations.triggers().forEach(trigger -> edges.add(new Edge("TRIGGER", trigger.interfaceId(), trigger.reactionId())));
        observations.actions().forEach(action -> edges.add(new Edge("ACTION", action.reactionId(), action.interfaceId())));
        observations.predecessors().forEach(history -> edges.add(new Edge("PREDECESSOR", history.successor(), history.predecessor())));
        return new Graph(observations.identityScheme(), nodes, edges);
    }

    record Graph(String identityScheme, List<Node> nodes, List<Edge> edges) {}
    record Node(Identity identity, String nodeType, InterfaceDetails interfaceDetails, ReactionDetails reaction) {}
    record InterfaceDetails(String interfaceType, String fqn, Message message, HttpOperation http) {}
    record Message(String messageType, String variant) {}
    record HttpOperation(String provider, String method, String path) {}
    record ReactionDetails(String system, String component, BigDecimal median, ReactionStatus status,
                           Integer reactionModelVersion, BigDecimal unidentifiedActionsRatio, String groupKey) {}
    record Edge(String edgeType, Identity source, Identity target) {}
}
