package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportOutcome;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.port.GovernanceImportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the governance imports of this instance have done, per kind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GovernanceImportRepositoryAdapter implements GovernanceImportRepository {

    private final GovernanceImportJpaRepository imports;

    @Override
    @Transactional(readOnly = true)
    public GovernanceImportState state(GovernanceResourceKind kind) {
        return imports.findById(kind.name())
                .map(GovernanceImportRepositoryAdapter::state)
                .orElseGet(() -> GovernanceImportState.none(kind));
    }

    @Override
    @Transactional
    public void save(GovernanceImportState state) {
        GovernanceImportEntity entity = imports.findById(state.kind().name())
                .orElseGet(GovernanceImportEntity::new);
        entity.setKind(state.kind().name());
        entity.setIndexEtag(state.indexEtag());
        entity.setComplete(state.complete());
        entity.setItemCount(state.itemCount());
        entity.setLastAttemptAt(state.lastAttemptAt());
        entity.setLastSuccessAt(state.lastSuccessAt());
        entity.setLastOutcome(state.lastOutcome() == null ? null : state.lastOutcome().name());
        entity.setFailureReason(state.failureReason());
        imports.save(entity);
    }

    private static GovernanceImportState state(GovernanceImportEntity entity) {
        return new GovernanceImportState(GovernanceResourceKind.valueOf(entity.getKind()), entity.getIndexEtag(),
                entity.isComplete(), entity.getItemCount(), entity.getLastAttemptAt(), entity.getLastSuccessAt(),
                outcomeOf(entity), entity.getFailureReason());
    }

    /**
     * The outcome as this adapter stored it, or null where a newer version wrote one this one does not know -
     * see {@code ArchitectureImportRepositoryAdapter} for the same rule.
     */
    private static GovernanceImportOutcome outcomeOf(GovernanceImportEntity entity) {
        if (entity.getLastOutcome() == null) {
            return null;
        }
        try {
            return GovernanceImportOutcome.valueOf(entity.getLastOutcome());
        } catch (IllegalArgumentException unknownToThisVersion) {
            return null;
        }
    }
}
