package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reading a stored graph, against the payload the reaction observer actually serves.
 * <p>
 * The nodes and edges are polymorphic on a type field, and this is where that is read - by hand, so that a
 * kind of node a later observer adds costs one node rather than the whole graph.
 */
class ReactionObserverGraphContentTest {

    private final ReactionObserverGraphContent content =
            new ReactionObserverGraphContent(JsonMapper.builder().build());

    /** The shape of GraphDto: MESSAGE and REACTION nodes, TRIGGER and ACTION edges. */
    @Test
    void read_thenTheNodesAndEdgesComeBackAsTheObserverServedThem() {
        ObservedReactions observed = content.read(graph("""
                {"nodes":[{"nodeType":"MESSAGE","id":1,"messageType":"OrdersPaymentAcceptedEvent",
                           "variant":null},
                          {"nodeType":"REACTION","id":2,"component":"shipping-dispatch-scs","median":12},
                          {"nodeType":"MESSAGE","id":3,"messageType":"ShippingDispatchedEvent",
                           "variant":"legacy"}],
                 "edges":[{"edgeType":"TRIGGER","sourceId":1,"sourceNodeType":"MESSAGE","targetReactionId":2,
                           "median":12},
                          {"edgeType":"ACTION","sourceReactionId":2,"targetId":3,
                           "targetNodeType":"MESSAGE"}]}
                """));

        assertThat(observed.messages()).extracting(ObservedReactions.ObservedMessage::messageType,
                        ObservedReactions.ObservedMessage::variant)
                .containsExactly(tuple("OrdersPaymentAcceptedEvent", null),
                        tuple("ShippingDispatchedEvent", "legacy"));
        assertThat(observed.reactions()).singleElement()
                .satisfies(reaction -> {
                    assertThat(reaction.id()).isEqualTo(2);
                    assertThat(reaction.component()).isEqualTo("shipping-dispatch-scs");
                    assertThat(reaction.median()).isEqualTo(12);
                });
        assertThat(observed.triggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.messageId()).isEqualTo(1);
            assertThat(trigger.reactionId()).isEqualTo(2);
            assertThat(trigger.median()).isEqualTo(12);
        });
        assertThat(observed.actions()).singleElement().satisfies(action -> {
            assertThat(action.reactionId()).isEqualTo(2);
            assertThat(action.messageId()).isEqualTo(3);
        });
    }

    /** A trigger the observer kept no number for is drawn, thin, rather than left out. */
    @Test
    void read_whenATriggerHasNoMedian_thenItIsStillATrigger() {
        ObservedReactions observed = content.read(graph("""
                {"nodes":[{"nodeType":"MESSAGE","id":1,"messageType":"SomeEvent","variant":null},
                          {"nodeType":"REACTION","id":2,"component":"a-service"}],
                 "edges":[{"edgeType":"TRIGGER","sourceId":1,"targetReactionId":2}]}
                """));

        assertThat(observed.triggers()).singleElement()
                .satisfies(trigger -> assertThat(trigger.median()).isNull());
    }

    /**
     * <b>The number is read off the reaction as well as off its trigger.</b> The observer counts per
     * reaction, so a reaction no message triggered carries one although no edge does - which is the case the
     * node's field exists for.
     */
    @Test
    void read_whenAReactionHasNoTrigger_thenItsOwnMedianIsStillRead() {
        ObservedReactions observed = content.read(graph("""
                {"nodes":[{"nodeType":"REACTION","id":2,"component":"a-service","median":9252},
                          {"nodeType":"MESSAGE","id":3,"messageType":"SomethingHappenedEvent",
                           "variant":null}],
                 "edges":[{"edgeType":"ACTION","sourceReactionId":2,"targetId":3,
                           "targetNodeType":"MESSAGE"}]}
                """));

        assertThat(observed.triggers()).isEmpty();
        assertThat(observed.reactions()).singleElement()
                .satisfies(reaction -> assertThat(reaction.median()).isEqualTo(9252));
    }

    /**
     * A graph an older observer stored carries no number on its nodes, and reading it must not invent one:
     * the import keeps the bytes as they arrived, so those graphs outlive the upgrade.
     */
    @Test
    void read_whenAReactionHasNoMedian_thenItCarriesNone() {
        ObservedReactions observed = content.read(graph("""
                {"nodes":[{"nodeType":"REACTION","id":2,"component":"a-service"}],"edges":[]}
                """));

        assertThat(observed.reactions()).singleElement()
                .satisfies(reaction -> assertThat(reaction.median()).isNull());
    }

    /**
     * <b>A kind of node this version does not know costs that node and nothing else.</b> Binding the
     * polymorphism instead would refuse the whole graph, and the observer is a service with a release train of
     * its own.
     */
    @Test
    void read_whenTheObserverAddsAKindOfNode_thenTheRestOfTheGraphIsStillDrawn() {
        ObservedReactions observed = content.read(graph("""
                {"nodes":[{"nodeType":"SOMETHING_NEW","id":9},
                          {"nodeType":"REACTION","id":2,"component":"a-service"}],
                 "edges":[{"edgeType":"SOMETHING_NEW","sourceId":9}]}
                """));

        assertThat(observed.reactions()).hasSize(1);
        assertThat(observed.messages()).isEmpty();
        assertThat(observed.triggers()).isEmpty();
    }

    /** An empty graph reads as empty, which is a page that is not written rather than one that is blank. */
    @Test
    void read_whenTheGraphIsEmpty_thenSoIsTheView() {
        assertThat(content.read(graph("{\"nodes\":[],\"edges\":[]}")).isEmpty()).isTrue();
    }

    /**
     * One unreadable graph costs one page. There is no {@code try} around the generation of a site, so
     * throwing here would cost every system of the environment its documentation.
     */
    @Test
    void read_whenTheBytesAreNotAGraph_thenItIsEmptyRatherThanAFailure() {
        assertThat(content.read(graph("<html>not a graph</html>")).isEmpty()).isTrue();
    }

    private static StoredReactionGraph graph(String json) {
        return new StoredReactionGraph("dev", ArchitectureImportKind.SYSTEM_REACTIONS, "Orders", "", null,
                "orders", "\"sha256:a\"", "fingerprint", json.getBytes(StandardCharsets.UTF_8), 1,
                Instant.parse("2026-09-09T08:00:00Z"));
    }
}
