package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Where the replicated OpenAPI specifications and database schemas are kept.
 * <p>
 * An artifact names its system and component by name and does not point into the model. The model is replaced
 * wholesale on every import, and a reference into it would either be deleted with it - throwing away a blob
 * that has not changed - or stop the model from being replaceable at all. {@link #removeOrphans} is what
 * cleans up instead, and it is the one place the two halves meet.
 */
public interface ArchitectureArtifactRepository {

    /**
     * Every artifact of one environment and kind, <b>without its content</b>: what deciding which artifacts to
     * fetch works on. Reading the blobs to compare entity tags would defeat the whole replication.
     */
    List<ArchitectureArtifactRef> findRefs(String environment, ArchitectureImportKind kind);

    /**
     * The content of one artifact, addressed by the component that published it. <b>The only way to read
     * one</b>, for the replication as well as for a generation run.
     * <p>
     * There is deliberately no read of a whole system's or a whole environment's artifacts. A specification
     * is among the largest text this service stores, and neither a replication nor a generation run needs
     * more than one of them at a time - a read that answered a list would put a component count's multiple of
     * {@code max-artifact-size} live at once. Both callers loop over the components instead; the names come
     * from the stored model or from {@link #findRefs}, and each lookup is served by the unique index.
     */
    Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind, String system,
                                        String component);

    void store(ArchitectureArtifact artifact);

    /**
     * Records that the stored copy is still the current one, without rewriting its content.
     */
    void confirm(String environment, ArchitectureImportKind kind, String system, String component,
                 Instant checkedAt);

    void remove(Collection<ArchitectureArtifactRef> artifacts);

    /**
     * Deletes the artifacts of one environment whose system or component the stored model does not have.
     * Called at the end of a model import, in its transaction.
     *
     * @return how many were deleted
     */
    int removeOrphans(String environment);
}
