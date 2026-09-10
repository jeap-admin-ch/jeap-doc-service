package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * An environment whose stage runs no reaction observer - which is the default, and what most of these tests
 * are about. It answers no graph, which is a site with no runtime views in it.
 */
class NoReactions implements ReactionGraphRepository, ReactionGraphContent {

    static final NoReactions INSTANCE = new NoReactions();

    @Override
    public List<ReactionGraphRef> findRefs(String environment, ArchitectureImportKind kind) {
        return List.of();
    }

    @Override
    public Optional<StoredReactionGraph> find(String environment, ArchitectureImportKind kind, String name,
                                              String system, String variant) {
        return Optional.empty();
    }

    @Override
    public List<StoredReactionGraph> findVariants(String environment, String messageType) {
        return List.of();
    }

    @Override
    public void store(StoredReactionGraph graph) {
        throw new UnsupportedOperationException("A build stores no reaction graph.");
    }

    @Override
    public void confirm(String environment, ArchitectureImportKind kind, String name, String system,
                        String variant, Instant checkedAt) {
        throw new UnsupportedOperationException("A build confirms no reaction graph.");
    }

    @Override
    public void remove(Collection<ReactionGraphRef> graphs) {
        throw new UnsupportedOperationException("A build removes no reaction graph.");
    }

    @Override
    public ObservedReactions read(StoredReactionGraph graph) {
        return ObservedReactions.empty();
    }
}
