package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
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
        assertThat(graph.interfaces()).hasSize(4);
        assertThat(graph.reactions()).hasSize(2);
        assertThat(graph.messages()).containsExactly(new ObservedMessage(new Identity("INTERFACE", "I1"),
                "command", "orders.Place/priority", "Place", "priority"));
        assertThat(graph.interfaces().get(1)).isEqualTo(new ObservedHttpOperation(new Identity("INTERFACE", "I2"),
                "http_request", "catalog.GET./items", "catalog", "GET", "/items"));
        assertThat(graph.interfaces().get(2).interfaceType()).isEqualTo("timer");
        assertThat(graph.interfaces().get(3)).isEqualTo(new ObservedGenericInterface(new Identity("INTERFACE", "opaque:/key"),
                "FUTURE_INTERFACE", "future", "future.name"));
        var reaction = graph.reactions().getFirst();
        assertThat(reaction.system()).isEqualTo("orders");
        assertThat(reaction.median()).isZero();
        assertThat(reaction.status()).isEqualTo(ObservedReactions.ReactionStatus.ACTIVE);
        assertThat(reaction.reactionModelVersion()).isEqualTo(7);
        assertThat(reaction.unidentifiedActionsRatio()).isEqualByComparingTo("0.25");
        assertThat(reaction.groupKey()).isEqualTo("checkout");
        assertThat(graph.reactions().get(1).status()).isEqualTo(ReactionStatus.SUPERSEDED);
        assertThat(graph.triggers()).hasSize(2);
        assertThat(graph.actions()).hasSize(2);
        assertThat(graph.predecessors()).containsExactly(new ObservedPredecessor(
                new Identity("REACTION", "R1"), new Identity("REACTION", "R2")));

    }

    @Test
    void interfaceIdentityDoesNotChangeWhenItsRepresentationBecomesSpecific() {
        var typed = read(V2).interfaces().getFirst().identity();
        var generic = read(V2.replace("\"nodeType\":\"MESSAGE\"", "\"nodeType\":\"INTERFACE\"")).interfaces().getFirst().identity();
        assertThat(generic).isEqualTo(typed);
    }

    @Test
    void unknownStatusIsUnknownAndMissingMetadataIsNotInvented() {
        var graph = read(V2.replace("\"ACTIVE\"", "\"FUTURE_STATUS\"")
                .replace(",\"reactionModelVersion\":7,\"unidentifiedActionsRatio\":0.25,\"groupKey\":\"checkout\"", ""));
        assertThat(graph.reactions().getFirst()).isEqualTo(new ObservedReaction(new Identity("REACTION", "R1"), "orders", "gateway",
                java.math.BigDecimal.ZERO, ObservedReactions.ReactionStatus.UNKNOWN, null, null, null));
    }

    @Test
    void storedSnapshotsRequireAnExplicitSupportedFormatEnvelope() {
        for (String source : java.util.List.of(
                "{\"nodes\":[],\"edges\":[]}",
                "{\"sourceFormat\":\"unknown\",\"graph\":{\"nodes\":[],\"edges\":[]}}")) {
            var stored = json.readTree(source);
            assertThatThrownBy(() -> FullReactionGraphReader.read(stored))
                    .isInstanceOf(IllegalArgumentException.class);
        }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTwoDistinctTriggersOfOneReaction(boolean v2) {
        var graph = json.readTree(graphWithTwoTriggers(v2, false));
        assertThatThrownBy(() -> FullReactionGraphReader.read(graph, v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A reaction cannot have multiple triggers");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allowsOneTriggerToTriggerDifferentReactions(boolean v2) {
        var graph = FullReactionGraphReader.read(json.readTree(graphWithTwoTriggers(v2, true)), v2);
        assertThat(graph.triggers()).hasSize(2);
        assertThat(graph.triggers().get(0).interfaceId()).isEqualTo(graph.triggers().get(1).interfaceId());
        assertThat(graph.triggers().get(0).reactionId()).isNotEqualTo(graph.triggers().get(1).reactionId());
    }

    private static String graphWithTwoTriggers(boolean v2, boolean differentReactions) {
        String graph = """
                {"nodes":[
                  {"nodeType":"MESSAGE","id":1,"interfaceType":"event","fqn":"A","messageType":"A"},
                  {"nodeType":"MESSAGE","id":2,"interfaceType":"event","fqn":"B","messageType":"B"},
                  {"nodeType":"REACTION","id":3,"component":"service","median":1},
                  {"nodeType":"REACTION","id":4,"component":"service","median":2}],
                 "edges":[
                  {"edgeType":"TRIGGER","sourceId":1,"targetReactionId":3},
                  {"edgeType":"TRIGGER","sourceId":%d,"targetReactionId":%d}]}
                """.formatted(differentReactions ? 1 : 2, differentReactions ? 4 : 3);
        return v2 ? graph.replace("targetReactionId", "targetId")
                .replaceAll("(\"(?:id|sourceId|targetId)\":)([0-9]+)", "$1\"$2\"") : graph;
    }

    private ObservedReactions read(String source) {
        return FullReactionGraphReader.read(json.readTree(source), true);
    }
}
