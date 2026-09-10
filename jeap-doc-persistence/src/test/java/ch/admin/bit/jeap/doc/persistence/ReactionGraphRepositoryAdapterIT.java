package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.COMPONENT_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.MESSAGE_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.SYSTEM_REACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replicated reaction graphs against a real PostgreSQL.
 * <p>
 * What is asserted here is what a fake repository cannot vouch for: the lookups and the delete, which fold the
 * name exactly as the unique index does and leave the variant alone exactly as it does, and a bulk update that
 * must touch one column and leave the graph alone. Every test uses an environment of its own, because these
 * tests share one database.
 */
class ReactionGraphRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final String NO_VARIANT = ReactionGraphRef.NO_VARIANT;
    private static final String NO_SYSTEM = ReactionGraphRef.NO_SYSTEM;
    private static final Instant IMPORTED_AT = Instant.parse("2026-09-09T08:00:11Z");

    @Autowired
    private ReactionGraphRepository graphs;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void storeAndFind_thenTheGraphComesBackAsItWasStored() {
        graphs.store(graph("round-trip", SYSTEM_REACTIONS, "Orders", NO_VARIANT,
                "{\"nodes\":[],\"edges\":[]}"));

        assertThat(graphs.find("round-trip", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> {
                    assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                            .isEqualTo("{\"nodes\":[],\"edges\":[]}");
                    assertThat(stored.etag()).isEqualTo("\"sha256:Orders\"");
                    assertThat(stored.fingerprint()).isEqualTo("fingerprint-of-Orders");
                    assertThat(stored.upstreamName())
                            .describedAs("the observer lower-cases a system name")
                            .isEqualTo("orders");
                });
    }

    @Test
    void store_whenTheGraphIsAlreadyThere_thenItIsReplacedRatherThanDuplicated() {
        graphs.store(graph("upsert", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "first"));

        graphs.store(graph("upsert", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "second"));

        assertThat(graphs.findRefs("upsert", SYSTEM_REACTIONS)).hasSize(1);
        assertThat(graphs.find("upsert", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("second"));
    }

    /**
     * <b>The lookups fold the name, the way the unique index does.</b> The name is the model's spelling, and a
     * model that re-spells a system between two runs would otherwise produce a second row the index does not
     * refuse.
     */
    @Test
    void store_whenTheNameIsSpelledDifferently_thenItIsStillOneGraph() {
        graphs.store(graph("folding", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "first"));

        graphs.store(graph("folding", SYSTEM_REACTIONS, "orders", NO_VARIANT, "second"));

        assertThat(graphs.findRefs("folding", SYSTEM_REACTIONS)).hasSize(1);
        assertThat(graphs.find("folding", SYSTEM_REACTIONS, "ORDERS", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("second"));
    }

    /**
     * A variant is a graph of its own, and the variant is <b>not</b> folded: it is the observer's string
     * verbatim, and two variants that differ in case are two graphs there.
     */
    @Test
    void store_whenAMessageTypeHasVariants_thenEachOfThemIsItsOwnGraph() {
        graphs.store(graph("variants", MESSAGE_REACTIONS, "OrderCreatedEvent", NO_VARIANT, "default"));
        graphs.store(graph("variants", MESSAGE_REACTIONS, "OrderCreatedEvent", "legacy", "legacy"));
        graphs.store(graph("variants", MESSAGE_REACTIONS, "OrderCreatedEvent", "Legacy", "Legacy"));

        assertThat(graphs.findRefs("variants", MESSAGE_REACTIONS))
                .extracting(ReactionGraphRef::variant)
                .containsExactlyInAnyOrder(NO_VARIANT, "legacy", "Legacy");
        assertThat(graphs.find("variants", MESSAGE_REACTIONS, "OrderCreatedEvent", NO_SYSTEM, "legacy")).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("legacy"));
    }

    /**
     * <b>One environment's reactions are another's business.</b> The same system in {@code dev} and in
     * {@code prod} reacts to different traffic, and one doc service imports both.
     */
    @Test
    void store_whenTheSameSystemIsInTwoEnvironments_thenThereAreTwoGraphs() {
        graphs.store(graph("dev-of-two", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "what dev observed"));
        graphs.store(graph("prod-of-two", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "what prod observed"));

        assertThat(graphs.find("dev-of-two", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("what dev observed"));
        assertThat(graphs.find("prod-of-two", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("what prod observed"));
    }

    /** The delete the replication makes when an index stops listing something. */
    @Test
    void remove_thenOnlyTheNamedGraphsGo() {
        graphs.store(graph("pruning", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "a"));
        graphs.store(graph("pruning", SYSTEM_REACTIONS, "Shipping", NO_VARIANT, "b"));
        graphs.store(graph("pruning", COMPONENT_REACTIONS, "Orders", NO_VARIANT, "c"));
        graphs.store(graph("another-environment", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "d"));

        graphs.remove(List.of(refTo("pruning", SYSTEM_REACTIONS, "Orders", NO_VARIANT)));

        assertThat(graphs.findRefs("pruning", SYSTEM_REACTIONS)).extracting(ReactionGraphRef::name)
                .containsExactly("Shipping");
        assertThat(graphs.findRefs("pruning", COMPONENT_REACTIONS))
                .describedAs("the component graph of the same name is a different graph").hasSize(1);
        assertThat(graphs.findRefs("another-environment", SYSTEM_REACTIONS))
                .describedAs("another environment's graph of the same name is untouched").hasSize(1);
    }

    /**
     * <b>Two columns, not one joined string.</b> A message type's own name may contain the separator, so
     * joined with a slash the type {@code orders/Foo} without a variant and the type {@code orders} with the
     * variant {@code Foo} are the same key - and one prune would take the neighbour's graph with it.
     */
    @Test
    void remove_whenTwoGraphsJoinToTheSameKey_thenOnlyTheNamedOneGoes() {
        graphs.store(graph("ambiguous", MESSAGE_REACTIONS, "orders/Foo", NO_VARIANT, "first"));
        graphs.store(graph("ambiguous", MESSAGE_REACTIONS, "orders", "Foo", "second"));

        graphs.remove(List.of(refTo("ambiguous", MESSAGE_REACTIONS, "orders/Foo", NO_VARIANT)));

        assertThat(graphs.findRefs("ambiguous", MESSAGE_REACTIONS)).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.name()).isEqualTo("orders");
                    assertThat(ref.variant()).isEqualTo("Foo");
                });
    }

    /**
     * Confirming a graph is what a run does for every graph whose entity tag has not moved, so it must not
     * rewrite the bytes - that is the whole saving.
     */
    @Test
    void confirm_thenTheGraphAndTheImportTimestampAreLeftAlone() {
        graphs.store(graph("confirming", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "unchanged bytes"));

        graphs.confirm("confirming", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT,
                IMPORTED_AT.plusSeconds(3600));

        assertThat(graphs.find("confirming", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT)).get()
                .satisfies(stored -> {
                    assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                            .isEqualTo("unchanged bytes");
                    assertThat(stored.importedAt()).isEqualTo(IMPORTED_AT);
                });
    }

    /**
     * The check timestamp is what a run that keeps hitting its deadline orders the unchanged graphs by, so a
     * reference has to carry it - and it has to move on a confirmation, or the order never rotates.
     */
    @Test
    void confirm_thenTheReferenceCarriesWhenItWasLastChecked() {
        graphs.store(graph("checking", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "bytes"));
        assertThat(graphs.findRefs("checking", SYSTEM_REACTIONS)).extracting(ReactionGraphRef::checkedAt)
                .containsExactly(IMPORTED_AT);

        graphs.confirm("checking", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT,
                IMPORTED_AT.plusSeconds(3600));

        assertThat(graphs.findRefs("checking", SYSTEM_REACTIONS)).extracting(ReactionGraphRef::checkedAt)
                .containsExactly(IMPORTED_AT.plusSeconds(3600));
    }

    @Test
    void confirm_whenNothingIsStored_thenItDoesNotFail() {
        graphs.confirm("empty", SYSTEM_REACTIONS, "Orders", NO_SYSTEM, NO_VARIANT, IMPORTED_AT);

        assertThat(graphs.findRefs("empty", SYSTEM_REACTIONS)).isEmpty();
    }

    /**
     * <b>Two systems may each call a component {@code gateway}</b>, and their reaction graphs are two graphs.
     * One row for both would store whichever the index listed last and draw it on both systems' pages.
     */
    @Test
    void store_whenTwoSystemsHaveAComponentOfOneName_thenThereAreTwoGraphs() {
        graphs.store(componentGraph("two-gateways", "gateway", "orders", "what orders observed"));
        graphs.store(componentGraph("two-gateways", "gateway", "shipping", "what shipping observed"));

        assertThat(graphs.findRefs("two-gateways", COMPONENT_REACTIONS)).hasSize(2);
        assertThat(graphs.find("two-gateways", COMPONENT_REACTIONS, "gateway", "orders", NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("what orders observed"));
        assertThat(graphs.find("two-gateways", COMPONENT_REACTIONS, "gateway", "shipping", NO_VARIANT)).get()
                .satisfies(stored -> assertThat(new String(stored.data(), StandardCharsets.UTF_8))
                        .isEqualTo("what shipping observed"));
    }

    /** And a prune of one of them leaves the other where it is. */
    @Test
    void remove_whenTwoSystemsHaveAComponentOfOneName_thenOnlyTheNamedOneGoes() {
        graphs.store(componentGraph("pruning-gateways", "gateway", "orders", "a"));
        graphs.store(componentGraph("pruning-gateways", "gateway", "shipping", "b"));

        graphs.remove(List.of(new ReactionGraphRef("pruning-gateways", COMPONENT_REACTIONS, "gateway",
                "gateway", NO_VARIANT, "orders", "\"sha256:gateway\"", null, IMPORTED_AT, true)));

        assertThat(graphs.findRefs("pruning-gateways", COMPONENT_REACTIONS)).singleElement()
                .satisfies(ref -> assertThat(ref.system()).isEqualTo("shipping"));
    }

    /**
     * <b>A stored graph is not a written page.</b> The observer can serve a graph with no node in it, and a
     * build has to know that without reading the graph - so the count the adapter took while storing it comes
     * back on the reference.
     */
    @Test
    void findRefs_thenAGraphThatDrawsNothingSaysSo() {
        graphs.store(new StoredReactionGraph("drawability", COMPONENT_REACTIONS, "gateway", NO_VARIANT,
                "orders", "gateway", "\"sha256:g\"", "fingerprint",
                "{\"nodes\":[],\"edges\":[]}".getBytes(StandardCharsets.UTF_8), 0, IMPORTED_AT));
        graphs.store(componentGraph("drawability", "intake", "orders", "one node"));

        assertThat(graphs.findRefs("drawability", COMPONENT_REACTIONS))
                .filteredOn(ReactionGraphRef::drawable).extracting(ReactionGraphRef::name)
                .containsExactly("intake");
    }

    /** A component's graph carries the system its reactions were published under, which the index named. */
    @Test
    void componentGraph_thenTheSystemItWasPublishedUnderIsKept() {
        graphs.store(new StoredReactionGraph("component-system", COMPONENT_REACTIONS, "orders-payment-scs",
                NO_VARIANT, "orders", "orders-payment-scs", "\"sha256:c\"", "fingerprint",
                "graph".getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT));

        assertThat(graphs.findRefs("component-system", COMPONENT_REACTIONS)).singleElement()
                .satisfies(ref -> assertThat(ref.system()).isEqualTo("orders"));
        assertThat(graphs.find("component-system", COMPONENT_REACTIONS, "orders-payment-scs", "orders",
                NO_VARIANT))
                .get().satisfies(stored -> assertThat(stored.system()).isEqualTo("orders"));
    }

    /**
     * Deciding what to fetch must never read the graphs, or the entity tags would save nothing at all.
     * <p>
     * <b>The entity load count is the assertion</b>, not the statement count: a projection widened to the
     * entity is still one statement and would satisfy a count of one, while selecting every graph of the
     * environment into memory.
     */
    @Test
    void findRefs_thenTheGraphsAreNotRead() {
        graphs.store(graph("projection", SYSTEM_REACTIONS, "Orders", NO_VARIANT, "x".repeat(100_000)));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<ReactionGraphRef> refs = graphs.findRefs("projection", SYSTEM_REACTIONS);

        assertThat(refs).singleElement()
                .satisfies(ref -> assertThat(ref.etag()).isEqualTo("\"sha256:Orders\""));
        assertThat(statistics.getEntityLoadCount())
                .describedAs("the rows are read as a projection, so no graph entity is loaded").isZero();
        assertThat(statistics.getPrepareStatementCount())
                .describedAs("one statement, and no second one to fetch a graph").isEqualTo(1);
    }

    private static StoredReactionGraph graph(String environment, ArchitectureImportKind kind, String name,
                                             String variant, String data) {
        return new StoredReactionGraph(environment, kind, name, variant, NO_SYSTEM,
                name.toLowerCase(Locale.ROOT), "\"sha256:" + name + "\"", "fingerprint-of-" + name,
                data.getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT);
    }

    private static StoredReactionGraph componentGraph(String environment, String component, String system,
                                                      String data) {
        return new StoredReactionGraph(environment, COMPONENT_REACTIONS, component, NO_VARIANT, system,
                component, "\"sha256:" + component + "-" + system + "\"", "fingerprint",
                data.getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT);
    }

    private static ReactionGraphRef refTo(String environment, ArchitectureImportKind kind, String name,
                                          String variant) {
        return new ReactionGraphRef(environment, kind, name, name.toLowerCase(Locale.ROOT), variant,
                NO_SYSTEM, "\"sha256:" + name + "\"", null, IMPORTED_AT, true);
    }
}
