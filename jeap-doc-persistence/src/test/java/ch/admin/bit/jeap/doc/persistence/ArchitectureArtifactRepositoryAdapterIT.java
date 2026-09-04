package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replicated artifacts against a real PostgreSQL.
 * <p>
 * The statements here are the ones a fake repository cannot vouch for: the lookups and the delete, which fold
 * the two names exactly as the unique index does, and a bulk update that must touch one column and leave the
 * blob alone. A wrong delete is invisible in production - everything is simply fetched again on the next
 * import - which is exactly why it is asserted against the database rather than against a map.
 */
class ArchitectureArtifactRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final ArchitectureImportKind KIND = ArchitectureImportKind.OPENAPI_SPEC;
    private static final Instant REPLICATED_AT = Instant.parse("2026-08-28T08:00:11Z");

    @Autowired
    private ArchitectureArtifactRepository artifacts;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void storeAndFind_thenTheBytesComeBackAsTheyWereStored() {
        artifacts.store(artifact("round-trip", "Orders", "orders-payment-scs", "{\"openapi\":\"3.0.0\"}"));

        assertThat(artifacts.find("round-trip", KIND, "Orders", "orders-payment-scs")).get()
                .satisfies(stored -> {
                    assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                            .isEqualTo("{\"openapi\":\"3.0.0\"}");
                    assertThat(stored.etag()).isEqualTo("\"sha256:orders-payment-scs\"");
                    assertThat(stored.version()).isEqualTo("2.1.0");
                });
    }

    @Test
    void store_whenTheArtifactIsAlreadyThere_thenItIsReplacedRatherThanDuplicated() {
        artifacts.store(artifact("upsert", "Orders", "orders-payment-scs", "first"));

        artifacts.store(artifact("upsert", "Orders", "orders-payment-scs", "second"));

        assertThat(artifacts.findRefs("upsert", KIND)).hasSize(1);
        assertThat(artifacts.find("upsert", KIND, "Orders", "orders-payment-scs")).get()
                .satisfies(stored -> assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                        .isEqualTo("second"));
    }

    /** The delete the replication makes when the index stops listing something. */
    @Test
    void remove_thenOnlyTheNamedArtifactsGo() {
        artifacts.store(artifact("pruning", "Orders", "orders-payment-scs", "a"));
        artifacts.store(artifact("pruning", "Orders", "orders-basket-scs", "b"));
        artifacts.store(artifact("pruning", "Shipping", "shipping-dispatch-scs", "c"));
        artifacts.store(artifact("another-environment", "Orders", "orders-payment-scs", "d"));

        artifacts.remove(List.of(refTo("pruning", "Orders", "orders-basket-scs")));

        assertThat(artifacts.findRefs("pruning", KIND))
                .extracting(ArchitectureArtifactRef::component)
                .containsExactlyInAnyOrder("orders-payment-scs", "shipping-dispatch-scs");
        assertThat(artifacts.findRefs("another-environment", KIND))
                .describedAs("another environment's artifact of the same name is untouched")
                .hasSize(1);
    }

    @Test
    void remove_whenTheArtifactsSpanSystems_thenEachOfThemGoes() {
        artifacts.store(artifact("wide", "Orders", "orders-payment-scs", "a"));
        artifacts.store(artifact("wide", "Shipping", "shipping-dispatch-scs", "b"));
        artifacts.store(artifact("wide", "Catalog", "catalog-search-scs", "c"));

        artifacts.remove(List.of(refTo("wide", "Orders", "orders-payment-scs"),
                refTo("wide", "Catalog", "catalog-search-scs")));

        assertThat(artifacts.findRefs("wide", KIND))
                .extracting(ArchitectureArtifactRef::component)
                .containsExactly("shipping-dispatch-scs");
    }

    @Test
    void remove_whenTheOtherKindHasTheSameComponent_thenItIsUntouched() {
        artifacts.store(artifact("kinds", "Orders", "orders-payment-scs", "spec"));
        artifacts.store(new ArchitectureArtifact("kinds", ArchitectureImportKind.DATABASE_SCHEMA, "Orders",
                "orders-payment-scs", "14", "\"sha256:schema\"", "schema".getBytes(StandardCharsets.UTF_8), 6,
                REPLICATED_AT, REPLICATED_AT));

        artifacts.remove(List.of(refTo("kinds", "Orders", "orders-payment-scs")));

        assertThat(artifacts.findRefs("kinds", KIND)).isEmpty();
        assertThat(artifacts.findRefs("kinds", ArchitectureImportKind.DATABASE_SCHEMA))
                .describedAs("the database schema of the same component is a different artifact")
                .hasSize(1);
    }

    /**
     * Confirming a copy is what a run does for every artifact whose entity tag has not moved, so it must not
     * rewrite the content - that is the whole saving.
     */
    @Test
    void confirm_thenTheContentAndTheReplicationTimestampAreLeftAlone() {
        artifacts.store(artifact("confirming", "Orders", "orders-payment-scs", "unchanged bytes"));

        artifacts.confirm("confirming", KIND, "Orders", "orders-payment-scs",
                REPLICATED_AT.plusSeconds(3600));

        assertThat(artifacts.find("confirming", KIND, "Orders", "orders-payment-scs")).get()
                .satisfies(stored -> {
                    assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                            .isEqualTo("unchanged bytes");
                    assertThat(stored.replicatedAt()).isEqualTo(REPLICATED_AT);
                });
    }

    /**
     * The check timestamp is what a run that keeps hitting its deadline orders the unchanged artifacts by, so
     * a reference has to carry it - and it has to move on a confirmation, or the order never rotates.
     */
    @Test
    void confirm_thenTheReferenceCarriesWhenItWasLastChecked() {
        artifacts.store(artifact("checking", "Orders", "orders-payment-scs", "bytes"));
        assertThat(artifacts.findRefs("checking", KIND)).extracting(ArchitectureArtifactRef::checkedAt)
                .containsExactly(REPLICATED_AT);

        artifacts.confirm("checking", KIND, "Orders", "orders-payment-scs", REPLICATED_AT.plusSeconds(3600));

        assertThat(artifacts.findRefs("checking", KIND)).extracting(ArchitectureArtifactRef::checkedAt)
                .containsExactly(REPLICATED_AT.plusSeconds(3600));
    }

    @Test
    void confirm_whenNothingIsStored_thenItDoesNotFail() {
        artifacts.confirm("empty", KIND, "Orders", "orders-payment-scs", REPLICATED_AT);

        assertThat(artifacts.findRefs("empty", KIND)).isEmpty();
    }

    @Test
    void findAll_thenTheArtifactsOfOneSystemComeBack() {
        artifacts.store(artifact("by-system", "Orders", "orders-payment-scs", "a"));
        artifacts.store(artifact("by-system", "Orders", "orders-basket-scs", "b"));
        artifacts.store(artifact("by-system", "Shipping", "shipping-dispatch-scs", "c"));

        assertThat(artifacts.findAll("by-system", KIND, "Orders"))
                .extracting(ArchitectureArtifact::component)
                .containsExactly("orders-basket-scs", "orders-payment-scs");
    }

    /**
     * Deciding what to fetch must never read the blobs, or the entity tags would save nothing at all.
     * <p>
     * <b>The entity load count is the assertion</b>, not the statement count. A projection widened to the
     * entity - a {@code List<ArchitectureArtifactEntity>} return type, which is the obvious thing to write -
     * is still one statement and would satisfy a count of one, while selecting every blob of the environment
     * into memory. Hibernate loads no entity for a closed interface projection, and one for a select of the
     * entity, so this tells the two apart.
     */
    @Test
    void findRefs_thenTheContentIsNotRead() {
        artifacts.store(artifact("projection", "Orders", "orders-payment-scs", "x".repeat(100_000)));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<ArchitectureArtifactRef> refs = artifacts.findRefs("projection", KIND);

        assertThat(refs).singleElement()
                .satisfies(ref -> assertThat(ref.etag()).isEqualTo("\"sha256:orders-payment-scs\""));
        assertThat(statistics.getEntityLoadCount())
                .describedAs("the rows are read as a projection, so no artifact entity is loaded")
                .isZero();
        assertThat(statistics.getPrepareStatementCount())
                .describedAs("one statement, and no second one to fetch a blob")
                .isEqualTo(1);
    }

    /**
     * <b>Two names, not one joined string.</b> A system may be called {@code Order Fulfilment}, so any
     * separator that can occur in a name makes the key ambiguous: joined with a slash, a system {@code a/b}
     * with a component {@code c} and a system {@code a} with a component {@code b/c} are the same key, and one
     * prune takes the neighbour's artifact with it.
     */
    @Test
    void remove_whenTwoArtifactsJoinToTheSameKey_thenOnlyTheNamedOneGoes() {
        artifacts.store(artifact("ambiguous", "a/b", "c", "first"));
        artifacts.store(artifact("ambiguous", "a", "b/c", "second"));

        artifacts.remove(List.of(refTo("ambiguous", "a/b", "c")));

        assertThat(artifacts.findRefs("ambiguous", KIND)).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.system()).isEqualTo("a");
                    assertThat(ref.component()).isEqualTo("b/c");
                });
    }

    /**
     * <b>The lookups fold the two names, the way the unique index does.</b> These rows and the model carry the
     * spellings of two different exports of the same upstream, so a case-sensitive lookup would decide to
     * insert where the index refuses - which fails the whole import, every run.
     */
    @Test
    void store_whenTheUpstreamRespellsTheName_thenItIsTheSameRowRatherThanASecond() {
        artifacts.store(artifact("folding", "Orders", "orders-payment-scs", "first"));

        artifacts.store(artifact("folding", "orders", "Orders-Payment-SCS", "second"));

        assertThat(artifacts.findRefs("folding", KIND)).hasSize(1);
        assertThat(artifacts.find("folding", KIND, "ORDERS", "ORDERS-PAYMENT-SCS")).get()
                .satisfies(stored -> assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                        .isEqualTo("second"));
    }

    /** And a build that reads a system's artifacts finds them under the model's spelling of the name. */
    @Test
    void findAll_whenTheModelSpellsTheSystemDifferently_thenTheArtifactsAreStillFound() {
        artifacts.store(artifact("folding-read", "orders", "orders-payment-scs", "a"));

        assertThat(artifacts.findAll("folding-read", KIND, "ORDERS"))
                .extracting(ArchitectureArtifact::component)
                .containsExactly("orders-payment-scs");
    }

    @Test
    void confirm_whenTheIndexSpellsTheNameDifferently_thenTheStoredRowIsStillConfirmed() {
        artifacts.store(artifact("folding-confirm", "orders", "orders-payment-scs", "a"));

        artifacts.confirm("folding-confirm", KIND, "Orders", "Orders-Payment-SCS",
                REPLICATED_AT.plusSeconds(3600));

        assertThat(artifacts.findRefs("folding-confirm", KIND))
                .extracting(ArchitectureArtifactRef::checkedAt)
                .containsExactly(REPLICATED_AT.plusSeconds(3600));
    }

    @Test
    void remove_whenTheIndexSpellsTheNameDifferently_thenTheStoredRowStillGoes() {
        artifacts.store(artifact("folding-remove", "orders", "orders-payment-scs", "a"));

        artifacts.remove(List.of(refTo("folding-remove", "Orders", "Orders-Payment-SCS")));

        assertThat(artifacts.findRefs("folding-remove", KIND)).isEmpty();
    }

    private static ArchitectureArtifact artifact(String environment, String system, String component,
                                                 String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ArchitectureArtifact(environment, KIND, system, component, "2.1.0",
                "\"sha256:" + component + "\"", bytes, bytes.length, REPLICATED_AT, REPLICATED_AT);
    }

    private static ArchitectureArtifactRef refTo(String environment, String system, String component) {
        return new ArchitectureArtifactRef(environment, KIND, system, component, "2.1.0",
                "\"sha256:" + component + "\"", REPLICATED_AT, null, REPLICATED_AT);
    }
}
