package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** One full snapshot per environment. Legacy address columns stay empty for schema compatibility. */
@Component
@RequiredArgsConstructor
class ReactionGraphRepositoryAdapter implements ReactionGraphRepository {
    private static final String KIND = ArchitectureImportKind.FULL_REACTIONS.name();
    private final ReactionGraphJpaRepository graphs;

    @Override
    @Transactional(readOnly = true)
    public Optional<ReactionGraphRef> findRef(String environment) {
        return graphs.findByEnvironmentAndKindAndNameAndSystemNameAndVariant(environment, KIND, "", "", "")
                .map(view -> new ReactionGraphRef(view.getEnvironment(), view.getUpstreamName(),
                        view.getEtag(), view.getImportedAt(), view.getCheckedAt(), view.getDrawableNodes() > 0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredReactionGraph> find(String environment) {
        return graphs.findOne(environment, KIND, "", "", "").map(ReactionGraphRepositoryAdapter::graph);
    }

    @Override
    @Transactional
    public void store(StoredReactionGraph graph) {
        ReactionGraphEntity entity = graphs.findOne(graph.environment(), KIND, "", "", "")
                .orElseGet(ReactionGraphEntity::new);
        entity.setEnvironment(graph.environment());
        entity.setKind(KIND);
        entity.setName("");
        entity.setVariant("");
        entity.setSystemName("");
        entity.setUpstreamName(graph.sourceFormat() == null ? "" : graph.sourceFormat());
        entity.setEtag(graph.etag());
        entity.setFingerprint(graph.fingerprint());
        entity.setGraphData(graph.data());
        entity.setSizeInBytes(graph.data().length);
        entity.setDrawableNodes(graph.drawableNodes());
        entity.setImportedAt(graph.importedAt());
        entity.setCheckedAt(graph.importedAt());
        graphs.save(entity);
    }

    @Override
    @Transactional
    public void confirm(String environment, Instant checkedAt) {
        graphs.confirm(environment, KIND, "", "", "", checkedAt);
    }

    private static StoredReactionGraph graph(ReactionGraphEntity entity) {
        return new StoredReactionGraph(entity.getEnvironment(), entity.getUpstreamName(), entity.getEtag(),
                entity.getFingerprint(), entity.getGraphData(), entity.getDrawableNodes(), entity.getImportedAt());
    }
}
