package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;

import java.util.List;
import java.util.Optional;

/**
 * The OpenAPI specifications and database schemas, as the architecture repository serves them.
 * <p>
 * Kept apart from {@link ArchitectureModelUpstream} because the two have nothing in common: one returns
 * records and is fetched whole, the other returns bytes and is fetched only where an entity tag moved. Here a
 * conditional request is worth making - the upstream tags these from a stored hash, so answering "not
 * modified" costs it nothing and saves a blob on the wire.
 */
public interface ArchitectureArtifactUpstream {

    /**
     * What the architecture repository holds for one environment and kind, without any content.
     *
     * @param knownIndexEtag the tag of the index as it was last seen, or null to ask unconditionally
     * @return empty when the upstream answered "not modified"
     * @throws ArchitectureModelUnavailableException when the architecture repository could not be read
     */
    Optional<Fetched<List<ArchitectureArtifactRef>>> index(String environment, ArchitectureImportKind kind,
                                                           String knownIndexEtag);

    /**
     * The content behind one index entry.
     *
     * @param knownEtag the tag of the copy already stored, or null when there is none
     * @return the content, a confirmation that the stored copy is current, or that the artifact could not be
     *         replicated - see {@link ArtifactFetch} for why the last two are not the same answer
     * @throws ArchitectureModelUnavailableException when the architecture repository could not be read
     */
    ArtifactFetch content(String environment, ArchitectureArtifactRef entry, String knownEtag);
}
