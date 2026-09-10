package ch.admin.bit.jeap.doc.persistence;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The replicated reaction graphs.
 * <p>
 * Every graph is its own transaction while it is being replicated: these are blobs, and holding a whole
 * environment's worth in memory to write them atomically would buy a consistency nobody needs.
 */
@Component
@RequiredArgsConstructor
class ReactionGraphRepositoryAdapter implements ReactionGraphRepository {

    private final ReactionGraphJpaRepository graphs;

    @Override
    @Transactional(readOnly = true)
    public List<ReactionGraphRef> findRefs(String environment, ArchitectureImportKind kind) {
        return graphs.findByEnvironmentAndKindOrderByNameAscVariantAsc(environment, kind.name()).stream()
                .map(view -> new ReactionGraphRef(view.getEnvironment(),
                        ArchitectureImportKind.valueOf(view.getKind()), view.getName(),
                        view.getUpstreamName(), view.getVariant(), view.getSystemName(), view.getEtag(), null,
                        view.getCheckedAt(), view.getDrawableNodes() > 0))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredReactionGraph> find(String environment, ArchitectureImportKind kind, String name,
                                              String system, String variant) {
        return graphs.findOne(environment, kind.name(), name, system, variant)
                .map(ReactionGraphRepositoryAdapter::graph);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredReactionGraph> findVariants(String environment, String messageType) {
        return graphs.findVariants(environment, ArchitectureImportKind.MESSAGE_REACTIONS.name(),
                        messageType).stream()
                .map(ReactionGraphRepositoryAdapter::graph)
                .toList();
    }

    @Override
    @Transactional
    public void store(StoredReactionGraph graph) {
        ReactionGraphEntity entity = graphs
                .findOne(graph.environment(), graph.kind().name(), graph.name(), graph.system(),
                        graph.variant())
                .orElseGet(ReactionGraphEntity::new);
        entity.setEnvironment(graph.environment());
        entity.setKind(graph.kind().name());
        entity.setName(graph.name());
        entity.setVariant(graph.variant());
        entity.setSystemName(graph.system());
        entity.setUpstreamName(graph.upstreamName());
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
    public void confirm(String environment, ArchitectureImportKind kind, String name, String system,
                        String variant, Instant checkedAt) {
        graphs.confirm(environment, kind.name(), name, system, variant, checkedAt);
    }

    /**
     * One statement per graph - see {@link ReactionGraphJpaRepository#removeOne}. A prune removes nothing on
     * almost every run, so the loop is not what this costs.
     */
    @Override
    @Transactional
    public void remove(Collection<ReactionGraphRef> refs) {
        for (ReactionGraphRef ref : refs) {
            graphs.removeOne(ref.environment(), ref.kind().name(), ref.name(), ref.system(),
                    ref.variant());
        }
    }

    private static StoredReactionGraph graph(ReactionGraphEntity entity) {
        return new StoredReactionGraph(entity.getEnvironment(),
                ArchitectureImportKind.valueOf(entity.getKind()), entity.getName(), entity.getVariant(),
                entity.getSystemName(), entity.getUpstreamName(), entity.getEtag(), entity.getFingerprint(),
                entity.getGraphData(), entity.getDrawableNodes(), entity.getImportedAt());
    }
}
