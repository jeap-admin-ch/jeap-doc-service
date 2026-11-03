package ch.admin.bit.jeap.doc.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code kind} is the primary key, so {@link #findById} already serves what the adapter needs. */
interface GovernanceImportJpaRepository extends JpaRepository<GovernanceImportEntity, String> {
}
