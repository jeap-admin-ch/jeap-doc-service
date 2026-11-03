package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportOutcome;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the governance imports have done, per kind, against a real PostgreSQL.
 */
class GovernanceImportRepositoryAdapterIT extends PostgresTestContainerBase {

    private static final Instant ATTEMPTED_AT = Instant.parse("2026-11-03T10:45:11Z");
    private static final Instant SUCCEEDED_AT = Instant.parse("2026-11-03T10:45:19Z");

    @Autowired
    private GovernanceImportRepository imports;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void state_whenNothingWasEverImported_thenTheStateOfSomethingThatNeverRan() {
        GovernanceImportState state = imports.state(GovernanceResourceKind.SYSTEM);

        assertThat(state.hasEverSucceeded()).isFalse();
        assertThat(state.lastOutcome()).isNull();
        assertThat(state.itemCount()).isZero();
    }

    @Test
    void save_thenEveryFieldComesBackUnchanged() {
        GovernanceImportState stored = new GovernanceImportState(GovernanceResourceKind.SYSTEM,
                "\"systems-index-1\"", true, 42, ATTEMPTED_AT, SUCCEEDED_AT, GovernanceImportOutcome.CHANGED,
                null);

        imports.save(stored);

        assertThat(imports.state(GovernanceResourceKind.SYSTEM)).isEqualTo(stored);
    }

    @Test
    void save_whenAnEntryCouldNotBeReplicated_thenTheRowSaysIncompleteAndCarriesNoSuccess() {
        imports.save(new GovernanceImportState(GovernanceResourceKind.RULE, null, false, 3, ATTEMPTED_AT, null,
                GovernanceImportOutcome.CHANGED, null));

        GovernanceImportState state = imports.state(GovernanceResourceKind.RULE);

        assertThat(state.hasEverSucceeded()).isFalse();
        assertThat(state.conditionalIndexEtag())
                .describedAs("an incomplete run's index answer may not be trusted").isNull();
    }

    @Test
    void save_whenTheKindIsImportedAgain_thenTheRowIsUpdatedRatherThanAdded() {
        imports.save(new GovernanceImportState(GovernanceResourceKind.SYSTEM, "\"systems-index-1\"", true, 1,
                ATTEMPTED_AT, SUCCEEDED_AT, GovernanceImportOutcome.CHANGED, null));

        imports.save(new GovernanceImportState(GovernanceResourceKind.SYSTEM, "\"systems-index-2\"", true, 2,
                SUCCEEDED_AT, SUCCEEDED_AT, GovernanceImportOutcome.UNCHANGED, null));

        GovernanceImportState state = imports.state(GovernanceResourceKind.SYSTEM);
        assertThat(state.indexEtag()).isEqualTo("\"systems-index-2\"");
        assertThat(state.lastOutcome()).isEqualTo(GovernanceImportOutcome.UNCHANGED);
    }

    /**
     * An outcome a newer version wrote reads as none rather than failing the row: what an operator loses by it
     * is one word, and what they would lose by an exception is the whole state of that kind - including the
     * timestamps the staleness alarm is read from.
     */
    @Test
    void state_whenTheStoredOutcomeIsUnknownToThisVersion_thenTheRestOfTheRowIsStillRead() {
        imports.save(new GovernanceImportState(GovernanceResourceKind.RULE, "an-etag", true, 7, ATTEMPTED_AT,
                SUCCEEDED_AT, GovernanceImportOutcome.CHANGED, null));
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.createNativeQuery("update governance_import set last_outcome = 'RETICULATED' "
                                            + "where kind = 'RULE'")
                    .executeUpdate();
            entityManager.getTransaction().commit();
        }

        GovernanceImportState state = imports.state(GovernanceResourceKind.RULE);

        assertThat(state.lastOutcome()).isNull();
        assertThat(state.indexEtag()).isEqualTo("an-etag");
        assertThat(state.lastSuccessAt()).isEqualTo(SUCCEEDED_AT);
        assertThat(state.itemCount()).isEqualTo(7);
    }
}
