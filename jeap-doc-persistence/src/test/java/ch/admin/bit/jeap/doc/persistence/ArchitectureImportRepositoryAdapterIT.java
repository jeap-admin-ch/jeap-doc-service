package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the imports of one environment and kind have done, against a real PostgreSQL.
 * <p>
 * The row is the evidence an operator reads and the memory a run uses to skip work the one before it already
 * did, so what matters here is that every field of it survives the round trip - and that an outcome this
 * version does not know does not make the rest of the row unreadable.
 */
class ArchitectureImportRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant ATTEMPTED_AT = Instant.parse("2026-08-28T08:00:11Z");
    private static final Instant SUCCEEDED_AT = Instant.parse("2026-08-28T08:00:19Z");

    @Autowired
    private ArchitectureImportRepository imports;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void state_whenNothingWasEverImported_thenTheStateOfSomethingThatNeverRan() {
        ArchitectureImportState state = imports.state("never-run", ArchitectureImportKind.MODEL);

        assertThat(state.hasEverSucceeded()).isFalse();
        assertThat(state.lastOutcome()).isNull();
        assertThat(state.itemCount()).isZero();
    }

    @Test
    void save_thenEveryFieldComesBackUnchanged() {
        ArchitectureImportState stored = new ArchitectureImportState("round-trip",
                ArchitectureImportKind.MODEL, "a-content-hash", null, true, 42, ATTEMPTED_AT, SUCCEEDED_AT,
                ImportOutcome.REPLACED, null);

        imports.save(stored);

        assertThat(imports.state("round-trip", ArchitectureImportKind.MODEL)).isEqualTo(stored);
    }

    /**
     * The outcome of a run that stopped at its deadline. It is neither a success nor a failure, and reading it
     * off the timestamps would report it as the latter - which is why it is a column of its own.
     */
    @Test
    void save_whenTheRunWasTruncated_thenTheRowSaysPartialAndCarriesNoSuccess() {
        imports.save(new ArchitectureImportState("truncated", ArchitectureImportKind.OPENAPI_SPEC, null,
                "an-etag", false, 20, ATTEMPTED_AT, null, ImportOutcome.PARTIAL, null));

        ArchitectureImportState state = imports.state("truncated", ArchitectureImportKind.OPENAPI_SPEC);

        assertThat(state.lastOutcome()).isEqualTo(ImportOutcome.PARTIAL);
        assertThat(state.hasEverSucceeded()).isFalse();
        assertThat(state.conditionalIndexEtag())
                .describedAs("a truncated run's index answer may not be trusted").isNull();
    }

    @Test
    void save_whenTheKindIsImportedAgain_thenTheRowIsUpdatedRatherThanAdded() {
        imports.save(new ArchitectureImportState("updated", ArchitectureImportKind.MODEL, "first", null, true,
                1, ATTEMPTED_AT, SUCCEEDED_AT, ImportOutcome.REPLACED, null));

        imports.save(new ArchitectureImportState("updated", ArchitectureImportKind.MODEL, "second", null, true,
                2, SUCCEEDED_AT, SUCCEEDED_AT, ImportOutcome.UNCHANGED, null));

        assertThat(imports.states()).filteredOn(state -> "updated".equals(state.environment()))
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.contentHash()).isEqualTo("second");
                    assertThat(state.lastOutcome()).isEqualTo(ImportOutcome.UNCHANGED);
                });
    }

    /**
     * A row of a kind this version has no constant for is left out rather than thrown over.
     * <p>
     * The only caller of {@code states()} binds the staleness gauges while the context refreshes, so an
     * exception here is an <b>instance that does not start</b> - which is what a rollback from a version that
     * added an import kind would have caused.
     */
    @Test
    void states_whenARowIsOfAKindThisVersionDoesNotKnow_thenTheOtherRowsAreStillRead() {
        imports.save(new ArchitectureImportState("from-a-newer-version", ArchitectureImportKind.MODEL, "hash",
                null, true, 3, ATTEMPTED_AT, SUCCEEDED_AT, ImportOutcome.REPLACED, null));
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.createNativeQuery("update architecture_import set kind = 'REACTIONS' "
                                            + "where environment = 'from-a-newer-version'")
                    .executeUpdate();
            entityManager.getTransaction().commit();
        }

        assertThat(imports.states()).noneSatisfy(state ->
                assertThat(state.environment()).isEqualTo("from-a-newer-version"));
    }

    /**
     * An outcome a newer version wrote reads as none rather than failing the row: what an operator loses by it
     * is one word, and what they would lose by an exception is the whole state of that environment - including
     * the timestamps the staleness alarm is read from.
     */
    @Test
    void state_whenTheStoredOutcomeIsUnknownToThisVersion_thenTheRestOfTheRowIsStillRead() {
        imports.save(new ArchitectureImportState("from-the-future", ArchitectureImportKind.MODEL, "hash", null,
                true, 7, ATTEMPTED_AT, SUCCEEDED_AT, ImportOutcome.REPLACED, null));
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.createNativeQuery("update architecture_import set last_outcome = 'RETICULATED' "
                                            + "where environment = 'from-the-future'")
                    .executeUpdate();
            entityManager.getTransaction().commit();
        }

        ArchitectureImportState state = imports.state("from-the-future", ArchitectureImportKind.MODEL);

        assertThat(state.lastOutcome()).isNull();
        assertThat(state.contentHash()).isEqualTo("hash");
        assertThat(state.lastSuccessAt()).isEqualTo(SUCCEEDED_AT);
        assertThat(state.itemCount()).isEqualTo(7);
    }
}
