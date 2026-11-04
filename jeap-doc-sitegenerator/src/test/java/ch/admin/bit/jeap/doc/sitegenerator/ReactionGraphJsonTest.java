package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactionGraphJsonTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void typedObservationsKeepTheExistingBrowserContractIncludingHistoryAndUnknownInterfaces() {
        var message = new ObservedMessage(new Identity("INTERFACE", "event/priority"), "event", "orders.Accepted/priority", "Accepted", "priority");
        var http = new ObservedHttpOperation(new Identity("INTERFACE", "http"), "http_request", "catalog.GET./items", "catalog", "GET", "/items");
        var generic = new ObservedGenericInterface(new Identity("INTERFACE", "unknown"), "FUTURE_INTERFACE", "future", "future.fqn");
        var reaction = new ObservedReaction(new Identity("REACTION", "R1"), "orders", "gateway", new BigDecimal("1.25"),
                ReactionStatus.ACTIVE, 2, new BigDecimal("0.25"), "checkout");
        var prior = new ObservedReaction(new Identity("REACTION", "R0"), null, "gateway", BigDecimal.ZERO,
                ReactionStatus.SUPERSEDED, 1, null, null);
        var observations = new ObservedReactions("observer-v2", List.of(message, http, generic), List.of(reaction, prior),
                List.of(new ObservedTrigger(message.identity(), reaction.identity())),
                List.of(new ObservedAction(reaction.identity(), http.identity()), new ObservedAction(prior.identity(), generic.identity())),
                List.of(new ObservedPredecessor(reaction.identity(), prior.identity())));

        var actual = json.readTree(json.writeValueAsBytes(ReactionGraphJson.of(observations)));

        assertThat(actual).isEqualTo(json.readTree("""
                {"identityScheme":"observer-v2","nodes":[
                  {"identity":{"nodeType":"INTERFACE","key":"event/priority"},"nodeType":"MESSAGE",
                   "interfaceDetails":{"interfaceType":"event","fqn":"orders.Accepted/priority",
                     "message":{"messageType":"Accepted","variant":"priority"},"http":null},"reaction":null},
                  {"identity":{"nodeType":"INTERFACE","key":"http"},"nodeType":"HTTP_OPERATION",
                   "interfaceDetails":{"interfaceType":"http_request","fqn":"catalog.GET./items","message":null,
                     "http":{"provider":"catalog","method":"GET","path":"/items"}},"reaction":null},
                  {"identity":{"nodeType":"INTERFACE","key":"unknown"},"nodeType":"FUTURE_INTERFACE",
                   "interfaceDetails":{"interfaceType":"future","fqn":"future.fqn","message":null,"http":null},"reaction":null},
                  {"identity":{"nodeType":"REACTION","key":"R1"},"nodeType":"REACTION","interfaceDetails":null,
                   "reaction":{"system":"orders","component":"gateway","median":1.25,"status":"ACTIVE",
                     "reactionModelVersion":2,"unidentifiedActionsRatio":0.25,"groupKey":"checkout"}},
                  {"identity":{"nodeType":"REACTION","key":"R0"},"nodeType":"REACTION","interfaceDetails":null,
                   "reaction":{"system":null,"component":"gateway","median":0,"status":"SUPERSEDED",
                     "reactionModelVersion":1,"unidentifiedActionsRatio":null,"groupKey":null}}
                ],"edges":[
                  {"edgeType":"TRIGGER","source":{"nodeType":"INTERFACE","key":"event/priority"},"target":{"nodeType":"REACTION","key":"R1"}},
                  {"edgeType":"ACTION","source":{"nodeType":"REACTION","key":"R1"},"target":{"nodeType":"INTERFACE","key":"http"}},
                  {"edgeType":"ACTION","source":{"nodeType":"REACTION","key":"R0"},"target":{"nodeType":"INTERFACE","key":"unknown"}},
                  {"edgeType":"PREDECESSOR","source":{"nodeType":"REACTION","key":"R1"},"target":{"nodeType":"REACTION","key":"R0"}}
                ]}
                """));
    }
}
