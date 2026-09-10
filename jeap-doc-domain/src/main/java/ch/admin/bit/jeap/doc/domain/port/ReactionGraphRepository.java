package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.StoredReactionGraph;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Where the replicated reaction graphs are kept.
 * <p>
 * The shape of {@link ArchitectureArtifactRepository}, which answers the same questions - including the
 * <i>confirm</i> that lets a run record having checked a graph without rewriting it.
 * <p>
 * A graph names its system, component or message type by name and does not point into the model, for the reason
 * an artifact does not: the model is replaced wholesale on every import, and a reference into it would either
 * be deleted with it or stop it from being replaceable. <b>There is deliberately no orphan sweep either.</b>
 * The import resolves every name the observer offers against the stored model <i>before</i> it compares what is
 * listed with what is stored, so a system that has left the model stops being listed and is pruned by that
 * comparison - and the floor under the prune, which refuses an index that lists nothing while something is
 * stored, is what keeps a failed model import from emptying the table.
 */
public interface ReactionGraphRepository {

    /**
     * Every graph of one environment and kind, <b>without its bytes</b>: what deciding which graphs to fetch
     * works on. Reading the graphs to compare entity tags would defeat the whole replication.
     */
    List<ReactionGraphRef> findRefs(String environment, ArchitectureImportKind kind);

    /**
     * One graph, addressed as a page is generated for it. <b>The only way to read one</b>, for the replication
     * as well as for a generation run - there is no read of a whole environment's graphs, because the largest
     * of them is the whole reaction graph of a system and a page draws one at a time.
     *
     * @param system  the system a component's graph belongs to, and {@code ""} for the kinds that are no
     *                component's. It is part of the address because two systems may each call a component
     *                {@code gateway}
     * @param variant {@code ""} for a graph that has no variant
     */
    Optional<StoredReactionGraph> find(String environment, ArchitectureImportKind kind, String name,
                                       String system, String variant);

    /**
     * Every variant of one message type's graph, sorted by variant.
     * <p>
     * The one read that answers more than one graph, and it is bounded by what a message type can
     * have: the page of a message draws a diagram per variant, and which variants exist is not known
     * from the model - only the observer knows.
     */
    List<StoredReactionGraph> findVariants(String environment, String messageType);

    void store(StoredReactionGraph graph);

    /**
     * Records that the stored graph is still the current one, without rewriting its bytes.
     */
    void confirm(String environment, ArchitectureImportKind kind, String name, String system, String variant,
                 Instant checkedAt);

    void remove(Collection<ReactionGraphRef> graphs);
}
