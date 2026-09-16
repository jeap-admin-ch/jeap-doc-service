package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // ----------------------------------------------------------------------------------------------------
    // What the page draws: the boundary, the fold and the ladder.
    // ----------------------------------------------------------------------------------------------------

    /** The two bounds of these cases: a picture is folded above two relations and dropped above four. */
    private static final DiagramLimits BANDS = new DiagramLimits(60, 4, 40, 100, 200, 4, 2);

    /** No bound reached: every picture is drawn in full. */
    private static final DiagramLimits NO_BOUND = new DiagramLimits(60, 4, 40, 100, 200, 100, 100);

    /** An arrow to a neighbour the bound left out would point at nothing. */
    @Test
    void drawnExternal_leavesOutAnEdgeToANeighbourWithNoBox() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "alpha", "a"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "s")));

        WhiteboxView view = WhiteboxView.of(model(orders, system("alpha"), system("shipping")), orders, 1);

        assertThat(view.external()).hasSize(2);
        assertThat(view.drawnExternal()).singleElement()
                .satisfies(edge -> assertThat(view.neighbourOf(edge)).isEqualTo("alpha"));
    }

    /** The boxes of the boundary picture: the components with something drawn crossing it, and no others. */
    @Test
    void boundaryComponents_holdsTheComponentsOnADrawnExternalEdge() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk"),
                event("OrdersCleared", "orders", "orders-intake", "shipping", "s")));

        WhiteboxView view = WhiteboxView.of(model(orders, system("shipping")), orders, 60);

        assertThat(view.components()).extracting(DocumentedComponent::name)
                .containsExactly("orders-intake", "orders-risk");
        assertThat(view.boundaryComponents()).extracting(DocumentedComponent::name)
                .describedAs("orders-risk talks to nobody outside, so it is not on the boundary picture")
                .containsExactly("orders-intake");
    }

    /**
     * The fold keeps the shape and gives up everything else: one line per pair, whatever travels between
     * them, and a pair joined in both directions loses its arrowhead.
     */
    @Test
    void folded_collapsesAPairIntoOneLineAndMarksTheOnesJoinedBothWays() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk"),
                event("OrdersCleared", "orders", "orders-intake", "orders", "orders-risk"),
                restApi("GET", "/scores", "orders", "orders-intake", "orders", "orders-risk"),
                restApi("GET", "/orders", "orders", "orders-risk", "orders", "orders-intake")));

        WhiteboxView view = WhiteboxView.of(model(orders), orders, 60);

        assertThat(view.internal()).describedAs("three arrows one way and one the other")
                .hasSize(3);
        assertThat(WhiteboxView.folded(view.internal())).singleElement().satisfies(line -> {
            assertThat(line.bothWays()).isTrue();
            assertThat(List.of(line.from(), line.to()))
                    .containsExactlyInAnyOrder("orders-intake", "orders-risk");
        });
    }

    /** A pair joined one way only keeps its arrowhead. */
    @Test
    void folded_whenAPairIsJoinedOneWay_thenTheLineKeepsItsArrowhead() {
        DocumentedSystem orders = orders(List.of(
                event("OrdersAccepted", "orders", "orders-intake", "orders", "orders-risk"),
                event("OrdersCleared", "orders", "orders-intake", "orders", "orders-risk")));

        WhiteboxView view = WhiteboxView.of(model(orders), orders, 60);

        assertThat(WhiteboxView.folded(view.internal())).singleElement()
                .satisfies(line -> assertThat(line.bothWays()).isFalse());
    }

    /** Within the first band both pictures are drawn in full, and the second is the whole one. */
    @Test
    void pictures_whenEverythingFits_thenBothAreDrawnInFullAndTheSecondIsTheWholeOne() {
        WhiteboxView view = WhiteboxView.of(model(wired(1, 1), system("shipping")), wired(1, 1), 60);

        WhiteboxView.Pictures pictures = view.pictures(NO_BOUND);

        assertThat(pictures.inside().kind()).isEqualTo(WhiteboxView.PictureKind.INSIDE);
        assertThat(pictures.inside().rendering()).isEqualTo(WhiteboxView.Rendering.IN_FULL);
        assertThat(pictures.outside().kind()).isEqualTo(WhiteboxView.PictureKind.WHOLE);
        assertThat(pictures.outside().rendering()).isEqualTo(WhiteboxView.Rendering.IN_FULL);
        assertThat(pictures.outside().edges()).describedAs("the whole picture carries both halves")
                .hasSize(2);
    }

    /** Over the first bound a picture is folded to its shape, and it says how many relations that was. */
    @Test
    void pictures_overTheFirstBound_thenThePictureIsFoldedToItsShape() {
        DocumentedSystem orders = wired(3, 0);

        WhiteboxView.Picture inside = WhiteboxView.of(model(orders), orders, 60).pictures(BANDS).inside();

        assertThat(inside.rendering()).isEqualTo(WhiteboxView.Rendering.FOLDED);
        assertThat(inside.relations()).isEqualTo(3);
        assertThat(inside.folded())
                .describedAs("a star is already one relation per pair, so the fold takes off the kinds and "
                             + "the names and no line")
                .hasSize(3);
    }

    /** Over the second bound nothing is drawn, and the count is kept for the page to name. */
    @Test
    void pictures_overTheSecondBound_thenNothingIsDrawnAndTheCountIsKept() {
        DocumentedSystem orders = wired(5, 0);

        WhiteboxView.Picture inside = WhiteboxView.of(model(orders), orders, 60).pictures(BANDS).inside();

        assertThat(inside.rendering()).isEqualTo(WhiteboxView.Rendering.NOT_DRAWN);
        assertThat(inside.isDrawn()).isFalse();
        assertThat(inside.relations()).isEqualTo(5);
        assertThat(inside.edges()).isEmpty();
    }

    /**
     * <b>The ladder.</b> Where the whole picture is over the bound, the boundary alone is drawn instead - it
     * is why four of the five pages that would lose a picture keep one.
     */
    @Test
    void pictures_whenTheWholeOneIsTooLarge_thenTheBoundaryPictureIsDrawnInstead() {
        DocumentedSystem orders = wired(3, 2);

        WhiteboxView.Pictures pictures =
                WhiteboxView.of(model(orders, system("shipping")), orders, 60).pictures(BANDS);

        assertThat(pictures.inside().rendering()).isEqualTo(WhiteboxView.Rendering.FOLDED);
        assertThat(pictures.outside().kind()).describedAs("five relations together is over the bound")
                .isEqualTo(WhiteboxView.PictureKind.BOUNDARY);
        assertThat(pictures.outside().relations()).isEqualTo(2);
        assertThat(pictures.outside().rendering()).isEqualTo(WhiteboxView.Rendering.IN_FULL);
    }

    /** A system whose components exchange nothing with each other has no first picture. */
    @Test
    void pictures_whenNothingIsExchangedInside_thenThereIsNoFirstPicture() {
        DocumentedSystem orders = wired(0, 1);

        WhiteboxView.Pictures pictures =
                WhiteboxView.of(model(orders, system("shipping")), orders, 60).pictures(NO_BOUND);

        assertThat(pictures.inside()).isNull();
        assertThat(pictures.outside().kind()).isEqualTo(WhiteboxView.PictureKind.WHOLE);
    }

    /** And one that exchanges nothing outside gets no second one: it would be the first again. */
    @Test
    void pictures_whenNothingCrossesTheBoundary_thenThereIsNoSecondPicture() {
        DocumentedSystem orders = wired(1, 0);

        WhiteboxView.Pictures pictures = WhiteboxView.of(model(orders), orders, 60).pictures(NO_BOUND);

        assertThat(pictures.inside().rendering()).isEqualTo(WhiteboxView.Rendering.IN_FULL);
        assertThat(pictures.outside()).isNull();
    }

    /**
     * A system wired with the given number of relations inside it and across its boundary, each on a pair of
     * its own so that the counts are the relations rather than the pairs.
     */
    private static DocumentedSystem wired(int inside, int outside) {
        List<DocumentedComponent> components = new ArrayList<>();
        List<SystemRelation> relations = new ArrayList<>();
        components.add(component("orders-intake"));
        for (int i = 0; i < inside; i++) {
            components.add(component("orders-inner-" + i));
            relations.add(event("Inside" + i, "orders", "orders-intake", "orders", "orders-inner-" + i));
        }
        for (int i = 0; i < outside; i++) {
            components.add(component("orders-outer-" + i));
            relations.add(event("Outside" + i, "orders", "orders-outer-" + i, "shipping", "s"));
        }
        return system("orders", components, relations, List.of());
    }

    /** A left-out component has no box, and no arrow reaches it. */
    @Test
    void of_whenAComponentIsLeftOutOfTheViews_thenItAndItsRelationsAreNotDrawn() {
        DocumentedSystem orders = system("orders", List.of(component("orders-intake"), component("orders-mock")),
                List.of(event("OrdersAccepted", "orders", "orders-mock", "orders", "orders-intake"),
                        event("TariffsChanged", "tariffs", "tariffs-service", "orders", "orders-mock")),
                List.of());

        WhiteboxView view = WhiteboxView.of(model(orders, system("tariffs")), orders, 60,
                ViewExcludedComponents.excluding(List.of("orders-mock")));

        assertThat(view.components()).extracting(DocumentedComponent::name).containsExactly("orders-intake");
        assertThat(view.internal()).isEmpty();
        assertThat(view.external()).isEmpty();
    }
}
