package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * What the last import of one environment and kind did.
 */
interface ArchitectureImportJpaRepository
        extends JpaRepository<ArchitectureImportEntity, ArchitectureImportEntity.Key> {


    Optional<ArchitectureImportEntity> findByEnvironmentAndKind(String environment, String kind);
}
