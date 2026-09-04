package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;

import java.util.List;

/**
 * What the imports of this instance have done, per environment and kind.
 */
public interface ArchitectureImportRepository {

    /**
     * The state of one environment and kind, never null - an environment and kind that has never been imported
     * reads as {@link ArchitectureImportState#none}.
     */
    ArchitectureImportState state(String environment, ArchitectureImportKind kind);

    /** Every state row, for the gauges. */
    List<ArchitectureImportState> states();

    void save(ArchitectureImportState state);
}
