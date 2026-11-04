package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ReactionNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullReactionGraphReaderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String V2 = """
            {"nodes":[
              {"nodeType":"MESSAGE","id":"I1","interfaceType":"command","fqn":"orders.Place/priority","messageType":"Place","variant":"priority"},
              {"nodeType":"HTTP_OPERATION","id":"I2","interfaceType":"http_request","fqn":"catalog.GET./items","provider":"catalog","method":"GET","path":"/items"},
              {"nodeType":"INTERFACE","id":"I3","interfaceType":"timer","fqn":"nightly"},
              {"nodeType":"FUTURE_INTERFACE","id":"opaque:/key","interfaceType":"future","fqn":"future.name","extra":true},
              {"nodeType":"REACTION","id":"R1","system":"orders","component":"gateway","median":0,"status":"ACTIVE","reactionModelVersion":7,"unidentifiedActionsRatio":0.25,"groupKey":"checkout"},
              {"nodeType":"REACTION","id":"R2","system":"orders","component":"gateway","median":2,"status":"SUPERSEDED","reactionModelVersion":6,"unidentifiedActionsRatio":1}
            ],"edges":[
              {"edgeType":"TRIGGER","sourceId":"I1","targetId":"R1"},
              {"edgeType":"ACTION","sourceId":"R1","targetId":"I2"},
              {"edgeType":"TRIGGER","sourceId":"I3","targetId":"R2"},
              {"edgeType":"ACTION","sourceId":"R2","targetId":"opaque:/key"},
              {"edgeType":"PREDECESSOR","sourceId":"R1","targetId":"R2"},
              {"edgeType":"FUTURE_RELATIONSHIP","sourceId":"I1","targetId":"I3"}
            ]}
            """;

    @Test
    void retainsAllInterfaceKindsAndHistoryWithTheirMetadata() {
        var graph = read(V2);
        assertThat(graph.identityScheme()).isEqualTo("observer-v2");
        assertThat(graph.nodes()).hasSize(6);
        var message = graph.nodes().get(0);
        assertThat(message.identity()).isEqualTo(new ReactionNetwork.Identity("INTERFACE", "I1"));
        assertThat(message.interfaceDetails()).isEqualTo(new ReactionNetwork.InterfaceDetails("command", "orders.Place/priority",
                new ReactionNetwork.Message("Place", "priority"), null));
        assertThat(graph.nodes().get(1).interfaceDetails().http()).isEqualTo(new ReactionNetwork.HttpOperation("catalog", "GET", "/items"));
        assertThat(graph.nodes().get(2).interfaceDetails().interfaceType()).isEqualTo("timer");
        assertThat(graph.nodes().get(3).interfaceDetails().fqn()).isEqualTo("future.name");
        var reaction = graph.nodes().get(4).reaction();
        assertThat(reaction.system()).isEqualTo("orders");
        assertThat(reaction.median()).isZero();
        assertThat(reaction.status()).isEqualTo(ReactionNetwork.ReactionStatus.ACTIVE);
        assertThat(reaction.reactionModelVersion()).isEqualTo(7);
        assertThat(reaction.unidentifiedActionsRatio()).isEqualByComparingTo("0.25");
        assertThat(reaction.groupKey()).isEqualTo("checkout");
        assertThat(graph.nodes().get(5).reaction().status()).isEqualTo(ReactionNetwork.ReactionStatus.SUPERSEDED);
        assertThat(graph.edges()).hasSize(5);
        var predecessor = graph.edges().getLast();
        assertThat(predecessor.edgeType()).isEqualTo("PREDECESSOR");
        assertThat(predecessor.source().key()).isEqualTo("R1");
        assertThat(predecessor.target().key()).isEqualTo("R2");
        assertThat(predecessor.participatesInFlow()).isFalse();
        assertThat(graph.edges().stream().filter(ReactionNetwork.Edge::participatesInFlow)).hasSize(4);
    }

    @Test
    void interfaceIdentityDoesNotChangeWhenItsRepresentationBecomesSpecific() {
        var typed = read(V2).nodes().get(0).identity();
        var generic = read(V2.replace("\"nodeType\":\"MESSAGE\"", "\"nodeType\":\"INTERFACE\"")).nodes().get(0).identity();
        assertThat(generic).isEqualTo(typed);
    }

    @Test
    void unknownStatusIsUnknownAndMissingMetadataIsNotInvented() {
        var graph = read(V2.replace("\"ACTIVE\"", "\"FUTURE_STATUS\"")
                .replace(",\"reactionModelVersion\":7,\"unidentifiedActionsRatio\":0.25,\"groupKey\":\"checkout\"", ""));
        assertThat(graph.nodes().get(4).reaction()).isEqualTo(new ReactionNetwork.ReactionDetails("orders", "gateway",
                java.math.BigDecimal.ZERO, ReactionNetwork.ReactionStatus.UNKNOWN, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "\"0\""})
    void rejectsInvalidMedian(String median) {
        String invalidMedian = V2.replace("\"median\":0", "\"median\":" + median);
        assertThatThrownBy(() -> read(invalidMedian))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "1.01", "\"0.2\""})
    void rejectsInvalidRatio(String ratio) {
        String invalidRatio = V2.replace("\"unidentifiedActionsRatio\":0.25", "\"unidentifiedActionsRatio\":" + ratio);
        assertThatThrownBy(() -> read(invalidRatio))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateIdsDanglingEdgesAndWrongDirection() {
        String duplicateId = V2.replace("\"id\":\"I2\"", "\"id\":\"I1\"");
        assertThatThrownBy(() -> read(duplicateId)).isInstanceOf(IllegalArgumentException.class);
        String missingTarget = V2.replace("\"targetId\":\"I2\"", "\"targetId\":\"missing\"");
        assertThatThrownBy(() -> read(missingTarget)).isInstanceOf(IllegalArgumentException.class);
        String wrongDirection = V2.replace("\"targetId\":\"I2\"", "\"targetId\":\"R2\"");
        assertThatThrownBy(() -> read(wrongDirection)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oldUnwrappedSnapshotsRemainReadableWithUnknownV2Metadata() {
        var graph = FullReactionGraphReader.read(json.readTree("""
                {"nodes":[{"nodeType":"REACTION","id":9007199254740993,"component":"gateway","median":0}],"edges":[]}
                """));
        assertThat(graph.identityScheme()).isEqualTo("observer-v1");
        assertThat(graph.nodes().getFirst().identity().key()).isEqualTo("9007199254740993");
        assertThat(graph.nodes().getFirst().reaction()).isEqualTo(new ReactionNetwork.ReactionDetails(null, "gateway",
                java.math.BigDecimal.ZERO, ReactionNetwork.ReactionStatus.UNKNOWN, null, null, null));
    }

    private ReactionNetwork read(String source) {
        return FullReactionGraphReader.read(json.readTree(source), true);
    }
}
