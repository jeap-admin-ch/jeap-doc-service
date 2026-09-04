package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PlantUML a diagram is written as.
 * <p>
 * A diagram that does not parse renders as an error box in the reader's browser, and the site build does not
 * notice. Nothing else checks this.
 */
class PlantUmlViewsTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-28T06:05:02Z");

    @Test
    void contextView_drawsTheSystemItsNeighboursAndTheArrowsBetweenThem() {
        ArchitectureModel model = landscape();
        SystemContext context = SystemContext.of(model, orders(), 60);

        String uml = PlantUmlViews.contextView(context, generation(model)).source();

        assertThat(uml).startsWith("@startuml").endsWith("@enduml");
        assertThat(uml).contains("left to right direction");
        assertThat(uml).contains("component \"orders\"").contains("component \"shipping\"");
        assertThat(uml).contains("OrdersPaymentAcceptedEvent");
    }

    /**
     * PlantUML reads a colour as the end of a declaration, so a link after one is a syntax error - and a
     * diagram that does not parse renders as an error box that fails no build.
     */
    @Test
    void contextView_theFocusedBoxCarriesItsLinkBeforeItsColour() {
        ArchitectureModel model = landscape();

        String uml = PlantUmlViews.contextView(SystemContext.of(model, orders(), 60),
                generation(model)).source();

        assertThat(uml).contains("component \"orders\" as c_orders [[/docs/prod/systems/orders/]] #line.bold");
    }

    /**
     * A REST call is dotted and a message is solid, so the two are told apart without reading every label.
     */
    @Test
    void contextView_drawsARestCallDottedAndAMessageSolid() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-a")),
                List.of(new SystemRelation(RelationKind.EVENT, "shipping", "z", "orders", "orders-a", "AnEvent",
                                null, null, null),
                        new SystemRelation(RelationKind.REST_API, "orders", "orders-a", "catalog", "t", null,
                                "GET", "/api/x", null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, other("shipping"), other("catalog")));

        String uml = PlantUmlViews.contextView(SystemContext.of(model, orders, 60),
                generation(model)).source();

        assertThat(uml).contains("-->").contains("..>");
    }

    /**
     * A name that is not in the model gets no link. A neighbour's name comes from a relation and is free text,
     * so it could name a page that does not exist.
     */
    @Test
    void contextView_whenANeighbourIsNotDocumented_thenItsBoxCarriesNoLink() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(),
                List.of(new SystemRelation(RelationKind.EVENT, "ghost", "g", "orders", "orders-a", "AnEvent",
                        null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders));

        String uml = PlantUmlViews.contextView(SystemContext.of(model, orders, 60),
                generation(model)).source();

        assertThat(uml).contains("component \"ghost\"");
        assertThat(uml).doesNotContain("[[/docs/prod/systems/ghost/]]");
    }

    /**
     * The fence is the one place the Markdown escaping cannot help: nothing inside it is Markdown, and the site
     * build does not look in. PlantUML reads {@code [[...]]} as a link inside a label as well as outside one,
     * so a name carrying brackets could put a link of somebody else's choosing on the diagram.
     */
    @Test
    void contextView_aNeighbourNameCannotBreakOutOfALinkOrALabel() {
        String hostile = "evil]] [[javascript:alert(1)";
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(),
                List.of(new SystemRelation(RelationKind.EVENT, hostile, "g", "orders", "orders-a",
                        "An\"Event\nWithQuotes", null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders));

        String uml = PlantUmlViews.contextView(SystemContext.of(model, orders, 60),
                generation(model)).source();

        assertThat(uml.split("\\[\\[", -1).length - 1)
                .describedAs("the only link is the one on the documented system's own box")
                .isEqualTo(1);
        assertThat(uml).contains("[[/docs/prod/systems/orders/]]");
        assertThat(uml).describedAs("a quote would end the label early").doesNotContain("An\"Event");
        assertThat(uml).describedAs("a newline would end the statement").doesNotContain("An\"Event\n");
    }

    /**
     * Only the boxes the diagram shows may carry an arrow; the page's table lists the rest.
     */
    @Test
    void contextView_whenNeighboursAreTruncated_thenTheirArrowsAreNotDrawn() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(),
                List.of(new SystemRelation(RelationKind.EVENT, "alpha", "a", "orders", "orders-a", "A",
                                null, null, null),
                        new SystemRelation(RelationKind.EVENT, "zulu", "z", "orders", "orders-a", "Z",
                                null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, other("alpha"), other("zulu")));

        SystemContext context = SystemContext.of(model, orders, 1);
        String uml = PlantUmlViews.contextView(context, generation(model)).source();

        assertThat(context.edges()).describedAs("both are still in the model").hasSize(2);
        assertThat(uml).contains("component \"alpha\"");
        assertThat(uml).doesNotContain("component \"zulu\"");
        assertThat(uml).doesNotContain(" : Z");
    }

    @Test
    void whiteboxView_putsTheComponentsInAPackageAndTheNeighboursOutside() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake"), component("orders-risk")),
                List.of(new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders", "orders-intake",
                                "Internal", null, null, null),
                        new SystemRelation(RelationKind.EVENT, "shipping", "z", "orders", "orders-intake", "Outgoing",
                                null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, other("shipping")));

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(uml).contains("package \"orders\" {");
        assertThat(uml).contains("component \"orders-intake\"").contains("component \"orders-risk\"");
        assertThat(uml).contains("component \"shipping\"");
        assertThat(uml).contains("Internal").contains("Outgoing");
    }

    /**
     * A box links to the page of what it draws, and that link has to carry the base URL and the environment
     * prefix - a Markdown link gets both added for it, a fenced one does not.
     */
    @Test
    void whiteboxView_aComponentBoxLinksToItsOwnPage() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake")), List.of(), List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders));

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(uml).contains(
                "[[/docs/prod/systems/orders/system-architecture/building-block-view/components/orders-intake/]]");
    }

    /**
     * A component name carries hyphens, which PlantUML reads as part of an arrow. The readable name goes in the
     * quoted label and an identifier goes on the line.
     */
    @Test
    void aHyphenatedNameBecomesAnIdentifierPlantUmlAccepts() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-foo-bar-service")), List.of(), List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders));

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(uml).contains("as c_orders_foo_bar_service");
    }

    /**
     * PlantUML reads a second declaration of the same identifier as a redefinition, so one box would swallow
     * the other and take all of its arrows with it.
     */
    @Test
    void twoNamesThatDifferOnlyInWhatTheIdentifierDropsStayTwoBoxes() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake"), component("orders_intake"), component("orders.intake")),
                List.of(), List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders));

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(uml).contains("as c_orders_intake ", "as c_orders_intake_2 ", "as c_orders_intake_3 ");
        assertThat(uml.split("component ", -1).length - 1)
                .describedAs("all three components are drawn").isEqualTo(3);
    }

    /**
     * The context view is a star of two ranks and is a narrow column {@code left to right}; a whitebox view is
     * a deep graph and is narrower top to bottom, which is PlantUML's own default. Both are tightened.
     */
    @Test
    void eachViewCarriesTheDirectionItsShapeCallsFor() {
        ArchitectureModel model = landscape();
        DocumentedSystem orders = orders();

        String context = PlantUmlViews.contextView(SystemContext.of(model, orders, 60),
                generation(model)).source();
        String whitebox = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(context).contains("left to right direction");
        assertThat(whitebox).describedAs("a deep graph belongs top to bottom")
                .doesNotContain("left to right direction");
        assertThat(context).contains("skinparam nodesep 8", "skinparam ranksep 20");
        assertThat(whitebox).contains("skinparam nodesep 8", "skinparam ranksep 20");
    }

    /**
     * {@code skinparam componentStyle rectangle} prints <i>"Please use CSS style instead of skinparam"</i> as a
     * text element inside the rendered diagram. Only the two the engine accepts silently may be emitted.
     */
    @Test
    void noSkinparamTheEngineWarnsAbout() {
        ArchitectureModel model = landscape();

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, orders(), 60), "orders",
                generation(model)).source();

        assertThat(uml.lines().filter(line -> line.startsWith("skinparam")).toList())
                .containsExactly("skinparam nodesep 8", "skinparam ranksep 20");
    }

    @Test
    void anArrowAtTheCapKeepsItsNames() {
        ArchitectureModel model = busy(4);

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, busySystem(4), 60), "orders",
                generation(model)).source();

        assertThat(uml).contains("Event1\\nEvent2\\nEvent3\\nEvent4");
        assertThat(uml).doesNotContain("4 Events");
    }

    @Test
    void anArrowAboveTheCapShowsTheCountOfItsKind() {
        ArchitectureModel model = busy(5);

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, busySystem(5), 60), "orders",
                generation(model)).source();

        assertThat(uml).contains(" : 5 Events");
        assertThat(uml).doesNotContain("Event1");
    }

    /** Zero is legal and means an arrow always shows a count, however few names it carries. */
    @Test
    void whenTheCapIsZero_thenEvenOneNameIsCounted() {
        ArchitectureModel model = busy(1);
        GenerationContext generation = new GenerationContext(model, "prod", "https://archrepo",
                GENERATED_AT.minusSeconds(900), GENERATED_AT, 100, 0, "/docs/prod/");

        String uml = PlantUmlViews.whiteboxView(WhiteboxView.of(model, busySystem(1), 60), "orders",
                generation).source();

        assertThat(uml).contains(" : 1 Event");
    }

    /**
     * The regression test for the crash this cap exists for. A diagram that does not render still produces a
     * valid page, so no build would notice - the invariant is what notices.
     * <p>
     * The engine lays a label out by recursion and overflows the browser's stack at about sixty lines. Nothing
     * any view emits may come near that, whatever the model holds.
     */
    @Test
    void noLabelOfAnyViewEverExceedsTheCap() {
        ArchitectureModel model = busy(100);
        DocumentedSystem hostile = busySystem(100);
        GenerationContext generation = generation(model);

        List<String> sources = List.of(
                PlantUmlViews.contextView(SystemContext.of(model, hostile, 60), generation).source(),
                PlantUmlViews.internalView(WhiteboxView.of(model, hostile, 60), "orders", generation).source(),
                PlantUmlViews.whiteboxView(WhiteboxView.of(model, hostile, 60), "orders", generation).source());

        assertThat(sources).allSatisfy(uml -> assertThat(uml.lines().toList()).allSatisfy(line -> {
            int labelLines = line.contains(" : ") ? line.split("\\\\n", -1).length : 0;
            assertThat(labelLines).describedAs("label lines on: %s", line)
                    .isLessThanOrEqualTo(generation.maxEdgeLabels());
        }));
    }

    @Test
    void aSummarizedDiagramSaysSoAndAnUnsummarizedOneDoesNot() {
        assertThat(PlantUmlViews.whiteboxView(WhiteboxView.of(busy(5), busySystem(5), 60), "orders",
                generation(busy(5))).labelsSummarized()).isTrue();
        assertThat(PlantUmlViews.whiteboxView(WhiteboxView.of(busy(4), busySystem(4), 60), "orders",
                generation(busy(4))).labelsSummarized()).isFalse();
    }

    /** The decomposition on its own: the boxes of the system, and not one thing outside it. */
    @Test
    void internalView_drawsNoNeighbourAndNoExternalArrow() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake"), component("orders-risk")),
                List.of(new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders",
                                "orders-intake", "Internal", null, null, null),
                        new SystemRelation(RelationKind.EVENT, "shipping", "z", "orders", "orders-intake",
                                "Outgoing", null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, other("shipping")));

        String uml = PlantUmlViews.internalView(WhiteboxView.of(model, orders, 60), "orders",
                generation(model)).source();

        assertThat(uml).contains("package \"orders\" {");
        assertThat(uml).contains("component \"orders-intake\"").contains("component \"orders-risk\"");
        assertThat(uml).contains("Internal");
        assertThat(uml).describedAs("the neighbour belongs to the other diagram")
                .doesNotContain("component \"shipping\"").doesNotContain("Outgoing");
    }

    /** An arrow to a neighbour the diagram left out would point at nothing. The page's table has it. */
    @Test
    void whiteboxView_whenNeighboursAreTruncated_thenTheirArrowsAreNotDrawn() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake")),
                List.of(new SystemRelation(RelationKind.EVENT, "alpha", "a", "orders", "orders-intake", "A",
                                null, null, null),
                        new SystemRelation(RelationKind.EVENT, "zulu", "z", "orders", "orders-intake", "Z",
                                null, null, null)),
                List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, other("alpha"), other("zulu")));

        WhiteboxView view = WhiteboxView.of(model, orders, 1);
        String uml = PlantUmlViews.whiteboxView(view, "orders", generation(model)).source();

        assertThat(view.external()).describedAs("both are still in the model").hasSize(2);
        assertThat(uml).contains("component \"alpha\"");
        assertThat(uml).doesNotContain("component \"zulu\"");
        assertThat(uml).doesNotContain(" : Z");
    }

    /**
     * A system whose every arrow carries however many message types: one arrow inside it and one leaving it,
     * so that all three views have something for the cap to bite on.
     */
    private static DocumentedSystem busySystem(int labels) {
        List<SystemRelation> relations = new ArrayList<>();
        for (int i = 1; i <= labels; i++) {
            relations.add(new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders",
                    "orders-intake", "Event" + i, null, null, null));
            relations.add(new SystemRelation(RelationKind.EVENT, "shipping", "shipping-service", "orders",
                    "orders-intake", "Event" + i, null, null, null));
        }
        return new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake"), component("orders-risk")), relations, List.of());
    }

    private static ArchitectureModel busy(int labels) {
        return ArchitectureModel.of(List.of(busySystem(labels), other("shipping")));
    }

    private static GenerationContext generation(ArchitectureModel model) {
        return new GenerationContext(model, "prod", "https://archrepo", GENERATED_AT.minusSeconds(900), GENERATED_AT,
                100, 4, "/docs/prod/");
    }

    private static DocumentedSystem orders() {
        return new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(component("orders-a")),
                List.of(new SystemRelation(RelationKind.EVENT, "shipping", "z", "orders", "orders-a",
                        "OrdersPaymentAcceptedEvent", null, null, null)),
                List.of());
    }

    private static ArchitectureModel landscape() {
        return ArchitectureModel.of(List.of(orders(), other("shipping")));
    }

    private static DocumentedSystem other(String name) {
        return new DocumentedSystem(name, name, null, List.of(), null, List.of(component(name + "-service")),
                List.of(), List.of());
    }

    private static DocumentedComponent component(String name) {
        return new DocumentedComponent(name, name, null, ComponentType.BACKEND_SERVICE, null, null, null,
                List.of(), null, null);
    }
}
