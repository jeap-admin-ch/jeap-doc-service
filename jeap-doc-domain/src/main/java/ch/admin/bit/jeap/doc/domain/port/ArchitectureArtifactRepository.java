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
     * The content of one artifact, for whoever renders it.
     * <p>
     * <b>Nothing renders them yet.</b> The OpenAPI specifications and the database schemas are replicated and
     * are not on a page, so this and {@link #findAll} are the read side of a generator that does not exist -
     * kept, rather than deleted and written again, because the replication beside them is what is expensive to
     * get right and both are covered by the adapter's tests.
     */
    Optional<ArchitectureArtifact> find(String environment, ArchitectureImportKind kind, String system,
                                        String component);

    /** Every artifact of one system, for a generation run that documents it - see {@link #find}. */
    List<ArchitectureArtifact> findAll(String environment, ArchitectureImportKind kind, String system);

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
