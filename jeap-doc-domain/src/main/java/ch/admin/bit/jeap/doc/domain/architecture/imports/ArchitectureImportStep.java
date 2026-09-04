package ch.admin.bit.jeap.doc.domain.architecture.imports;

/**
 * One kind of thing an import reads from the architecture repository.
 * <p>
 * The steps of one environment run in order under the same job, each under its own lock, and one that fails
 * does not stop the next. The model runs first, because it decides which systems and components exist and
 * therefore which artifacts are orphans.
 */
interface ArchitectureImportStep {

    ArchitectureImportKind kind();

    /**
     * Imports one environment. Never throws: what went wrong is the outcome and the reason recorded with it,
     * because a failing import must not reach the build that reads what was imported before it.
     */
    ImportOutcome run(String environment, Deadline deadline);
}
