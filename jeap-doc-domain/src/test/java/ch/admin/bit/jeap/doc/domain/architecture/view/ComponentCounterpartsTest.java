package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentCounterparts.Counterpart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.component;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.model;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.restApi;
import static ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModelFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is on the other side: the callers of an operation, and the components that consume or publish a
 * message. What is asserted here is the joining and the resolving - a page reads a slug and derives none.
 */
class ComponentCounterpartsTest {

    private static final DocumentedComponent ORDERS_API = component("orders-api");

    private static DocumentedSystem orders(SystemRelation... relations) {
        return system("orders", List.of(ORDERS_API), List.of(relations), List.of());
    }

    private static DocumentedSystem shipping() {
        return system("shipping", List.of(component("shipping-dispatch"), component("shipping-label")),
                List.of(), List.of());
    }

    /**
     * <b>The join is the method and the path.</b> The operation comes from the replicated specification and
     * the relation from the Pact importer, and the two spell one path differently: a trailing slash on one
     * side and none on the other is the same operation.
     */
    @Test
    void callersOf_joinsTheRelationToTheOperationIgnoringATrailingSlashAndTheMethodCase() {
        ArchitectureModel model = model(orders(
                new SystemRelation(RelationKind.REST_API, "shipping", "shipping-dispatch", "orders",
                        "orders-api", null, "post", "/api/v4/businesspartner", null)),
                shipping());

        ComponentCounterparts.RestCallers callers = ComponentCounterparts.callersOf(model, ORDERS_API);

        assertThat(callers.of("POST", "/api/v4/businesspartner/"))
                .extracting(Counterpart::component).containsExactly("shipping-dispatch");
        assertThat(callers.of("POST", "/api/v4/businesspartner"))
                .describedAs("and the same operation written without the slash")
                .hasSize(1);
        assertThat(callers.of("GET", "/api/v4/businesspartner"))
                .describedAs("another method is another operation")
                .isEmpty();
    }

    /**
     * <b>A path variable is a path variable, whatever it is called.</b> The specification and the relation
     * come from two different parsers, and a team renaming a variable between them must not make the callers
     * vanish. This is the architecture repository's own rule, {@code RestApi.pathWithoutVariableNames}.
     */
    @Test
    void callersOf_joinsPathsWhoseVariablesAreNamedDifferently() {
        ArchitectureModel model = model(orders(
                restApi("GET", "/api/v4/businesspartner/{bpId}", "shipping", "shipping-dispatch", "orders",
                        "orders-api")),
                shipping());

        ComponentCounterparts.RestCallers callers = ComponentCounterparts.callersOf(model, ORDERS_API);

        assertThat(callers.of("GET", "/api/v4/businesspartner/{businessPartnerId}"))
                .extracting(Counterpart::component).containsExactly("shipping-dispatch");
        assertThat(callers.of("GET", "/api/v4/businesspartner/{id}/address"))
                .describedAs("a variable in one segment does not join paths of a different shape")
                .isEmpty();
    }

    /** The root path is the whole path, so its slash stays. */
    @Test
    void callersOf_whenThePathIsTheRoot_thenItKeepsItsSlash() {
        ArchitectureModel model = model(orders(
                restApi("GET", "/", "shipping", "shipping-dispatch", "orders", "orders-api")), shipping());

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).of("GET", "/")).hasSize(1);
    }

    /**
     * <b>A called operation the specification does not declare keeps its callers.</b> The architecture model
     * knows operations the published specification has no path for, and a page that asked for every row it
     * wrote can name what is left rather than dropping those callers.
     */
    @Test
    void notLookedUp_namesTheCalledOperationsNoRowAskedFor() {
        ArchitectureModel model = model(
                orders(restApi("GET", "/api/orders", "shipping", "shipping-dispatch", "orders", "orders-api"),
                        restApi("GET", "/api/vats/1", "shipping", "shipping-label", "orders", "orders-api")),
                shipping());
        ComponentCounterparts.RestCallers callers = ComponentCounterparts.callersOf(model, ORDERS_API);

        // The page writes a row for the one operation its specification declares.
        callers.of("GET", "/api/orders");

        assertThat(callers.notLookedUp()).singleElement().satisfies(operation -> {
            assertThat(operation.label()).isEqualTo("GET /api/vats/1");
            assertThat(operation.callers()).extracting(Counterpart::component)
                    .containsExactly("shipping-label");
        });
    }

    /**
     * <b>A relation with no path names no operation.</b> The path is a REST relation's, and one without a
     * path would sort against the others as a null and take the build of the whole part with it.
     */
    @Test
    void callersOf_leavesOutARelationWithNoPath() {
        ArchitectureModel model = model(orders(
                new SystemRelation(RelationKind.REST_API, "shipping", "shipping-dispatch", "orders",
                        "orders-api", null, "GET", null, null),
                new SystemRelation(RelationKind.REST_API, "shipping", "shipping-label", "orders",
                        "orders-api", null, "GET", " ", null),
                restApi("GET", "/api/vats/1", "shipping", "shipping-dispatch", "orders", "orders-api")),
                shipping());

        ComponentCounterparts.RestCallers callers = ComponentCounterparts.callersOf(model, ORDERS_API);

        assertThat(callers.notLookedUp()).extracting(ComponentCounterparts.Operation::label)
                .containsExactly("GET /api/vats/1");
    }

    /** Nothing is left over when every called operation has a row. */
    @Test
    void notLookedUp_whenEveryCalledOperationHasARow_thenNothingIsLeft() {
        ArchitectureModel model = model(
                orders(restApi("GET", "/api/orders", "shipping", "shipping-dispatch", "orders", "orders-api")),
                shipping());
        ComponentCounterparts.RestCallers callers = ComponentCounterparts.callersOf(model, ORDERS_API);

        callers.of("get", "/api/orders/");

        assertThat(callers.notLookedUp()).isEmpty();
    }

    /** A caller of this landscape is linkable; the slugs come from the model and never from the name. */
    @Test
    void callersOf_resolvesTheSystemAndTheComponentOfEveryCaller() {
        ArchitectureModel model = model(
                orders(restApi("GET", "/orders", "shipping", "shipping-dispatch", "orders", "orders-api")),
                shipping());

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).of("GET", "/orders"))
                .singleElement()
                .satisfies(caller -> {
                    assertThat(caller.component()).isEqualTo("shipping-dispatch");
                    assertThat(caller.componentSlug()).isEqualTo("shipping-dispatch");
                    assertThat(caller.system()).isEqualTo("shipping");
                    assertThat(caller.systemSlug()).isEqualTo("shipping");
                    assertThat(caller.isLinkable()).isTrue();
                });
    }

    /**
     * A caller no system of this landscape owns keeps its name and gets no slug: a link would point at a page
     * this run never wrote, and the site is built with {@code onBrokenLinks: 'throw'}.
     */
    @Test
    void callersOf_whenTheSystemIsUnknown_thenTheCallerIsNamedAndNotLinked() {
        ArchitectureModel model = model(orders(
                restApi("GET", "/orders", null, "somebody-elses-service", "orders", "orders-api")));

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).of("GET", "/orders"))
                .singleElement()
                .satisfies(caller -> {
                    assertThat(caller.component()).isEqualTo("somebody-elses-service");
                    assertThat(caller.componentSlug()).isNull();
                    assertThat(caller.systemSlug()).isNull();
                    assertThat(caller.isLinkable()).isFalse();
                });
    }

    /** One row per caller, in one order, whatever the order of the relations. */
    @Test
    void callersOf_namesEveryCallerOnce_sortedIgnoringCase() {
        ArchitectureModel model = model(
                orders(restApi("GET", "/orders", "shipping", "shipping-label", "orders", "orders-api"),
                        restApi("GET", "/orders", "shipping", "shipping-dispatch", "orders", "orders-api"),
                        restApi("GET", "/orders", "shipping", "Shipping-Dispatch", "orders", "orders-api")),
                shipping());

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).of("GET", "/orders"))
                .extracting(Counterpart::component)
                .containsExactly("shipping-dispatch", "shipping-label");
    }

    /** The Pact contract rides with the caller, and one relation carrying it is enough. */
    @Test
    void callersOf_whenOneRelationOfACallerCarriesAPact_thenTheCallerCarriesIt() {
        ArchitectureModel model = model(orders(
                restApi("GET", "/orders", "shipping", "shipping-dispatch", "orders", "orders-api"),
                new SystemRelation(RelationKind.REST_API, "shipping", "shipping-dispatch", "orders",
                        "orders-api", null, "GET", "/orders", "https://pacts.example.ch/orders")),
                shipping());

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).of("GET", "/orders"))
                .singleElement()
                .satisfies(caller -> assertThat(caller.pactUrl())
                        .isEqualTo("https://pacts.example.ch/orders"));
    }

    /** Only REST relations, and only where this component is the one being called. */
    @Test
    void callersOf_leavesOutMessageRelationsAndTheCallsThisComponentMakesItself() {
        ArchitectureModel model = model(
                orders(restApi("GET", "/dispatch", "orders", "orders-api", "shipping", "shipping-dispatch"),
                        new SystemRelation(RelationKind.EVENT, "shipping", "shipping-dispatch", "orders",
                                "orders-api", "OrdersAcceptedEvent", null, null, null)),
                shipping());

        assertThat(ComponentCounterparts.callersOf(model, ORDERS_API).isEmpty()).isTrue();
    }

    /** The consumers of a message the component produces, the component's own contract left out. */
    @Test
    void counterpartsOf_namesTheOtherSideAndNotTheComponentItself() {
        DocumentedMessage event = message(
                new MessageContract(ContractRole.PRODUCES, "orders-api", "orders", "orders.topic", List.of()),
                new MessageContract(ContractRole.CONSUMES, "shipping-label", "shipping", "orders.topic",
                        List.of()),
                new MessageContract(ContractRole.CONSUMES, "shipping-dispatch", "shipping", "orders.topic",
                        List.of()),
                new MessageContract(ContractRole.CONSUMES, "orders-api", "orders", "orders.topic", List.of()));
        ArchitectureModel model = model(orders(), shipping());

        List<Counterpart> consumers = ComponentCounterparts.counterpartsOf(model, event,
                ContractRole.CONSUMES, "orders-api");

        assertThat(consumers).extracting(Counterpart::component)
                .describedAs("sorted, and the component's own contract is not a counterpart of itself")
                .containsExactly("shipping-dispatch", "shipping-label");
        assertThat(consumers).allSatisfy(consumer -> {
            assertThat(consumer.isLinkable()).isTrue();
            assertThat(consumer.pactUrl()).isNull();
        });
    }

    /** A contract naming no component is no counterpart: there is nothing to name and nothing to link. */
    @Test
    void counterpartsOf_dropsAContractWithNoComponent() {
        DocumentedMessage event = message(
                new MessageContract(ContractRole.CONSUMES, " ", "shipping", "orders.topic", List.of()));

        assertThat(ComponentCounterparts.counterpartsOf(model(orders(), shipping()), event,
                ContractRole.CONSUMES, "orders-api")).isEmpty();
    }

    /** A contract whose system the model does not name is resolved by the component alone, where it can be. */
    @Test
    void counterpartsOf_whenAContractNamesNoSystem_thenAnUnambiguousOwnerResolvesIt() {
        DocumentedMessage event = message(
                new MessageContract(ContractRole.CONSUMES, "shipping-dispatch", null, "orders.topic",
                        List.of()));

        assertThat(ComponentCounterparts.counterpartsOf(model(orders(), shipping()), event,
                ContractRole.CONSUMES, "orders-api"))
                .singleElement()
                .satisfies(consumer -> {
                    assertThat(consumer.systemSlug()).isEqualTo("shipping");
                    assertThat(consumer.componentSlug()).isEqualTo("shipping-dispatch");
                });
    }

    private static DocumentedMessage message(MessageContract... contracts) {
        return new DocumentedMessage("OrdersAcceptedEvent", "orders-accepted-event", MessageKind.EVENT,
                "An order was accepted.", "orders.topic", null, null, null, List.of(), List.of(contracts));
    }
}
