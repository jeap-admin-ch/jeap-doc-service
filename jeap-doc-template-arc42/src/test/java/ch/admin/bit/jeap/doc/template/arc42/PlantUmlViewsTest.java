package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentContext;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
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

    /** The bounds the shipped defaults set. A case that is about a bound overrides the one it is about. */
    private static final DiagramLimits LIMITS = new DiagramLimits(100, 4, 40, 100, 200);

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

        assertThat(uml).contains("component \"orders\" as c_orders [[/docs/prod/systems/orders/]] "
                                 + "#Gold;line.bold");
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

        assertThat(uml).contains("-[#blue]->").contains(".[#blue].>");
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
                GENERATED_AT.minusSeconds(900), GENERATED_AT, new DiagramLimits(100, 0, 40, 100, 200),
                "/docs/prod/");

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
                    .isLessThanOrEqualTo(generation.limits().maxEdgeLabels());
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
                LIMITS, "/docs/prod/");
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
                List.of(), null, null, null);
    }

    // The two diagrams a component's pages carry. Nothing about Markdown reaches inside a fence, so
    // everything a fence needs it carries itself: its own escaping, and links that already hold the base URL.

    @Test
    void componentContextView_drawsTheComponentInItsSystemAndTheOthersOutside() {
        ArchitectureModel model = componentLandscape();

        String uml = PlantUmlViews.componentContextView(componentContext(model, 60, 60),
                generation(model)).source();

        assertThat(uml).startsWith("@startuml").endsWith("@enduml");
        assertThat(uml).contains("left to right direction");
        assertThat(uml).describedAs("the component and its sibling inside the system's package")
                .contains("package \"orders\" as c_orders [[/docs/prod/systems/orders/]] {")
                .contains("component \"orders-intake\"")
                .contains("component \"orders-risk\"");
        assertThat(uml).describedAs("and the counterpart of the other system, inside a package for it")
                .contains("package \"shipping\"")
                .contains("component \"shipping-gateway\"");
        assertThat(uml).describedAs("a message is a solid arrow and a REST call a dotted one, both blue")
                .contains("-[#blue]->")
                .contains(".[#blue].>");
    }

    /**
     * PlantUML reads a colour as the end of a declaration, so a link after one is a syntax error - and a
     * diagram that does not parse renders as an error box that fails no build.
     */
    @Test
    void componentContextView_theComponentInTheMiddleCarriesItsLinkBeforeItsColour() {
        ArchitectureModel model = componentLandscape();

        String uml = PlantUmlViews.componentContextView(componentContext(model, 60, 60),
                generation(model)).source();

        assertThat(uml).contains("component \"orders-intake\" as c_orders_intake "
                                 + "[[/docs/prod/systems/orders/system-architecture/building-block-view/"
                                 + "components/orders-intake/]] #Gold;line.bold");
    }

    /** Every box is a link, and a fenced one carries the base URL and the environment prefix already. */
    @Test
    void componentContextView_everyBoxLinksToThePageOfWhatItDraws() {
        ArchitectureModel model = componentLandscape();

        String uml = PlantUmlViews.componentContextView(componentContext(model, 60, 60),
                generation(model)).source();

        assertThat(uml).contains("[[/docs/prod/systems/orders/system-architecture/building-block-view/"
                                 + "components/orders-risk/]]");
        assertThat(uml).describedAs("a counterpart of another system links to its own page, under that "
                                    + "system's slug")
                .contains("[[/docs/prod/systems/shipping/system-architecture/building-block-view/"
                          + "components/shipping-gateway/]]");
        assertThat(uml).describedAs("and the package carries the way into that system's own documentation")
                .contains("package \"shipping\" as c_shipping [[/docs/prod/systems/shipping/]] {");
    }

    /** An arrow to a box the diagram left out would point at nothing; the page's table still lists it. */
    @Test
    void componentContextView_drawsNoArrowToACounterpartItLeftOut() {
        ArchitectureModel model = componentLandscape();

        String uml = PlantUmlViews.componentContextView(componentContext(model, 0, 0),
                generation(model)).source();

        assertThat(uml).doesNotContain("orders-risk").doesNotContain("shipping");
        assertThat(uml).describedAs("and no arrow at all, because both ends of every edge are gone")
                .doesNotContain("-[#blue]->").doesNotContain(".[#blue].>");
    }

    /** The cap on an arrow's names applies here too: it is the one method every arrow goes through. */
    @Test
    void componentContextView_countsTheNamesOnAnArrowThatCarriesTooMany() {
        ArchitectureModel model = componentLandscape();
        GenerationContext capped = new GenerationContext(model, "prod", "https://archrepo",
                GENERATED_AT.minusSeconds(900), GENERATED_AT, new DiagramLimits(100, 0, 40, 100, 200),
                "/docs/prod/");

        PlantUmlViews.Diagram diagram =
                PlantUmlViews.componentContextView(componentContext(model, 60, 60), capped);

        assertThat(diagram.labelsSummarized()).isTrue();
        assertThat(diagram.source()).contains(" : 1 Event");
    }

    /** A name inside a fence escapes itself: PlantUML reads {@code [[...]]} as a link inside a label too. */
    @Test
    void componentContextView_escapesTheNamesInsideTheFence() {
        DocumentedComponent hostile = new DocumentedComponent("orders[[https://evil.example/x]]",
                "orders-intake", null, ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null,
                null);
        DocumentedSystem system = new DocumentedSystem("orders\"", "orders", null, List.of(), null,
                List.of(hostile), List.of(), List.of());
        ArchitectureModel model = ArchitectureModel.of(List.of(system));

        String uml = PlantUmlViews.componentContextView(
                ComponentContext.of(model, system, hostile, 60, 60), generation(model)).source();

        assertThat(uml).describedAs("the label is escaped and the alias is sanitized: a quote becomes a typographic "
                             + "one in the name and an underscore in the identifier")
                .contains("package \"orders\u2019\" as c_orders_ [[/docs/prod/systems/orders/]] {")
                .contains("component \"orders((https://evil.example/x))\"")
                .doesNotContain("[[https://evil.example/x]]");
    }

    @Test
    void databaseSchema_drawsAnEntityPerTableWithItsKeysAndOneArrowPerForeignKey() {
        String uml = PlantUmlViews.databaseSchema(documented(schema(), generation(landscape()))).source();

        assertThat(uml).startsWith("@startuml").endsWith("@enduml");
        assertThat(uml).contains("""
                entity "orders_order" {
                  * id : uuid <<PK>>
                  --
                    party_id : uuid <<FK>>
                  * total : numeric(12,2)
                }
                """);
        assertThat(uml).describedAs("one arrow per foreign key, naming the columns it is made of")
                .contains("\"orders_order\" }o--|| \"orders_party\" : party_id");
    }

    /**
     * <b>An array type keeps its brackets.</b> The escaping in the fence replaces only a <i>doubled</i>
     * bracket, which is the pair PlantUML reads as a link: replacing every one of them turned
     * {@code text[]} into {@code text()} on the page, which is a different type.
     */
    @Test
    void databaseSchema_whenAColumnIsAnArray_thenItsTypeStillSaysSo() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_order",
                List.of(new SchemaColumn("id", "uuid", false),
                        new SchemaColumn("total", "numeric(12,2)", true),
                        new SchemaColumn("tags", "text[]", true)),
                List.of("id"), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("tags : text[]").contains("total : numeric(12,2)");
    }

    /**
     * A table whose columns are all in the primary key gets no separator. A {@code --} just before the
     * closing brace is a syntax error, which renders as an error box and fails no build.
     */
    @Test
    void databaseSchema_whenATableIsNothingButItsKey_thenThereIsNoSeparator() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_party",
                List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("""
                entity "orders_party" {
                  * id : uuid <<PK>>
                }
                """);
        assertThat(uml).doesNotContain("--\n}");
    }

    /** And a table with no key at all is drawn without one, rather than starting with a separator. */
    @Test
    void databaseSchema_whenATableHasNoPrimaryKey_thenItIsDrawnWithoutOne() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_log",
                List.of(new SchemaColumn("line", "text", true)), List.of(), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("""
                entity "orders_log" {
                    line : text
                }
                """);
    }

    /**
     * <b>An arrow into a shard has to reach the entity its family became.</b> Matched on the name it was
     * declared with, every arrow of a partitioned schema would be dropped as pointing at nothing drawn -
     * which is the collapse silently taking the relations off the diagram.
     */
    @Test
    void databaseSchema_whenAKeyPointsIntoAShard_thenTheArrowReachesTheCollapsedEntity() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int suffix = 1; suffix <= 5; suffix++) {
            tables.add(new SchemaTable("doc_meta_" + suffix,
                    List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of()));
        }
        tables.add(new SchemaTable("doc_root", List.of(new SchemaColumn("meta_id", "uuid", false)),
                List.of(), List.of(new SchemaForeignKey("fk_meta", List.of("meta_id"), "DOC_META_3",
                List.of("id")))));
        DatabaseSchema schema = new DatabaseSchema("docs_db", "1", tables);

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).describedAs("the family is one entity, quoted so that the star is a name")
                .contains("entity \"doc_meta_*\" {");
        assertThat(uml).contains("\"doc_root\" }o--|| \"doc_meta_*\" : meta_id");
        assertThat(uml).describedAs("and no shard is drawn on its own")
                .doesNotContain("entity \"doc_meta_3\"");
    }

    /** A key into a family the diagram had no room for still points at nothing. */
    @Test
    void databaseSchema_whenTheCollapsedFamilyIsNotDrawn_thenNoArrowPointsAtIt() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int suffix = 1; suffix <= 5; suffix++) {
            tables.add(new SchemaTable("zzz_meta_" + suffix,
                    List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of()));
        }
        tables.add(new SchemaTable("doc_root", List.of(new SchemaColumn("meta_id", "uuid", false)),
                List.of(), List.of()));
        GenerationContext narrow = new GenerationContext(landscape(), "prod", "https://archrepo",
                GENERATED_AT.minusSeconds(900), GENERATED_AT, new DiagramLimits(100, 4, 40, 1, 200),
                "/docs/prod/");

        String uml = PlantUmlViews.databaseSchema(
                documented(new DatabaseSchema("docs_db", "1", tables), narrow)).source();

        assertThat(uml).contains("entity \"doc_root\"").doesNotContain("zzz_meta");
        assertThat(uml).doesNotContain("}o--||");
    }

    /**
     * The diagram is bounded by the number of tables, and an arrow into one it left out would point at
     * nothing. The page's list of tables carries all of them either way.
     */
    @Test
    void databaseSchema_whenItLeavesATableOut_thenNoArrowPointsAtIt() {
        GenerationContext narrow = new GenerationContext(landscape(), "prod", "https://archrepo",
                GENERATED_AT.minusSeconds(900), GENERATED_AT, new DiagramLimits(100, 4, 40, 1, 200),
                "/docs/prod/");

        String uml = PlantUmlViews.databaseSchema(documented(schema(), narrow)).source();

        assertThat(uml).describedAs("the referenced table is the one kept")
                .contains("entity \"orders_party\"")
                .doesNotContain("entity \"orders_order\"");
        assertThat(uml).doesNotContain("}o--||");
    }

    /**
     * A foreign key naming its target in another case points at the box that was drawn, under the spelling it
     * was drawn with. The two spellings come from one export of one upstream, but nothing guarantees a
     * database spells a constraint's target the way it spells the table - and a PlantUML code is
     * case-sensitive, so an arrow drawn with the key's spelling grows a second, empty box beside the real
     * table and leaves that table with no arrow into it.
     */
    @Test
    void databaseSchema_whenAForeignKeySpellsItsTargetInAnotherCase_thenTheArrowPointsAtTheDrawnEntity() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(
                new SchemaTable("orders_order", List.of(new SchemaColumn("party_id", "uuid", true)),
                        List.of(), List.of(new SchemaForeignKey("fk", List.of("party_id"), "ORDERS_PARTY",
                                List.of("id")))),
                new SchemaTable("orders_party", List.of(new SchemaColumn("id", "uuid", false)),
                        List.of("id"), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("\"orders_order\" }o--|| \"orders_party\" : party_id");
        assertThat(uml).describedAs("no second box under the key's spelling")
                .doesNotContain("\"ORDERS_PARTY\"");
    }

    /**
     * PlantUML reads an {@code '} at the start of a line as a comment, so a nullable column whose name begins
     * with one used to vanish from the diagram - and a {@code }} would have ended the entity early, taking
     * every column after it. The {@code {field}} marker moves the name off the start of the line.
     */
    @Test
    void databaseSchema_whenAColumnNameStartsWithSomethingPlantUmlReads_thenItIsStillAField() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_order",
                List.of(new SchemaColumn("'foo", "text", true), new SchemaColumn("--bar", "text", true),
                        new SchemaColumn("}baz", "text", true),
                        // A quoted identifier may begin with a space, and what PlantUML reads is the first
                        // character of the line that is not blank - so this one is a comment without the
                        // marker, and the column is gone from the picture with nothing failing.
                        new SchemaColumn(" 'spaced", "text", true),
                        new SchemaColumn("\t--tabbed", "text", true),
                        new SchemaColumn(" plainly spaced", "text", true),
                        new SchemaColumn("plain", "text", true)),
                List.of(), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("    {field} 'foo : text")
                .contains("    {field} --bar : text")
                .contains("    {field} }baz : text")
                .contains("    {field}  'spaced : text")
                .contains("    {field} \t--tabbed : text")
                .describedAs("and only where it is needed")
                .contains("     plainly spaced : text")
                .contains("    plain : text");
    }

    /**
     * A non-nullable column needs no marker: the {@code *} in front of its name has already moved the name
     * off the start of the line, which is the whole reason the marker is there.
     */
    @Test
    void databaseSchema_whenAHazardousColumnIsNotNullable_thenTheStarIsEnough() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_order",
                List.of(new SchemaColumn("'foo", "text", false), new SchemaColumn("}bar", "text", false)),
                List.of(), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("  * 'foo : text").contains("  * }bar : text");
        assertThat(uml).describedAs("no marker where the name does not start the line")
                .doesNotContain("{field}");
    }

    /**
     * A quote becomes a typographic one rather than an apostrophe, which is PlantUML's line comment - and
     * {@code /'} opens a block comment from anywhere in a line, so that pair is broken up too.
     */
    @Test
    void databaseSchema_whenANameCarriesAQuote_thenItIsNotTurnedIntoAComment() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable("orders_order",
                List.of(new SchemaColumn("say \"hi\"", "text", true),
                        new SchemaColumn("block /' comment", "text", true)),
                List.of(), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("say \u2019hi\u2019 : text").contains("block /( comment : text");
        assertThat(uml).describedAs("nothing that opens a PlantUML comment").doesNotContain("/'");
    }

    /** The machinery of a schema is on no diagram, and no arrow into it is drawn either. */
    @Test
    void databaseSchema_drawsNeitherTheMachineryTablesNorTheArrowsIntoThem() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(
                new SchemaTable("orders_order", List.of(new SchemaColumn("lock_name", "varchar", true)),
                        List.of(), List.of(new SchemaForeignKey("fk", List.of("lock_name"), "shedlock",
                                List.of("name")))),
                new SchemaTable("shedlock", List.of(new SchemaColumn("name", "varchar", false)),
                        List.of("name"), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("entity \"orders_order\"").doesNotContain("entity \"shedlock\"");
        assertThat(uml).doesNotContain("}o--||");
    }

    /** A column name and a type come out of somebody's database, so both escape themselves in the fence. */
    @Test
    void databaseSchema_escapesTheTableAndColumnNamesInsideTheFence() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable(
                "orders[[https://evil.example/x]]",
                List.of(new SchemaColumn("id\"", "uuid\n[[x]]", false)), List.of("id\""), List.of())));

        String uml = PlantUmlViews.databaseSchema(documented(schema, generation(landscape()))).source();

        assertThat(uml).contains("entity \"orders((https://evil.example/x))\"")
                .contains("* id\u2019 : uuid\\n((x))")
                .doesNotContain("[[");
    }

    /** Two runs over one schema and one landscape produce identical bytes. */
    @Test
    void theTwoNewDiagramsAreTheSameOverTwoRuns() {
        ArchitectureModel model = componentLandscape();
        GenerationContext generation = generation(model);

        assertThat(PlantUmlViews.componentContextView(componentContext(model, 60, 60), generation).source())
                .isEqualTo(PlantUmlViews.componentContextView(componentContext(model, 60, 60), generation)
                        .source());
        assertThat(PlantUmlViews.databaseSchema(documented(schema(), generation)).source())
                .isEqualTo(PlantUmlViews.databaseSchema(documented(schema(), generation)).source());
    }

    /** The derived view the page builds, so that these tests exercise what a render really passes in. */
    private static DocumentedSchema documented(DatabaseSchema schema, GenerationContext generation) {
        return DocumentedSchema.of(schema, generation.limits().maxSchemaTableDiagram(),
                generation.limits().maxSchemaTableList());
    }

    private static ComponentContext componentContext(ArchitectureModel model, int maxSiblings,
                                                     int maxSystems) {
        DocumentedSystem system = model.find("orders").orElseThrow();
        DocumentedComponent intake = system.components().stream()
                .filter(component -> component.name().equals("orders-intake"))
                .findFirst().orElseThrow();
        return ComponentContext.of(model, system, intake, maxSiblings, maxSystems);
    }

    /**
     * A system of two components that exchange an event, one of which also calls another system's API. So
     * both halves of a component context view have something in them.
     */
    private static ArchitectureModel componentLandscape() {
        DocumentedSystem orders = new DocumentedSystem("orders", "orders", null, List.of(), null,
                List.of(component("orders-intake"), component("orders-risk")),
                List.of(new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders",
                                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null),
                        new SystemRelation(RelationKind.REST_API, "orders", "orders-intake", "shipping",
                                "shipping-gateway", null, "GET", "/api/shipments", null)),
                List.of());
        DocumentedSystem shipping = new DocumentedSystem("shipping", "shipping", null, List.of(), null,
                List.of(component("shipping-gateway")), List.of(), List.of());
        return ArchitectureModel.of(List.of(orders, shipping));
    }

    /** A schema of two tables, one referencing the other, and the machinery beside them. */
    private static DatabaseSchema schema() {
        SchemaTable order = new SchemaTable("orders_order",
                List.of(new SchemaColumn("id", "uuid", false),
                        new SchemaColumn("party_id", "uuid", true),
                        new SchemaColumn("total", "numeric(12,2)", false)),
                List.of("id"),
                List.of(new SchemaForeignKey("fk_order_party", List.of("party_id"), "orders_party",
                        List.of("id"))));
        SchemaTable party = new SchemaTable("orders_party",
                List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of());
        return new DatabaseSchema("orders_db", "1.2.3", List.of(order, party));
    }
}
