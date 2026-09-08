package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.command;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.component;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.model;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.restApi;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one component talks to. Its own context view, not the system's cut down.
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

        assertThat(context.siblings()).containsExactly("orders-risk");
        assertThat(context.siblings()).describedAs("the third component exchanges nothing with this one")
                .doesNotContain("orders-audit");
        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders-intake");
            assertThat(edge.to()).isEqualTo("orders-risk");
            assertThat(edge.sibling()).isTrue();
            assertThat(edge.labels()).containsExactly("OrdersAccepted");
        });
    }

    /**
     * External systems as a blackbox. Two components of one other system are one box and one arrow, because
     * what is inside that system is its own documentation's subject.
     */
    @Test
    void of_anotherSystemIsOneBoxAndItsComponentsAreNotDrawn() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "shipping-other")));
        DocumentedSystem shipping = system("shipping",
                List.of(component("shipping-gateway"), component("shipping-other")), List.of(), List.of());

        ComponentContext context = ComponentContext.of(model(orders, shipping), orders, intakeOf(orders),
                NO_LIMIT, NO_LIMIT);

        assertThat(context.externalSystems()).containsExactly("shipping");
        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.to()).describedAs("the counterpart is the system, not one of its components")
                    .isEqualTo("shipping");
            assertThat(edge.sibling()).isFalse();
            assertThat(edge.labels()).containsExactly("OrdersAccepted", "OrdersCleared");
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

        ComponentContext context = ComponentContext.of(model(orders, system("catalog"), system("shipping")),
                orders, intakeOf(orders), NO_LIMIT, NO_LIMIT);

        assertThat(context.edges()).hasSize(2);
        assertThat(context.edges()).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("orders-intake");
            assertThat(edge.to()).isEqualTo("catalog");
            assertThat(edge.labels()).containsExactly("GET /api/tariffs");
        });
        assertThat(context.edges()).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("orders-intake");
            assertThat(edge.to()).isEqualTo("shipping");
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
            assertThat(edge.from()).isEqualTo("shipping");
            assertThat(edge.to()).isEqualTo("orders-intake");
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

    /** A component that exchanges nothing says so, which is a fact worth reading on its page. */
    @Test
    void of_anIsolatedComponentHasNoEdgeAtAll() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersScored", "orders", "orders-risk", "orders", "orders-audit")));

        ComponentContext context = ComponentContext.of(model(orders), orders, intakeOf(orders), NO_LIMIT,
                NO_LIMIT);

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.siblings()).isEmpty();
        assertThat(context.externalSystems()).isEmpty();
        assertThat(context.truncated()).isZero();
    }

    /**
     * The two limits bound the picture and nothing else. The page promises that its table lists every
     * counterpart, so what the diagram leaves out is still an edge here.
     */
    @Test
    void of_whenThereAreMoreCounterpartsThanTheDiagramMayDraw_thenOnlyThePictureIsCut() {
        List<SystemRelation> relations = new ArrayList<>();
        List<DocumentedComponent> components = new ArrayList<>();
        components.add(component("orders-intake"));
        for (int i = 0; i < 5; i++) {
            components.add(component("orders-worker-" + i));
            relations.add(event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-worker-" + i));
            relations.add(restApi("GET", "/api/thing", "orders", "orders-intake", "system-" + i, "api"));
        }
        DocumentedSystem orders = system("orders", components, relations, List.of());
        List<DocumentedSystem> landscape = new ArrayList<>();
        landscape.add(orders);
        for (int i = 0; i < 5; i++) {
            landscape.add(system("system-" + i));
        }

        ComponentContext context = ComponentContext.of(ArchitectureModel.of(landscape), orders,
                intakeOf(orders), 2, 3);

        assertThat(context.drawnSiblings()).containsExactly("orders-worker-0", "orders-worker-1");
        assertThat(context.drawnSystems()).containsExactly("system-0", "system-1", "system-2");
        assertThat(context.siblings()).hasSize(5);
        assertThat(context.externalSystems()).hasSize(5);
        assertThat(context.edges()).describedAs("every edge is returned, drawn or not").hasSize(10);
        assertThat(context.truncated()).isEqualTo(5);
        assertThat(context.isDrawn("orders-worker-4")).isFalse();
        assertThat(context.isDrawn("ORDERS-WORKER-0")).describedAs("matched the way the edges are")
                .isTrue();
        assertThat(context.isDrawn("orders-intake")).describedAs("the component in the middle").isTrue();
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
                restApi("GET", "/api/tariffs", "orders", "orders-intake", "catalog", "catalog-api")));
        DocumentedSystem catalog = system("catalog");

        ComponentContext first = ComponentContext.of(model(orders, catalog), orders, intakeOf(orders), 60, 60);
        ComponentContext second = ComponentContext.of(model(catalog, orders), orders, intakeOf(orders), 60, 60);

        assertThat(first).isEqualTo(second);
        assertThat(first.edges()).extracting(ComponentContext.Edge::labels)
                .containsExactly(List.of("GET /api/tariffs"),
                        List.of("OrdersAccepted", "OrdersRejected"));
    }
}
