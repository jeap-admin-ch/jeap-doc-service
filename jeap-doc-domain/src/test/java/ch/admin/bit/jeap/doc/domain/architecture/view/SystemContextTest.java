package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.command;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.component;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.event;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.model;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.restApi;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

class SystemContextTest {

    private static final int NO_LIMIT = 100;

    /**
     * The case the whole class exists for.
     * <p>
     * In the architecture repository an event relation belongs to the system that owns the event, so
     * {@code shipping}, which only consumes {@code orders}'s event, exports no relation at all. Reading only its own
     * export would draw it as an island; the Confluence pages never did, and neither does this.
     */
    @Test
    void of_whenTheRelationIsDefinedByTheOtherSystem_thenItIsStillInThisSystemsContext() {
        DocumentedSystem orders = system("orders", event("OrdersPaymentAcceptedEvent", "orders", "orders-service", "shipping", "shipping-service"));
        DocumentedSystem shipping = system("shipping");
        ArchitectureModel landscape = model(orders, shipping);

        SystemContext context = SystemContext.of(landscape, shipping, NO_LIMIT);

        assertThat(shipping.relations()).describedAs("shipping defines no relation of its own").isEmpty();
        assertThat(context.neighbours()).containsExactly("orders");
        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders");
            assertThat(edge.to()).isEqualTo("shipping");
            assertThat(edge.labels()).containsExactly("OrdersPaymentAcceptedEvent");
        });
    }

    /**
     * The direction the Confluence pages have drawn for years: a message runs from the side that publishes it,
     * a REST call runs from the side that makes it. Getting one of them backwards reverses every arrow of the
     * landscape at once, which is why both are pinned here.
     */
    @Test
    void of_aMessageRunsFromTheProducer_andARestCallFromTheCaller() {
        DocumentedSystem orders = system("orders",
                event("OrdersPaymentAcceptedEvent", "orders", "orders-service", "shipping", "shipping-service"),
                restApi("GET", "/api/tariffs", "orders", "orders-service", "catalog", "catalog-service"));
        ArchitectureModel landscape = model(orders, system("shipping"), system("catalog"));

        SystemContext context = SystemContext.of(landscape, orders, NO_LIMIT);

        assertThat(context.edges()).extracting(SystemContext.ContextEdge::from, SystemContext.ContextEdge::to)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("orders", "shipping"),
                        org.assertj.core.groups.Tuple.tuple("orders", "catalog"));
    }

    @Test
    void of_aCommandRunsFromTheSenderToTheReceiver() {
        DocumentedSystem orders = system("orders", command("OrdersCheckCommand", "orders", "orders-service", "erp", "erp-core"));
        SystemContext context = SystemContext.of(model(orders, system("erp")), orders, NO_LIMIT);

        assertThat(context.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.from()).isEqualTo("orders");
            assertThat(edge.to()).isEqualTo("erp");
        });
    }

    /**
     * Twelve events between two systems are one arrow with twelve labels, not twelve arrows. That is what makes
     * a real landscape readable at all.
     */
    @Test
    void of_edgesOfOneKindAndDirectionAreCollapsed() {
        DocumentedSystem orders = system("orders",
                event("BEvent", "orders", "orders-service", "shipping", "shipping-service"),
                event("AEvent", "orders", "orders-service", "shipping", "shipping-other"));

        SystemContext context = SystemContext.of(model(orders, system("shipping")), orders, NO_LIMIT);

        assertThat(context.edges()).singleElement()
                .satisfies(edge -> assertThat(edge.labels()).containsExactly("AEvent", "BEvent"));
    }

    /**
     * A system talking to itself is the inside of the box, which is the whitebox view's business.
     */
    @Test
    void of_aRelationInsideTheSystemIsNotAContextEdge() {
        DocumentedSystem orders = system("orders", event("OrdersInternalEvent", "orders", "orders-a", "orders", "orders-b"));

        assertThat(SystemContext.of(model(orders), orders, NO_LIMIT).isEmpty()).isTrue();
    }

    /**
     * A relation whose other end belongs to no known system would be an arrow pointing at nothing.
     */
    @Test
    void of_aRelationWithoutAKnownCounterpartIsNotDrawn() {
        DocumentedSystem orders = system("orders", event("OrdersEvent", "orders", "orders-service", null, null));

        assertThat(SystemContext.of(model(orders), orders, NO_LIMIT).isEmpty()).isTrue();
    }

    /**
     * A system with sixty neighbours renders a diagram nobody can read. Only the diagram is cut: the page
     * still lists every neighbour, which is what its own wording promises the reader.
     */
    @Test
    void of_whenThereAreTooManyNeighbours_thenOnlyTheDiagramIsCut() {
        DocumentedSystem orders = system("orders",
                event("A", "orders", "orders-service", "alpha", "a"),
                event("B", "orders", "orders-service", "bravo", "b"),
                event("C", "orders", "orders-service", "charlie", "c"));
        ArchitectureModel landscape = model(orders, system("alpha"), system("bravo"), system("charlie"));

        SystemContext context = SystemContext.of(landscape, orders, 2);

        assertThat(context.drawn()).describedAs("what the diagram shows").containsExactly("alpha", "bravo");
        assertThat(context.truncated()).isEqualTo(1);
        assertThat(context.neighbours()).describedAs("what the page lists")
                .containsExactly("alpha", "bravo", "charlie");
        assertThat(context.edges()).describedAs("every edge, so the table can carry them").hasSize(3);
    }

    /**
     * Two runs over one model produce the same diagram, or every build is a diff.
     */
    @Test
    void of_isDeterministic() {
        DocumentedSystem orders = system("orders",
                event("Z", "orders", "orders-service", "shipping", "z"),
                event("A", "orders", "orders-service", "alpha", "a"));
        ArchitectureModel landscape = model(orders, system("shipping"), system("alpha"));

        List<SystemContext.ContextEdge> first = SystemContext.of(landscape, orders, NO_LIMIT).edges();
        List<SystemContext.ContextEdge> second = SystemContext.of(landscape, orders, NO_LIMIT).edges();

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(SystemContext.ContextEdge::to).containsExactly("alpha", "shipping");
    }

    @Test
    void systemOf_findsTheSystemAComponentBelongsTo() {
        ArchitectureModel landscape = model(
                system("orders", List.of(component("orders-foo")), List.of(), List.of()),
                system("shipping", List.of(component("shipping-bar")), List.of(), List.of()));

        assertThat(landscape.systemOf("SHIPPING-BAR")).get()
                .extracting(DocumentedSystem::name).isEqualTo("shipping");
        assertThat(landscape.systemOf("nobody")).isEmpty();
    }
}
