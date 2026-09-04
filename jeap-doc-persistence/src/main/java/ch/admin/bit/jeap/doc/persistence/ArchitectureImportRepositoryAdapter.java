package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureImportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What the imports of this instance have done, per environment and kind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ArchitectureImportRepositoryAdapter implements ArchitectureImportRepository {

    private final ArchitectureImportJpaRepository imports;

    @Override
    @Transactional(readOnly = true)
    public ArchitectureImportState state(String environment, ArchitectureImportKind kind) {
        return imports.findByEnvironmentAndKind(environment, kind.name())
                .map(ArchitectureImportRepositoryAdapter::state)
                .orElseGet(() -> ArchitectureImportState.none(environment, kind));
    }

    /**
     * Every state row - <b>except one this version cannot read</b>.
     * <p>
     * The kinds are stored by name, and a row written by a newer version names a kind this one has no constant
     * for. Its only caller binds the staleness gauges while the context refreshes, so letting that row through
     * as an exception would turn a rollback into an <b>instance that does not start</b>. Skipping it costs one
     * gauge of a kind this version does not import anyway.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ArchitectureImportState> states() {
        return imports.findAll().stream()
                .filter(ArchitectureImportRepositoryAdapter::isKindOfThisVersion)
                .map(ArchitectureImportRepositoryAdapter::state)
                .toList();
    }

    private static boolean isKindOfThisVersion(ArchitectureImportEntity entity) {
        try {
            ArchitectureImportKind.valueOf(entity.getKind());
            return true;
        } catch (IllegalArgumentException unknownToThisVersion) {
            log.warn("The import state of the environment {} is of the kind '{}', which this version of the "
                     + "doc service does not know; it is left out. A newer version wrote it.",
                    entity.getEnvironment(), entity.getKind());
            return false;
        }
    }

    @Override
    @Transactional
    public void save(ArchitectureImportState state) {
        ArchitectureImportEntity entity = imports
                .findByEnvironmentAndKind(state.environment(), state.kind().name())
                .orElseGet(ArchitectureImportEntity::new);
        entity.setEnvironment(state.environment());
        entity.setKind(state.kind().name());
        entity.setContentHash(state.contentHash());
        entity.setIndexEtag(state.indexEtag());
        entity.setComplete(state.complete());
        entity.setItemCount(state.itemCount());
        entity.setLastAttemptAt(state.lastAttemptAt());
        entity.setLastSuccessAt(state.lastSuccessAt());
        entity.setLastOutcome(state.lastOutcome() == null ? null : state.lastOutcome().name());
        entity.setFailureReason(state.failureReason());
        imports.save(entity);
    }

    private static ArchitectureImportState state(ArchitectureImportEntity entity) {
        return new ArchitectureImportState(entity.getEnvironment(),
                ArchitectureImportKind.valueOf(entity.getKind()), entity.getContentHash(), entity.getIndexEtag(),
                entity.isComplete(), entity.getItemCount(), entity.getLastAttemptAt(), entity.getLastSuccessAt(),
                outcomeOf(entity), entity.getFailureReason());
    }

    /**
     * The outcome as this adapter stored it, or null where a newer version wrote one this one does not know.
     * Falling back rather than failing: one unknown constant must not make a whole state row unreadable, and
     * what an operator loses by it is one word beside a row that still carries its timestamps.
     */
    private static ImportOutcome outcomeOf(ArchitectureImportEntity entity) {
        if (entity.getLastOutcome() == null) {
            return null;
        }
        try {
            return ImportOutcome.valueOf(entity.getLastOutcome());
        } catch (IllegalArgumentException unknownToThisVersion) {
            return null;
        }
    }
}
