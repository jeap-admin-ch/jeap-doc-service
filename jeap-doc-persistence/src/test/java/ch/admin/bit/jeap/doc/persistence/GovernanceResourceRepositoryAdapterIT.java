package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resources read from the Governance Service's {@code /docs-api} against a real PostgreSQL.
 * <p>
 * What matters here is what a fake repository cannot vouch for: that the two index rows - whose key is null -
 * are each addressed as the one row of their kind rather than colliding or multiplying, and that deciding what
 * to fetch never reads a stored body.
 */
class GovernanceResourceRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant IMPORTED_AT = Instant.parse("2026-11-03T10:45:11Z");

    @Autowired
    private GovernanceResourceRepository resources;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void storeAndFind_thenTheBytesComeBackAsTheyWereStored() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "{\"name\":\"orders\"}"));

        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "orders")).get().satisfies(stored -> {
            assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"orders\"}");
            assertThat(stored.etag()).isEqualTo("\"sha256:orders\"");
        });
    }

    @Test
    void store_whenAlreadyThere_thenItIsReplacedRatherThanDuplicated() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "first"));

        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "second"));

        assertThat(resources.findRefs(GovernanceResourceKind.SYSTEM)).hasSize(1);
        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "orders")).get()
                .satisfies(stored -> assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                        .isEqualTo("second"));
    }

    /**
     * An import that commits between two reads of a build is invisible to the second one. The reads are
     * projections that go to the database every time: an entity read twice would come from the persistence
     * context and pass under any isolation.
     */
    @Test
    void readConsistently_whenAnImportCommitsBetweenTwoReads_thenBothSeeTheSameState() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "snapshot-orders", "first"));

        List<Integer> seen = resources.readConsistently(() -> {
            int before = resources.findRefs(GovernanceResourceKind.SYSTEM).size();
            try {
                CompletableFuture.runAsync(() -> resources.store(
                        resource(GovernanceResourceKind.SYSTEM, "snapshot-shipping", "first"))).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return List.of(before, resources.findRefs(GovernanceResourceKind.SYSTEM).size());
        });

        assertThat(seen).containsExactly(1, 1);
        assertThat(resources.findRefs(GovernanceResourceKind.SYSTEM)).hasSize(2);
        resources.remove(resources.findRefs(GovernanceResourceKind.SYSTEM));
    }

    /** What one import changes is written together: a failing write rolls the removal and the index back too. */
    @Test
    void replace_whenOneWriteFails_thenNothingOfItIsApplied() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "replace-orders", "first"));
        resources.store(resource(GovernanceResourceKind.SYSTEM, "replace-shipping", "first"));
        GovernanceResource index = new GovernanceResource(GovernanceResourceKind.SYSTEMS_INDEX, null,
                "\"index-1\"", "index".getBytes(StandardCharsets.UTF_8), 5, IMPORTED_AT);
        GovernanceResource unwritable = new GovernanceResource(GovernanceResourceKind.SYSTEM, "replace-bad",
                null, "x".getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT);

        assertThatThrownBy(() -> resources.replace(index, List.of(unwritable), List.of(
                new GovernanceResourceRef(GovernanceResourceKind.SYSTEM, "replace-shipping", "\"sha256:x\""))))
                .isInstanceOf(RuntimeException.class);

        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "replace-shipping")).isPresent();
        resources.remove(resources.findRefs(GovernanceResourceKind.SYSTEM));
        assertThat(resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)
                .map(GovernanceResource::etag).orElse("")).isNotEqualTo("\"index-1\"");
    }

    /**
     * There is at most one row per index kind, and its key is null - the identity the unique index folds on
     * has to treat that null as its own value rather than as "no constraint at all".
     */
    @Test
    void store_whenTheSystemsIndexIsStoredAgain_thenTheSingleRowIsReplaced() {
        resources.store(new GovernanceResource(GovernanceResourceKind.SYSTEMS_INDEX, null, "\"index-1\"",
                "first".getBytes(StandardCharsets.UTF_8), 5, IMPORTED_AT));

        resources.store(new GovernanceResource(GovernanceResourceKind.SYSTEMS_INDEX, null, "\"index-2\"",
                "second".getBytes(StandardCharsets.UTF_8), 6, IMPORTED_AT));

        assertThat(resources.findRefs(GovernanceResourceKind.SYSTEMS_INDEX)).hasSize(1);
        assertThat(resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)).get()
                .satisfies(stored -> {
                    assertThat(stored.etag()).isEqualTo("\"index-2\"");
                    assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("second");
                });
    }

    /** The rules index, stored beside the systems index, is a different row even though both have a null key. */
    @Test
    void store_whenBothIndexesAreStored_thenEachKindKeepsItsOwnRow() {
        resources.store(new GovernanceResource(GovernanceResourceKind.SYSTEMS_INDEX, null, "\"systems\"",
                "s".getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT));
        resources.store(new GovernanceResource(GovernanceResourceKind.RULES_INDEX, null, "\"rules\"",
                "r".getBytes(StandardCharsets.UTF_8), 1, IMPORTED_AT));

        assertThat(resources.find(GovernanceResourceKind.SYSTEMS_INDEX, null)).get()
                .extracting(GovernanceResource::etag).isEqualTo("\"systems\"");
        assertThat(resources.find(GovernanceResourceKind.RULES_INDEX, null)).get()
                .extracting(GovernanceResource::etag).isEqualTo("\"rules\"");
    }

    @Test
    void remove_thenOnlyTheNamedResourcesGo() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "a"));
        resources.store(resource(GovernanceResourceKind.SYSTEM, "shipping", "b"));
        resources.store(resource(GovernanceResourceKind.RULE, "orders", "c"));

        resources.remove(List.of(new GovernanceResourceRef(GovernanceResourceKind.SYSTEM, "orders", "x")));

        assertThat(resources.findRefs(GovernanceResourceKind.SYSTEM))
                .extracting(GovernanceResourceRef::key)
                .containsExactly("shipping");
        assertThat(resources.findRefs(GovernanceResourceKind.RULE))
                .describedAs("a rule with the same key as the removed system is untouched")
                .hasSize(1);
    }

    @Test
    void confirm_thenTheContentAndTheCheckedTimestampAreUpdated() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "unchanged bytes"));

        resources.confirm(GovernanceResourceKind.SYSTEM, "orders", IMPORTED_AT.plusSeconds(3600));

        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "orders")).get()
                .satisfies(stored -> assertThat(new String(stored.content(), StandardCharsets.UTF_8))
                        .isEqualTo("unchanged bytes"));
    }

    @Test
    void confirm_whenNothingIsStored_thenItDoesNotFail() {
        resources.confirm(GovernanceResourceKind.SYSTEM, "nowhere", IMPORTED_AT);

        assertThat(resources.find(GovernanceResourceKind.SYSTEM, "nowhere")).isEmpty();
    }

    /**
     * Deciding what to fetch must never read the stored bodies, or the entity tags would save nothing.
     * <p>
     * <b>The entity load count is the assertion</b>, not the statement count - see {@code
     * ArchitectureArtifactRepositoryAdapterIT#findRefs_thenTheContentIsNotRead} for why.
     */
    @Test
    void findRefs_thenTheContentIsNotRead() {
        resources.store(resource(GovernanceResourceKind.SYSTEM, "orders", "x".repeat(100_000)));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<GovernanceResourceRef> refs = resources.findRefs(GovernanceResourceKind.SYSTEM);

        assertThat(refs).singleElement()
                .satisfies(ref -> assertThat(ref.etag()).isEqualTo("\"sha256:orders\""));
        assertThat(statistics.getEntityLoadCount())
                .describedAs("the rows are read as a projection, so no resource entity is loaded")
                .isZero();
        assertThat(statistics.getPrepareStatementCount())
                .describedAs("one statement, and no second one to fetch a body")
                .isEqualTo(1);
    }

    private static GovernanceResource resource(GovernanceResourceKind kind, String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new GovernanceResource(kind, key, "\"sha256:" + key + "\"", bytes, bytes.length, IMPORTED_AT);
    }
}
