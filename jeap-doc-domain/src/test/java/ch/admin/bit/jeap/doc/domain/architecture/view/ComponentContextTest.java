package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentContext.Node;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentContext.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.command;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.component;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.model;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.restApi;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one component talks to. Its own context view, not the system's cut down.
 * <p>
 * The counterparts are <b>components</b>, whichever system owns them, and a system is a single box only
 * where its component is not known or the bound left no room for it. So most of what is asserted here is
 * about identity: a box is a component <i>of a system</i>, and two systems may name a component alike.
 */
class ComponentContextTest {

    private static final int NO_LIMIT = 60;

    private static DocumentedSystem orders(List<SystemRelation> relations) {
        return system("orders", List.of(component("orders-intake"), component("orders-risk"),
                component("orders-audit")), relations, List.of());
    }

    private static DocumentedComponent intakeOf(DocumentedSystem orders) {
        return orders.components().stream()
                .filter(component -> component.name().equals("orders-intake"))
                .findFirst().orElseThrow();
    }

    private static List<String> labelsOf(List<Node> nodes) {
        return nodes.stream().map(Node::label).toList();
    }

    /**
     * A sibling appears only when this component exchanges something with it. Drawing all of them would
     * repeat the system's whitebox view.
     */
    @Test
    void of_drawsOnlyTheSiblingsThisComponentExchangesSomethingWith() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk")));

        ComponentContext context = ComponentContext.of(model(orders), orders, intakeOf(orders), NO_LIMIT,
                NO_LIMIT);

        assertThat(labelsOf(context.counterparts())).containsExactly("orders-risk");
        assertThat(labelsOf(context.counterparts()))
                .describedAs("the third component exchanges nothing with this one")
                .doesNotContain("orders-audit");
        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from().label()).isEqualTo("orders-intake");
            assertThat(edge.to().label()).isEqualTo("orders-risk");
            assertThat(edge.to().kind()).isEqualTo(NodeKind.SIBLING);
            assertThat(edge.labels()).containsExactly("OrdersAccepted");
        });
    }

    /**
     * <b>A counterpart of another system is named.</b> Two components of one neighbour are two boxes and two
     * arrows: the point of the view is what this component talks to, and "the system shipping" is a poorer
     * answer than "shipping's gateway" when the model knows which.
     */
    @Test
    void of_aCounterpartOfAnotherSystemIsItsOwnBoxInsideThatSystem() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "shipping-other")));
        DocumentedSystem shipping = system("shipping",
                List.of(component("shipping-gateway"), component("shipping-other")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(labelsOf(context.counterparts()))
                .containsExactly("shipping-gateway", "shipping-other");
        assertThat(context.drawnWholeSystems()).describedAs("shipping is drawn open, so not as a box")
                .isEmpty();
        assertThat(context.drawnSystemsWithComponents()).satisfiesExactly(
                own -> {
                    assertThat(own.name()).isEqualTo("orders");
                    assertThat(labelsOf(own.components())).containsExactly("orders-intake");
                },
                neighbour -> {
                    assertThat(neighbour.name()).isEqualTo("shipping");
                    assertThat(neighbour.slug()).describedAs("the neighbour's own slug, for the box's link")
                            .isEqualTo("shipping");
                    assertThat(labelsOf(neighbour.components()))
                            .containsExactly("shipping-gateway", "shipping-other");
                });
        assertThat(context.arrows()).describedAs("one arrow per counterpart, not one merged onto the system")
                .hasSize(2);
        assertThat(context.arrows()).allSatisfy(arrow ->
                assertThat(arrow.labels()).hasSize(1));
    }

    /** A counterpart's box carries the slugs of its own page, resolved from the model rather than derived. */
    @Test
    void of_aCounterpartCarriesTheSlugsOfItsOwnPage() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "Shipping", "shipping-gateway")));
        DocumentedSystem shipping = system("Shipping", List.of(component("shipping-gateway")), List.of(),
                List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(context.counterparts()).singleElement().satisfies(node -> {
            assertThat(node.kind()).isEqualTo(NodeKind.NEIGHBOUR_COMPONENT);
            assertThat(node.systemName()).isEqualTo("Shipping");
            assertThat(node.systemSlug()).isEqualTo("shipping");
            assertThat(node.componentName()).isEqualTo("shipping-gateway");
            assertThat(node.componentSlug()).isEqualTo("shipping-gateway");
        });
    }

    /**
     * <b>A system reached under an alias is one box, not two.</b> A relation carries whatever the
     * architecture repository stored, so one neighbour named twice would otherwise be drawn twice, each box
     * holding some of its components - strictly worse than the single box it replaced.
     */
    @Test
    void of_whenANeighbourIsNamedByAnAlias_thenItIsOneBoxUnderTheModelsSpelling() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                event("OrdersCleared", "orders", "orders-intake", "SHIP", "shipping-other")));
        DocumentedSystem shipping = new DocumentedSystem("shipping", "shipping", "Ships things.",
                List.of("SHIP"), new Team("Team shipping", null, null, null),
                List.of(component("shipping-gateway"), component("shipping-other")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(context.drawnSystemsWithComponents()).hasSize(2);
        assertThat(context.drawnSystemsWithComponents().getLast()).satisfies(neighbour -> {
            assertThat(neighbour.name()).describedAs("the model's spelling, not the relation's")
                    .isEqualTo("shipping");
            assertThat(labelsOf(neighbour.components()))
                    .containsExactly("shipping-gateway", "shipping-other");
        });
    }

    /**
     * A REST arrow runs from the caller to the provider and a message arrow the other way. So a component
     * that only calls out and only receives messages has arrows in both directions.
     */
    @Test
    void of_aRestCallPointsAtTheProviderAndAMessageAtTheReceiver() {
        DocumentedSystem orders = orders(List.of(
                restApi("GET", "/api/tariffs", "orders", "orders-intake", "catalog", "catalog-api"),
                command("ShipTheOrder", "orders", "orders-intake", "shipping", "shipping-gateway")));

        ComponentContext context = ComponentContext.of(
                model(orders, system("catalog", List.of(component("catalog-api")), List.of(), List.of()),
                        system("shipping", List.of(component("shipping-gateway")), List.of(), List.of())),
                orders, intakeOf(orders), NO_LIMIT, NO_LIMIT);

        assertThat(context.edges()).hasSize(2);
        assertThat(context.edges()).anySatisfy(edge -> {
            assertThat(edge.from().label()).isEqualTo("orders-intake");
            assertThat(edge.to().label()).isEqualTo("catalog-api");
            assertThat(edge.labels()).containsExactly("GET /api/tariffs");
        });
        assertThat(context.edges()).anySatisfy(edge -> {
            assertThat(edge.from().label()).isEqualTo("orders-intake");
            assertThat(edge.to().label()).isEqualTo("shipping-gateway");
            assertThat(edge.labels()).containsExactly("ShipTheOrder");
        });
    }

    /**
     * A relation belongs to the system that <b>defines</b> it, so a component that only consumes another
     * system's events appears in none of its own system's relations. Reading only those would draw it as an
     * island.
     */
    @Test
    void of_readsTheRelationsTheOtherSystemDefines() {
        DocumentedSystem orders = orders(List.of());
        DocumentedSystem shipping = system("shipping", List.of(component("shipping-gateway")),
                List.of(event("ShippingArranged", "shipping", "shipping-gateway", "orders", "orders-intake")),
                List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(context.isEmpty()).isFalse();
        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from().label()).isEqualTo("shipping-gateway");
            assertThat(edge.to().label()).isEqualTo("orders-intake");
        });
    }

    /**
     * Two systems may each have a component called {@code gateway}. Matching on the component name alone
     * would put the other one's arrows on this page.
     */
    @Test
    void of_aComponentOfAnotherSystemWithTheSameName_isNotThisOne() {
        DocumentedSystem orders = system("orders", List.of(component("gateway")), List.of(), List.of());
        DocumentedSystem shipping = system("shipping", List.of(component("gateway")),
                List.of(event("ShippingArranged", "shipping", "gateway", "shipping", "gateway")), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders,
                orders.components().getFirst(), NO_LIMIT, NO_LIMIT);

        assertThat(context.isEmpty())
                .describedAs("the relation of the other system's gateway is not this gateway's")
                .isTrue();
    }

    /**
     * <b>And the counterpart of that name is not a self-loop either.</b> The check is on the identity of a
     * box - which system, which component - because comparing the names would drop this relation as an
     * arrow from a component to itself.
     */
    @Test
    void of_whenAnotherSystemsCounterpartHasThisComponentsName_thenItIsStillDrawn() {
        DocumentedSystem orders = system("orders", List.of(component("gateway")),
                List.of(event("OrdersAccepted", "orders", "gateway", "shipping", "gateway")), List.of());
        DocumentedSystem shipping = system("shipping", List.of(component("gateway")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders,
                orders.components().getFirst(), NO_LIMIT, NO_LIMIT);

        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from().systemName()).isEqualTo("orders");
            assertThat(edge.to().systemName()).describedAs("the other system's gateway, not this one")
                    .isEqualTo("shipping");
        });
        assertThat(context.counterparts()).singleElement().satisfies(node ->
                assertThat(node.key()).isNotEqualTo(
                        new Node(NodeKind.SELF, "orders", "orders", "gateway", "gateway").key()));
    }

    /**
     * A neighbour whose counterpart component the model does not name stays one box - the only thing known
     * about it is that this component talks to it, which is exactly what a single box says.
     */
    @Test
    void of_whenTheCounterpartComponentIsNotNamed_thenTheNeighbourIsOneBox() {
        DocumentedSystem orders = orders(List.of(
                new SystemRelation(RelationKind.EVENT, "orders", "orders-intake", "shipping", null,
                        "ShippingArranged", null, null, null)));
        DocumentedSystem shipping = system("shipping", List.of(component("shipping-gateway")), List.of(),
                List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(labelsOf(context.drawnWholeSystems())).containsExactly("shipping");
        assertThat(context.counterparts()).singleElement().satisfies(node -> {
            assertThat(node.kind()).isEqualTo(NodeKind.NEIGHBOUR_SYSTEM);
            assertThat(node.isSystem()).isTrue();
        });
    }

    /**
     * <b>A counterpart named without its system is resolved where exactly one system has it.</b> The export
     * left the system out; the landscape still says which one it is. Two owners say nothing, and then the
     * counterpart is not drawn at all - a box outside every system would read as a component of no system.
     */
    @Test
    void of_whenTheCounterpartNamesNoSystem_thenItIsResolvedOnlyWhenUnambiguous() {
        DocumentedSystem orders = orders(List.of(
                new SystemRelation(RelationKind.EVENT, null, "shipping-gateway", "orders", "orders-intake",
                        "OrdersAccepted", null, null, null),
                new SystemRelation(RelationKind.EVENT, null, "gateway", "orders", "orders-intake",
                        "OrdersCleared", null, null, null)));
        DocumentedSystem shipping = system("shipping", List.of(component("shipping-gateway"),
                component("gateway")), List.of(), List.of());
        DocumentedSystem catalog = system("catalog", List.of(component("gateway")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping, catalog), orders,
                intakeOf(orders), NO_LIMIT, NO_LIMIT);

        assertThat(context.counterparts()).satisfiesExactlyInAnyOrder(
                resolved -> {
                    assertThat(resolved.label()).isEqualTo("shipping-gateway");
                    assertThat(resolved.kind()).describedAs("one system has it, so the owner is known")
                            .isEqualTo(NodeKind.NEIGHBOUR_COMPONENT);
                    assertThat(resolved.systemName()).isEqualTo("shipping");
                },
                ambiguous -> {
                    assertThat(ambiguous.label()).isEqualTo("gateway");
                    assertThat(ambiguous.kind()).describedAs("two systems have it, so nothing is concluded")
                            .isEqualTo(NodeKind.COMPONENT_OF_UNKNOWN_SYSTEM);
                });
        assertThat(labelsOf(context.unplaced())).containsExactly("gateway");
        assertThat(context.edges()).describedAs("the relation is still a relation, so the table has it")
                .hasSize(2);
        assertThat(context.arrows()).describedAs("but there is no box to point an arrow at").hasSize(1);
        assertThat(context.truncated()).describedAs("it is not left out by a bound, so it is not truncated")
                .isZero();
    }

    /**
     * <b>A relation that names only a neighbour lands on that neighbour's package.</b> Where some of a
     * neighbour's counterparts are named and one relation names none, the arrow has no component box to point
     * at - and dropping it would lose a relation from the picture with nothing saying so.
     */
    @Test
    void of_whenARelationNamesOnlyAnOpenNeighbour_thenTheArrowLandsOnThatSystem() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                new SystemRelation(RelationKind.EVENT, "shipping", null, "orders", "orders-intake",
                        "OrdersCleared", null, null, null)));
        DocumentedSystem shipping = system("shipping", List.of(component("shipping-gateway")), List.of(),
                List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(context.drawnSystemsWithComponents()).describedAs("shipping is open, with its component")
                .hasSize(2);
        assertThat(context.arrows()).describedAs("both relations are drawn").hasSize(2);
        assertThat(context.arrows()).anySatisfy(arrow -> {
            assertThat(arrow.to().label()).isEqualTo("shipping-gateway");
            assertThat(arrow.labels()).containsExactly("OrdersAccepted");
        });
        assertThat(context.arrows()).anySatisfy(arrow -> {
            assertThat(arrow.to().label()).describedAs("onto the package of the system it named")
                    .isEqualTo("shipping");
            assertThat(arrow.to().isSystem()).isTrue();
            assertThat(arrow.labels()).containsExactly("OrdersCleared");
        });
    }

    /** A component that exchanges nothing says so, which is a fact worth reading on its page. */
    @Test
    void of_anIsolatedComponentHasNoEdgeAtAll() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersScored", "orders", "orders-risk", "orders", "orders-audit")));

        ComponentContext context = ComponentContext.of(model(orders), orders, intakeOf(orders), NO_LIMIT,
                NO_LIMIT);

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.counterparts()).isEmpty();
        assertThat(context.truncated()).isZero();
    }

    /**
     * <b>The siblings come first, then one component of each neighbour in turn.</b> A neighbour of two
     * hundred components would otherwise fill the budget and push this component's own siblings off its own
     * page, and naming one counterpart in each neighbour is worth more than forty in the first.
     */
    @Test
    void of_whenTheBoxesAreBounded_thenSiblingsComeFirstAndThenOneNeighbourEach() {
        List<SystemRelation> relations = new ArrayList<>();
        relations.add(event("OrdersScored", "orders", "orders-intake", "orders", "orders-risk"));
        List<DocumentedSystem> landscape = new ArrayList<>();
        for (String neighbour : List.of("alpha", "beta")) {
            List<DocumentedComponent> components = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                components.add(component(neighbour + "-" + i));
                relations.add(restApi("GET", "/api/thing", "orders", "orders-intake", neighbour,
                        neighbour + "-" + i));
            }
            landscape.add(system(neighbour, components, List.of(), List.of()));
        }
        DocumentedSystem orders = orders(relations);
        landscape.addFirst(orders);

        ComponentContext context = ComponentContext.of(ArchitectureModel.of(landscape), orders,
                intakeOf(orders), 3, NO_LIMIT);

        assertThat(labelsOf(context.drawn()))
                .describedAs("this component, its sibling, then one of each neighbour")
                .containsExactly("orders-intake", "orders-risk", "alpha-0", "beta-0");
        assertThat(context.edges()).describedAs("every relation is still here for the table").hasSize(7);
        assertThat(context.truncated()).describedAs("the four counterparts with no box of their own")
                .isEqualTo(4);
    }

    /**
     * <b>At a bound of nothing the view is the one it replaced:</b> every neighbour whole, and no component
     * box but this one. So the picture degrades rather than losing a relation.
     */
    @Test
    void of_whenNoComponentBoxIsAllowed_thenEveryNeighbourIsOneBox() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "shipping-other")));
        DocumentedSystem shipping = system("shipping",
                List.of(component("shipping-gateway"), component("shipping-other")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                0, NO_LIMIT);

        assertThat(labelsOf(context.drawnWholeSystems())).containsExactly("shipping");
        assertThat(context.drawnSystemsWithComponents()).satisfiesExactly(own ->
                assertThat(labelsOf(own.components())).containsExactly("orders-intake"));
        assertThat(context.arrows()).describedAs("the two edges onto that box are one arrow")
                .singleElement().satisfies(arrow -> {
                    assertThat(arrow.to().label()).isEqualTo("shipping");
                    assertThat(arrow.labels()).containsExactly("OrdersAccepted", "OrdersCleared");
                });
        assertThat(context.edges()).describedAs("both relations are still on the page's table").hasSize(2);
        assertThat(context.truncated())
                .describedAs("neither is left out: the relation is on the box of its system")
                .isZero();
    }

    /** The bound on the systems still bounds them, whether they are drawn open or whole. */
    @Test
    void of_whenThereAreMoreSystemsThanTheDiagramMayReach_thenTheRestAreLeftOut() {
        List<SystemRelation> relations = new ArrayList<>();
        List<DocumentedSystem> landscape = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            relations.add(restApi("GET", "/api/thing", "orders", "orders-intake", "system-" + i, "api-" + i));
            landscape.add(system("system-" + i, List.of(component("api-" + i)), List.of(), List.of()));
        }
        DocumentedSystem orders = orders(relations);
        landscape.addFirst(orders);

        ComponentContext context = ComponentContext.of(ArchitectureModel.of(landscape), orders,
                intakeOf(orders), NO_LIMIT, 3);

        assertThat(context.drawnSystemsWithComponents()).describedAs("this system and the three reached")
                .hasSize(4);
        assertThat(context.edges()).hasSize(5);
        assertThat(context.truncated()).describedAs("the counterparts in the two systems not reached")
                .isEqualTo(2);
    }

    /**
     * Two runs over one landscape produce the same view. Everything is sorted, so a page's bytes do not
     * depend on the order the model arrived in - a diagram that moved on every build would make every build
     * look like a change.
     */
    @Test
    void of_isTheSameOverTwoRuns() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk"),
                event("OrdersRejected", "orders", "orders-intake", "orders", "orders-risk"),
                restApi("GET", "/api/tariffs", "orders", "orders-intake", "catalog", "catalog-api"),
                restApi("GET", "/api/rates", "orders", "orders-intake", "billing", "billing-api")));
        DocumentedSystem catalog = system("catalog", List.of(component("catalog-api")), List.of(), List.of());
        DocumentedSystem billing = system("billing", List.of(component("billing-api")), List.of(), List.of());

        ComponentContext first = ComponentContext.of(model(orders, catalog, billing), orders,
                intakeOf(orders), 60, 60);
        ComponentContext second = ComponentContext.of(model(orders, billing, catalog), orders,
                intakeOf(orders), 60, 60);

        assertThat(labelsOf(second.drawn())).isEqualTo(labelsOf(first.drawn()));
        assertThat(second.arrows()).isEqualTo(first.arrows());
        assertThat(second.edges()).isEqualTo(first.edges());
    }

    /** A name is folded the way the edges are, so two spellings of one component are one box. */
    @Test
    void nodeKey_foldsCase() {
        Node upper = new Node(NodeKind.SIBLING, "Orders", "orders", "Orders-Risk", "orders-risk");
        Node lower = new Node(NodeKind.SIBLING, "orders", "orders", "orders-risk", "orders-risk");

        assertThat(upper.key()).isEqualTo(lower.key());
        assertThat(upper.key().component()).isEqualTo("orders-risk".toLowerCase(Locale.ROOT));
    }
}
