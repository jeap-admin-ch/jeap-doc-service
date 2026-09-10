package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.COMPONENT_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.MESSAGE_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.SYSTEM_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef.NO_VARIANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per shape of mismatch between what the reaction observer calls something and what the model does.
 * <p>
 * This is the failure that otherwise produces <b>no graph and no error</b>: the observer stores a system name
 * lower-cased and a component name as the publisher sent it, so an exact match finds a fraction of what is
 * there and says nothing about the rest.
 */
class ReactionNameResolverTest {

    private static final String ENVIRONMENT = "dev";

    private final ReactionNameResolver resolver = new ReactionNameResolver(model());

    @Test
    void system_whenTheObserverLowerCasedIt_thenTheModelsSpellingIsUsed() {
        assertThat(resolver.resolve(ref(SYSTEM_REACTIONS, "orders", null))).get()
                .satisfies(resolved -> {
                    assertThat(resolved.name()).isEqualTo("Orders");
                    assertThat(resolved.upstreamName()).isEqualTo("orders");
                });
    }

    /**
     * Aliases exist for systems that do not follow the naming convention everywhere, and every other importer
     * of the architecture repository resolves through them. The reaction importer there is the one that does
     * not, and the symptom is a system with reactions whose page has no graph.
     */
    @Test
    void system_whenItIsPublishedUnderAnAlias_thenItStillResolves() {
        assertThat(resolver.resolve(ref(SYSTEM_REACTIONS, "bestellungen", null))).get()
                .satisfies(resolved -> assertThat(resolved.name()).isEqualTo("Orders"));
    }

    @Test
    void system_whenTheModelDoesNotHaveIt_thenItIsNotResolved() {
        assertThat(resolver.resolve(ref(SYSTEM_REACTIONS, "a-system-nobody-documents", null))).isEmpty();
    }

    /** The index names the system the reactions were published under, and that is what is tried first. */
    @Test
    void component_thenTheSystemFromTheIndexDecidesIt() {
        assertThat(resolver.resolve(ref(COMPONENT_REACTIONS, "gateway", "shipping"))).get()
                .satisfies(resolved -> {
                    assertThat(resolved.name()).isEqualTo("gateway");
                    assertThat(resolved.system()).isEqualTo("Shipping");
                });
    }

    @Test
    void component_whenTheSystemIsNotInTheModel_thenAUniqueNameStillResolves() {
        assertThat(resolver.resolve(ref(COMPONENT_REACTIONS, "orders-payment-scs", "not-a-system"))).get()
                .satisfies(resolved -> {
                    assertThat(resolved.name()).isEqualTo("orders-payment-scs");
                    assertThat(resolved.system()).isEqualTo("Orders");
                });
    }

    /**
     * <b>Two systems, one component name, and no system from the index that resolves.</b> A component graph is
     * drawn on a page below one system, so filing it under whichever system came first would put it on a page
     * about something else - it is left unresolved and reported instead.
     */
    @Test
    void component_whenTwoSystemsHaveTheName_thenItIsNotResolved() {
        assertThat(resolver.resolve(ref(COMPONENT_REACTIONS, "gateway", "not-a-system"))).isEmpty();
    }

    @Test
    void component_whenTheNameIsSpelledDifferently_thenItResolvesIgnoringCase() {
        assertThat(resolver.resolve(ref(COMPONENT_REACTIONS, "Orders-Payment-SCS", "orders"))).get()
                .satisfies(resolved -> assertThat(resolved.name())
                        .describedAs("the model's spelling, not the observer's").isEqualTo("orders-payment-scs"));
    }

    @Test
    void message_thenItResolvesIgnoringCase() {
        assertThat(resolver.resolve(ref(MESSAGE_REACTIONS, "orderspaymentacceptedevent", null))).get()
                .satisfies(resolved -> assertThat(resolved.name())
                        .isEqualTo("OrdersPaymentAcceptedEvent"));
    }

    @Test
    void message_whenTheModelDoesNotDefineIt_thenItIsNotResolved() {
        assertThat(resolver.resolve(ref(MESSAGE_REACTIONS, "SomeOtherEvent", null))).isEmpty();
    }

    private static ReactionGraphRef ref(ArchitectureImportKind kind, String name, String system) {
        return new ReactionGraphRef(ENVIRONMENT, kind, name, name, NO_VARIANT, system, "\"sha256:x\"", "/path",
                null, true);
    }

    /**
     * Two systems, one of them with an alias, and a component name they both have - which is the case that
     * cannot be resolved from the name alone.
     */
    private static ArchitectureModel model() {
        return ArchitectureModel.of(List.of(
                new DocumentedSystem("Orders", "orders", "", List.of("bestellungen"), null,
                        List.of(component("orders-payment-scs"), component("gateway")), List.of(),
                        List.of(new DocumentedMessage("OrdersPaymentAcceptedEvent",
                                "orders-payment-accepted-event", MessageKind.EVENT, null, null, null, null,
                                null, List.of(), List.of()))),
                new DocumentedSystem("Shipping", "shipping", "", List.of(), null,
                        List.of(component("gateway")), List.of(), List.of())));
    }

    private static DocumentedComponent component(String name) {
        return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, null, null, null,
                List.of(), null, null, null);
    }
}
