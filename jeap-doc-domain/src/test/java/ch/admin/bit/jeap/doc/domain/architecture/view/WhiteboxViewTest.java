package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.component;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.model;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.restApi;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

class WhiteboxViewTest {

    private static DocumentedSystem orders(List<SystemRelation> relations) {
        return system("orders", List.of(component("orders-intake"), component("orders-risk")), relations, List.of());
    }

    @Test
    void of_drawsTheComponentsOfTheSystemAndTheEdgesBetweenThem() {
        DocumentedSystem orders = orders(List.of(event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk")));

        WhiteboxView view = WhiteboxView.of(model(orders), orders, 60);

        assertThat(view.components()).extracting(DocumentedComponent::name)
                .containsExactly("orders-intake", "orders-risk");
        assertThat(view.internal()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders-intake");
            assertThat(edge.to()).isEqualTo("orders-risk");
            assertThat(edge.labels()).containsExactly("OrdersAccepted");
        });
        assertThat(view.external()).isEmpty();
    }

    /**
     * The criterion's own wording: <i>external systems only as a system blackbox</i>. A component of another
     * system is never drawn here - the edge goes to that system's box.
     */
    @Test
    void of_anotherSystemIsOneBoxAndItsComponentsAreNotDrawn() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "shipping", "shipping-gateway"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "shipping-other")));
        DocumentedSystem shipping = system("shipping", List.of(component("shipping-gateway"), component("shipping-other")),
                List.of(), List.of());

        WhiteboxView view = WhiteboxView.of(model(orders, shipping), orders, 60);

        assertThat(view.neighbourSystems()).containsExactly("shipping");
        assertThat(view.external()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders-intake");
            assertThat(edge.to()).describedAs("the neighbour is a system, not one of its components")
                    .isEqualTo("shipping");
            assertThat(edge.labels()).containsExactly("OrdersAccepted", "OrdersCleared");
        });
        assertThat(view.internal()).isEmpty();
    }

    @Test
    void of_aRestCallOutOfTheSystemPointsAtTheProvidingSystem() {
        DocumentedSystem orders = orders(List.of(restApi("GET", "/api/tariffs", "orders", "orders-risk", "catalog", "t")));

        WhiteboxView view = WhiteboxView.of(model(orders, system("catalog")), orders, 60);

        assertThat(view.external()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders-risk");
            assertThat(edge.to()).isEqualTo("catalog");
            assertThat(edge.labels()).containsExactly("GET /api/tariffs");
        });
    }

    /**
     * Every component is drawn: the whitebox view is the one place the whole decomposition belongs, so it does
     * not cap the number of boxes the way the system context diagram caps its neighbours.
     */
    @Test
    void of_drawsEveryComponentHoweverManyThereAre() {
        DocumentedSystem orders = orders(List.of(event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk")));

        WhiteboxView view = WhiteboxView.of(model(orders), orders, 60);

        assertThat(view.components()).extracting(DocumentedComponent::name)
                .containsExactly("orders-intake", "orders-risk");
        assertThat(view.internal()).singleElement()
                .satisfies(edge -> assertThat(edge.labels()).containsExactly("OrdersAccepted"));
    }

    /**
     * The other systems are bounded the way the context view's are: the picture is cut, the facts are not.
     */
    @Test
    void of_cutsTheNeighbouringSystemsAtTheLimitAndSaysHowMany() {
        DocumentedSystem orders = orders(List.of(
                event("A", "orders", "orders-intake", "alpha", "a"),
                event("Z", "orders", "orders-intake", "zulu", "z")));
        ArchitectureModel landscape = model(orders, system("alpha"), system("zulu"));

        WhiteboxView view = WhiteboxView.of(landscape, orders, 1);

        assertThat(view.neighbourSystems()).containsExactly("alpha", "zulu");
        assertThat(view.drawnNeighbours()).containsExactly("alpha");
        assertThat(view.truncated()).isEqualTo(1);
        assertThat(view.isDrawnNeighbour("alpha")).isTrue();
        assertThat(view.isDrawnNeighbour("ZULU")).isFalse();
        assertThat(view.external()).describedAs("the edge of the neighbour left out is still returned")
                .hasSize(2);
    }

    /**
     * A component is never cut, however many there are. Every one of them has a page, and one missing from the
     * level-1 view would be a page the diagram does not point at.
     */
    @Test
    void of_boundsTheNeighboursAndNeverTheComponents() {
        DocumentedSystem orders = orders(List.of(
                event("Internal", "orders", "orders-intake", "orders", "orders-risk"),
                event("Outgoing", "orders", "orders-intake", "alpha", "a")));

        WhiteboxView view = WhiteboxView.of(model(orders, system("alpha")), orders, 0);

        assertThat(view.components()).extracting(DocumentedComponent::name)
                .containsExactly("orders-intake", "orders-risk");
        assertThat(view.drawnNeighbours()).isEmpty();
        assertThat(view.truncated()).isEqualTo(1);
        assertThat(view.internal()).describedAs("what is inside the system is untouched by the bound")
                .hasSize(1);
    }

    @Test
    void of_isDeterministic() {
        DocumentedSystem orders = orders(List.of(
                event("Z", "orders", "orders-risk", "shipping", "z"),
                event("A", "orders", "orders-intake", "alpha", "a")));
        ArchitectureModel landscape = model(orders, system("shipping"), system("alpha"));

        assertThat(WhiteboxView.of(landscape, orders, 60).external())
                .isEqualTo(WhiteboxView.of(landscape, orders, 60).external())
                .extracting(WhiteboxView.Edge::to).containsExactly("alpha", "shipping");
    }
}
