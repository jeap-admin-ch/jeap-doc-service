package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.ReactionIds;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DOT a runtime view is drawn from.
 * <p>
 * What is asserted here is the source, because the browser cannot tell a diagram that is wrong from one that
 * is right: a node without an id still draws, and a link with the wrong prefix still looks like a link. What a
 * browser is for is {@code ReactionGraphBrowserIT}.
 */
class GraphVizViewsTest {

    private static final Instant WHEN = Instant.parse("2026-09-09T08:00:00Z");

    /**
     * <b>Every node carries the id a deep link addresses.</b> The plugin resolves {@code highlight-node} by
     * SVG id before it falls back to matching text, so without this a link lands on whatever happens to read
     * the same.
     */
    @Test
    void reactions_thenEveryNodeCarriesItsOwnId() {
        String dot = draw(observed());

        assertThat(dot).contains("\"MESSAGE-1\" [id=\"MESSAGE-1\"")
                .contains("\"REACTION-2\" [id=\"REACTION-2\"");
    }

    /**
     * A link inside a fence is rewritten by nothing - not by the remark plugin that adds the environment, not
     * by Docusaurus which adds the base URL - so the source carries both already.
     */
    @Test
    void reactions_thenALinkCarriesTheEnvironmentPrefixItself() {
        String dot = draw(observed());

        assertThat(dot).contains("URL=\"/docs/dev/systems/orders/system-architecture/building-block-view/"
                                 + "events/orders-payment-accepted-event/#graph?highlight-node=MESSAGE-1\"")
                .contains("#graph?highlight-node=REACTION-2");
    }

    /** A variant is a second line of the label, or two variants of one type would be one node twice. */
    @Test
    void reactions_whenAMessageHasAVariant_thenItIsOnASecondLine() {
        String dot = draw(new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", "express")),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 3)), List.of()));

        assertThat(dot).contains("label=\"OrdersPaymentAccepted\\n[express]\"");
    }

    /**
     * <b>A name is a string literal, and DOT reads one.</b> A quote in a component name would end the label
     * and turn the rest of the graph into syntax - which draws nothing at all, on a page that still builds.
     */
    @Test
    void reactions_whenANameCarriesAQuote_thenItIsEscapedRatherThanEndingTheLabel() {
        String dot = draw(new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "Orders\"Odd\"Event", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "orders\\intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of()));

        assertThat(dot).contains("label=\"Orders\\\"Odd\\\"\"")
                .contains("label=\"orders\\\\intake\"");
        assertThat(dot.chars().filter(character -> character == '"').count() % 2)
                .describedAs("the quotes of the source pair up").isZero();
    }

    /**
     * A message type whose own name contains a slash is a name, not a path. It is the case that makes the
     * observer's variant keys ambiguous, and it must not become a broken link either.
     */
    @Test
    void reactions_whenAMessageTypeContainsASlash_thenItIsDrawnAsANameNotAPath() {
        String dot = draw(new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "orders/OddEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of()));

        assertThat(dot).contains("label=\"orders/Odd\"");
    }

    /** A message this landscape does not document is drawn and is not offered as a link. */
    @Test
    void reactions_whenNothingDocumentsAMessage_thenTheNodeIsNotALink() {
        String dot = draw(new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "NobodyDocumentsThisEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "a-component-nobody-documents", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of()));

        assertThat(dot).doesNotContain("URL=")
                .contains("NobodyDocumentsThis");
    }

    /** A busier trigger is a thicker arrow, which is the one thing the picture says about frequency. */
    @Test
    void reactions_thenATriggersWidthComesFromItsMedian() {
        assertThat(draw(observed())).contains("[penwidth=2]");
    }

    /**
     * A message of another system is outlined, and nothing else is coloured at all.
     * <p>
     * <b>Outlined rather than filled</b>, and that is not a matter of taste: the site's diagram plugin gives a
     * linked node's text the theme's link colour and retargets every other label at the page's text colour, so
     * in dark mode the label of a filled node is light on light. A stroke sits on the background instead. It
     * was a browser that said so, and this is what keeps it said.
     */
    @Test
    void reactions_thenTheOnlyColourIsTheOneThatMeansSomething() {
        String dot = draw(observed());

        assertThat(dot).contains("color=\"#4a90d9\" penwidth=2")
                .doesNotContain("fillcolor").doesNotContain("style=filled");
        assertThat(dot.lines().filter(line -> line.contains("color=")).count())
                .describedAs("one coloured thing on the graph").isEqualTo(1);
    }

    /**
     * <b>A reaction is not a link where this run wrote no page for the component.</b> A URL inside a fence is
     * checked by nothing - Docusaurus verifies Markdown links and never looks in a code block - so a link to a
     * page nobody wrote builds cleanly and answers 404 to the reader who follows it. A browser found this one.
     */
    @Test
    void reactions_whenTheComponentHasNoPageOfItsOwn_thenItsReactionIsNotALink() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                // A component the landscape documents, but that this run wrote no runtime view for.
                List.of(new ObservedReactions.ObservedReaction(2, "orders-risk", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of());

        assertThat(draw(observed)).doesNotContain("component-reactions");
    }

    /**
     * <b>A reaction of another system's component is a link all the same.</b> The graphs handed to a template
     * are one system's, so asking them whether a component has a runtime view answers no for every component
     * of every other system - and a reaction on a system's graph is regularly one of those. What decides it
     * is the environment's components, not this pass's.
     */
    @Test
    void reactions_whenTheComponentBelongsToAnotherSystem_thenItsReactionIsStillALink() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of());

        assertThat(draw(observed))
                .contains("/systems/shipping/system-architecture/building-block-view/components/"
                          + "shipping-dispatch/component-architecture/runtime-view/component-reactions/");
    }

    /** And not where that component has no runtime view anywhere on the site. */
    @Test
    void reactions_whenNoEnvironmentHasAGraphOfTheComponent_thenItsReactionIsNotALink() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "shipping-dispatch", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 1)), List.of());

        assertThat(draw(observed, null, Set.of("orders-intake"), ""))
                .doesNotContain("component-reactions");
    }

    /**
     * <b>A node id becomes a DOM id</b>, and a message type with variants draws a diagram per variant on one
     * page. Without a prefix per graph the page carries the same id twice and a deep link lands on whichever
     * the browser finds first.
     */
    @Test
    void reactions_whenThePageCarriesSeveralGraphs_thenEachOnesIdsAreItsOwn() {
        String dot = draw(observed(), null, Set.of("orders-intake"), ReactionIds.prefixOf("legacy"));

        assertThat(dot).contains("\"legacy-MESSAGE-1\" [id=\"legacy-MESSAGE-1\"")
                .contains("\"legacy-REACTION-2\" [id=\"legacy-REACTION-2\"")
                .describedAs("and the graph itself is named apart too")
                .contains("digraph \"legacy-reactions\"")
                .describedAs("the edges follow the nodes")
                .contains("\"legacy-MESSAGE-1\" -> \"legacy-REACTION-2\"");
    }

    /**
     * <b>A message node focuses the message on the page it opens.</b> A message page draws a diagram per
     * variant, so a link without the fragment leaves the reader at the top of a page of many graphs to find
     * the one they came from - which is what the arch repo's Confluence graphs never did.
     */
    @Test
    void reactions_thenAMessageNodeLinksToItsPageFocusedOnItself() {
        String dot = draw(observed());

        assertThat(dot).contains("/systems/orders/system-architecture/building-block-view/events/"
                                 + "orders-payment-accepted-event/#graph?highlight-node=MESSAGE-1")
                .describedAs("a message of another system as much as one of this one")
                .contains("/systems/shipping/system-architecture/building-block-view/events/"
                          + "shipping-dispatched-event/#graph?highlight-node=MESSAGE-3");
    }

    /**
     * And the node addressed is the one on <b>that variant's</b> diagram, whose id carries the variant's
     * prefix. The linking pass holds one system's graphs and knows nothing of the target page, so the prefix
     * has to be a function of what the node itself carries.
     */
    @Test
    void reactions_whenAMessageHasAVariant_thenTheFragmentNamesThatVariantsNode() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                        new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", "NES_Risk")),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)),
                List.of(new ObservedReactions.ObservedAction(2, 3)));

        assertThat(draw(observed))
                .contains("shipping-dispatched-event/#graph?highlight-node=nes-risk-MESSAGE-3");
    }

    /**
     * <b>A type whose page draws no graph is linked with the fragment all the same.</b> The hash then names a
     * node no diagram on that page carries, the plugin does nothing with it, and the reader lands on the page -
     * which is what a link without a fragment does anyway. Suppressing it would need an index of every message
     * type of the environment that has a graph, for a case that costs nothing.
     */
    @Test
    void reactions_whenTheMessagesOwnPageDrawsNoGraph_thenTheFragmentIsWrittenAnyway() {
        // The run carries no graph of any message type at all, which is what a type the observer never saw
        // react has - and the link is written with its fragment regardless.
        assertThat(draw(observed())).contains("orders-payment-accepted-event/#graph?highlight-node=MESSAGE-1");
    }

    /** And on the component's own page, its reactions link nowhere: the reader is already there. */
    @Test
    void reactions_whenItIsTheComponentsOwnPage_thenItsReactionsAreNotLinks() {
        assertThat(draw(observed(), "orders-intake")).doesNotContain("component-reactions");
        assertThat(draw(observed(), "orders-intake"))
                .describedAs("the message still links to its page").contains("/events/");
    }

    private static String draw(ObservedReactions observed) {
        return draw(observed, null);
    }

    private static String draw(ObservedReactions observed, String onItsOwnPage) {
        // The environment has a runtime view of orders-intake and of the other system's dispatch component,
        // which is what a link into either depends on.
        return draw(observed, onItsOwnPage, Set.of("orders-intake", "shipping-dispatch"), "");
    }

    private static String draw(ObservedReactions observed, String onItsOwnPage,
                               Set<String> componentsWithAGraph, String idPrefix) {
        DocumentedComponent intake = new DocumentedComponent("orders-intake", "orders-intake", null,
                ComponentType.SELF_CONTAINED_SYSTEM, null, null, null, List.of(), null, null, null);
        DocumentedComponent risk = new DocumentedComponent("orders-risk", "orders-risk", null,
                ComponentType.SELF_CONTAINED_SYSTEM, null, null, null, List.of(), null, null, null);
        DocumentedComponent dispatch = new DocumentedComponent("shipping-dispatch", "shipping-dispatch", null,
                ComponentType.SELF_CONTAINED_SYSTEM, null, null, null, List.of(), null, null, null);
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", "", List.of(), null,
                List.of(intake, risk), List.of(), List.of(new DocumentedMessage("OrdersPaymentAcceptedEvent",
                        "orders-payment-accepted-event", MessageKind.EVENT, null, null, null, null, null,
                        List.of(), List.of())));
        DocumentedSystem shipping = new DocumentedSystem("shipping", "shipping", "", List.of(), null,
                List.of(dispatch), List.of(), List.of(new DocumentedMessage("ShippingDispatchedEvent",
                        "shipping-dispatched-event", MessageKind.EVENT, null, null, null, null, null,
                        List.of(), List.of())));
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, shipping));
        ReactionView view = ReactionView.of(observed, model, orders);
        // This system's own graphs, as a real run hands them over: one system at a time.
        GenerationContext context = new GenerationContext(model, "dev", "https://archrepo", WHEN, WHEN,
                new DiagramLimits(100, 4, 40, 100, 200), "/docs/dev/", null,
                ReactionViews.of(WHEN, view, Map.of("orders-intake", view), Map.of(),
                        componentsWithAGraph));
        return GraphVizViews.reactions(view, context, onItsOwnPage, idPrefix);
    }

    /** One message of this system, one reaction of its component, one message of another system in answer. */
    private static ObservedReactions observed() {
        return new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null),
                        new ObservedReactions.ObservedMessage(3, "ShippingDispatchedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)),
                List.of(new ObservedReactions.ObservedAction(2, 3)));
    }
}
