package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The replicated OpenAPI specifications and database schemas.
 * <p>
 * Every artifact is its own transaction while it is being replicated: these are blobs, and holding a whole
 * environment's worth in memory to write them atomically would buy a consistency nobody needs.
 */
@Component
@RequiredArgsConstructor
class ArchitectureArtifactRepositoryAdapter implements ArchitectureArtifactRepository {

    private final ArchitectureArtifactJpaRepository artifacts;

    @Override
    @Transactional(readOnly = true)
    public List<ArchitectureArtifactRef> findRefs(String environment, ArchitectureImportKind kind) {
        return artifacts.findByEnvironmentAndKindOrderBySystemNameAscComponentNameAsc(environment, kind.name())
                .stream()
                .map(view -> new ArchitectureArtifactRef(view.getEnvironment(),
                        ArchitectureImportKind.valueOf(view.getKind()), view.getSystemName(),
                        view.getComponentName(), view.getVersion(), view.getEtag(), view.getModifiedAt(), null,
                        view.getCheckedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind, String system,
                                               String component) {
        return artifacts.findOne(environment, kind.name(), system, component)
                .map(ArchitectureArtifactRepositoryAdapter::artifact);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchitectureArtifact> findAll(String environment, ArchitectureImportKind kind, String system) {
        return artifacts.findAllOfSystem(environment, kind.name(), system).stream()
                .map(ArchitectureArtifactRepositoryAdapter::artifact).toList();
    }

    @Override
    @Transactional
    public void store(ArchitectureArtifact artifact) {
        ArchitectureArtifactEntity entity = artifacts
                .findOne(artifact.environment(), artifact.kind().name(), artifact.system(), artifact.component())
                .orElseGet(ArchitectureArtifactEntity::new);
        entity.setEnvironment(artifact.environment());
        entity.setKind(artifact.kind().name());
        entity.setSystemName(artifact.system());
        entity.setComponentName(artifact.component());
        entity.setVersion(artifact.version());
        entity.setEtag(artifact.etag());
        entity.setContent(artifact.content());
        entity.setSizeInBytes(artifact.sizeInBytes());
        entity.setModifiedAt(artifact.modifiedAt());
        entity.setReplicatedAt(artifact.replicatedAt());
        entity.setCheckedAt(artifact.replicatedAt());
        artifacts.save(entity);
    }

    @Override
    @Transactional
    public void confirm(String environment, ArchitectureImportKind kind, String system, String component,
                        Instant checkedAt) {
        artifacts.confirm(environment, kind.name(), system, component, checkedAt);
    }

    /**
     * One statement per artifact - see {@link ArchitectureArtifactJpaRepository#removeOne}. A prune removes
     * nothing on almost every run, so the loop is not what this costs.
     */
    @Override
    @Transactional
    public void remove(Collection<ArchitectureArtifactRef> refs) {
        for (ArchitectureArtifactRef ref : refs) {
            artifacts.removeOne(ref.environment(), ref.kind().name(), ref.system(), ref.component());
        }
    }

    @Override
    @Transactional
    public int removeOrphans(String environment) {
        return artifacts.removeOrphans(environment);
    }

    private static ArchitectureArtifact artifact(ArchitectureArtifactEntity entity) {
        return new ArchitectureArtifact(entity.getEnvironment(),
                ArchitectureImportKind.valueOf(entity.getKind()), entity.getSystemName(),
                entity.getComponentName(), entity.getVersion(), entity.getEtag(), entity.getContent(),
                entity.getSizeInBytes(), entity.getModifiedAt(), entity.getReplicatedAt());
    }
}
