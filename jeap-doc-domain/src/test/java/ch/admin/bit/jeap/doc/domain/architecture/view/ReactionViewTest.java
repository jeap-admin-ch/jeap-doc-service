package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a runtime view draws, decided before anything knows it will be GraphViz.
 */
class ReactionViewTest {

    private static final DocumentedSystem ORDERS = system("Orders", "orders",
            List.of("orders-payment-scs", "orders-basket-scs"), List.of("OrdersPaymentAcceptedEvent"));
    private static final DocumentedSystem SHIPPING = system("Shipping", "shipping",
            List.of("shipping-dispatch-scs"), List.of("ShippingDispatchedEvent"));
    private static final ArchitectureModel MODEL = ArchitectureModel.of(List.of(ORDERS, SHIPPING));

    @Test
    void of_whenNothingWasObserved_thenTheViewIsEmpty() {
        assertThat(ReactionView.of(ObservedReactions.empty(), MODEL, ORDERS).isEmpty()).isTrue();
    }

    /** The suffix every message type carries says nothing on a graph, and the same goes for a component's. */
    @Test
    void of_thenTheLabelsLoseTheSuffixEveryNameCarries() {
        ReactionView view = ReactionView.of(observed(), MODEL, ORDERS);

        assertThat(view.messages()).extracting(ReactionView.MessageNode::label)
                .contains("OrdersPaymentAccepted", "ShippingDispatched");
        assertThat(view.clusters()).extracting(ReactionView.ReactionCluster::label)
                .containsExactly("shipping-dispatch");
    }

    /**
     * A message of another system is drawn as one, so a reader can tell what this system answers from what it
     * publishes itself.
     */
    @Test
    void of_thenAMessageOfAnotherSystemIsMarkedAsSuch() {
        ReactionView view = ReactionView.of(observed(), MODEL, SHIPPING);

        assertThat(view.messages())
                .filteredOn(message -> message.name().equals("OrdersPaymentAcceptedEvent"))
                .singleElement()
                .satisfies(message -> assertThat(message.ofAnotherSystem())
                        .describedAs("Orders defines it, and this is Shipping's page").isTrue());
        assertThat(view.messages())
                .filteredOn(message -> message.name().equals("ShippingDispatchedEvent"))
                .singleElement()
                .satisfies(message -> assertThat(message.ofAnotherSystem()).isFalse());
    }

    /** A page this run does not write is not offered as a link, which is the context views' rule too. */
    @Test
    void of_whenTheLandscapeDoesNotDocumentAMessage_thenItIsNotALink() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "SomethingNobodyDocumentsEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "a-component-nobody-documents", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 3)), List.of());

        ReactionView view = ReactionView.of(observed, MODEL, ORDERS);

        assertThat(view.messages()).singleElement()
                .satisfies(message -> assertThat(message.isLinkable()).isFalse());
        assertThat(view.clusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.isLinkable()).isFalse());
    }

    /** A component two systems both have cannot be linked: the link would have to guess which page. */
    @Test
    void of_whenTwoSystemsHaveTheComponent_thenItIsNotALink() {
        ArchitectureModel ambiguous = ArchitectureModel.of(List.of(
                system("Orders", "orders", List.of("gateway"), List.of()),
                system("Shipping", "shipping", List.of("gateway"), List.of())));
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "SomeEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "gateway", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, null)), List.of());

        assertThat(ReactionView.of(observed, ambiguous, null).clusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.isLinkable()).isFalse());
    }

    /**
     * Reactions of one component to one message are one box. A component that reacts to the same message
     * several times is what makes a busy graph unreadable otherwise.
     */
    @Test
    void of_thenTheReactionsOfOneComponentToOneTriggerAreOneCluster() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", null),
                        new ObservedReactions.ObservedReaction(3, "shipping-dispatch-scs", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 4),
                        new ObservedReactions.ObservedTrigger(1, 3, 4)), List.of());

        ReactionView view = ReactionView.of(observed, MODEL, ORDERS);

        assertThat(view.clusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.reactions())
                        .extracting(ReactionView.ReactionNode::label).containsExactly("2", "3"));
    }

    /** A busier trigger is a thicker arrow, by the number of digits rather than by the number itself. */
    @Test
    void of_thenATriggersWeightGrowsWithItsMedian() {
        assertThat(weightOf(null)).isEqualTo(1);
        assertThat(weightOf(9)).isEqualTo(1);
        assertThat(weightOf(10)).isEqualTo(2);
        assertThat(weightOf(99)).isEqualTo(2);
        assertThat(weightOf(100)).isEqualTo(3);
        assertThat(weightOf(100_000)).isEqualTo(3);
    }

    /**
     * The table is the complete list and the searchable one: it says what the diagram says, in words, for a
     * reader whose browser is searching the page and for a graph the plugin refuses to draw.
     */
    @Test
    void of_thenEveryReactionIsARowWithWhatItPublishedInAnswer() {
        ReactionView view = ReactionView.of(observed(), MODEL, ORDERS);

        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.trigger()).isEqualTo("OrdersPaymentAcceptedEvent");
            assertThat(row.component()).isEqualTo("shipping-dispatch-scs");
            assertThat(row.answers()).containsExactly("ShippingDispatchedEvent");
            assertThat(row.median()).isEqualTo(12);
        });
    }

    /**
     * <b>The variant is what tells two rows apart.</b> Four reactions to four variants of one message type
     * are four distinct nodes on the diagram and were four identical rows under it.
     */
    @Test
    void of_whenAMessageHasAVariant_thenTheRowNamesItBesideTheType() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", "nets-notice"),
                        new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", "by-road")),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)),
                List.of(new ObservedReactions.ObservedAction(2, 3)));

        ReactionView view = ReactionView.of(observed, MODEL, ORDERS);

        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.trigger()).isEqualTo("OrdersPaymentAcceptedEvent [nets-notice]");
            assertThat(row.answers()).containsExactly("ShippingDispatchedEvent [by-road]");
        });
    }

    /** A reaction the observer holds without a trigger is still drawn, or a component vanishes from it. */
    @Test
    void of_whenAReactionHasNoTrigger_thenItIsStillDrawn() {
        ObservedReactions observed = new ObservedReactions(List.of(),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-basket-scs", null)), List.of(), List.of());

        ReactionView view = ReactionView.of(observed, MODEL, ORDERS);

        assertThat(view.isEmpty()).isFalse();
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.trigger()).isNull());
    }

    /**
     * <b>A node with no name is left out rather than drawn.</b> The reader of the observer's payload
     * deliberately tolerates a field that is absent or is not a string, and a label of null further down is a
     * whole part's site build failing on one bad node.
     */
    @Test
    void of_whenANodeCarriesNoName_thenItAndItsEdgesAreLeftOut() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                        new ObservedReactions.ObservedMessage(3, null, null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", null),
                        new ObservedReactions.ObservedReaction(4, " ", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12),
                        new ObservedReactions.ObservedTrigger(1, 4, 7)),
                List.of(new ObservedReactions.ObservedAction(2, 3)));

        ReactionView view = ReactionView.of(observed, MODEL, ORDERS);

        assertThat(view.messages()).extracting(ReactionView.MessageNode::name)
                .containsExactly("OrdersPaymentAcceptedEvent");
        assertThat(view.clusters()).extracting(ReactionView.ReactionCluster::component)
                .containsExactly("shipping-dispatch-scs");
        assertThat(view.triggers()).extracting(ReactionView.TriggerEdge::reactionId).containsExactly(2L);
        assertThat(view.actions()).describedAs("the message it answered is gone with it").isEmpty();
    }

    /**
     * <b>A reaction nothing triggered is still counted.</b> The observer counts per reaction, and a reaction
     * no message set off has no trigger edge to carry the number - so the row read <i>unknown</i> for a
     * reaction the observer had a number for, until the number arrived on the node.
     */
    @Test
    void of_whenAReactionHasNoTrigger_thenItsRowShowsTheMedianOfItsNode() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", 9252)),
                List.of(), List.of(new ObservedReactions.ObservedAction(2, 3)));

        assertThat(ReactionView.of(observed, MODEL, ORDERS).rows()).singleElement().satisfies(row -> {
            assertThat(row.trigger()).isNull();
            assertThat(row.median()).isEqualTo(9252);
        });
    }

    /**
     * The node's number is the one shown where both carry one - they are the same number, and the node is
     * where the observer counts it.
     */
    @Test
    void of_whenTheNodeAndItsTriggerBothCarryAMedian_thenTheNodesIsShown() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", 12)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)), List.of());

        assertThat(ReactionView.of(observed, MODEL, ORDERS).rows()).singleElement()
                .satisfies(row -> assertThat(row.median()).isEqualTo(12));
    }

    /**
     * <b>A graph stored before the observer carried the number on the node</b> has it on the trigger edge
     * only, and still reads as it did - the import stores the observer's bytes as they arrived, so those
     * graphs outlive the upgrade.
     */
    @Test
    void of_whenOnlyTheTriggerCarriesAMedian_thenTheRowFallsBackToIt() {
        assertThat(ReactionView.of(observed(), MODEL, ORDERS).rows()).singleElement()
                .satisfies(row -> assertThat(row.median()).isEqualTo(12));
    }

    /** A graph of nothing but nameless nodes is a chapter that is not written, like an empty one. */
    @Test
    void of_whenEveryNodeCarriesNoName_thenTheViewIsEmpty() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, null, null)),
                List.of(new ObservedReactions.ObservedReaction(2, null, null)), List.of(), List.of());

        assertThat(ReactionView.of(observed, MODEL, ORDERS).isEmpty()).isTrue();
    }

    private static int weightOf(Integer median) {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "SomeEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-basket-scs", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, median)), List.of());
        return ReactionView.of(observed, MODEL, ORDERS).triggers().getFirst().weight();
    }

    /** One message accepted by Orders, one reaction of a Shipping component, one message published back. */
    private static ObservedReactions observed() {
        return new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                        new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch-scs", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)),
                List.of(new ObservedReactions.ObservedAction(2, 3)));
    }

    private static DocumentedSystem system(String name, String slug, List<String> components,
                                           List<String> messages) {
        return new DocumentedSystem(name, slug, "", List.of(), null,
                components.stream().map(ReactionViewTest::component).toList(), List.of(),
                messages.stream().map(ReactionViewTest::message).toList());
    }

    private static DocumentedComponent component(String name) {
        return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, null, null, null,
                List.of(), null, null, null);
    }

    private static DocumentedMessage message(String name) {
        return new DocumentedMessage(name, name.toLowerCase(Locale.ROOT), MessageKind.EVENT, null,
                null, null, null, null, List.of(), List.of());
    }
}
