package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceImportState;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;

/**
 * What the last governance import of one kind did - the evidence an operator reads and the memory that lets a
 * run skip work the one before it already did. Plays the role for the governance import that {@code
 * ArchitectureImportRepository} plays for the architecture import.
 */
public interface GovernanceImportRepository {

    GovernanceImportState state(GovernanceResourceKind kind);

    void save(GovernanceImportState state);
}
