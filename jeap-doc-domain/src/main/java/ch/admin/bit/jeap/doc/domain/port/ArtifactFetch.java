package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;

/**
 * What one conditional fetch of an artifact came back with.
 * <p>
 * Three answers, because the importer has to tell them apart: content to store, a "not modified" that confirms
 * the stored copy, and an artifact that could not be replicated - gone between the index and the fetch, a
 * content URL that cannot be fetched, an answer without an entity tag, a body over the size cap. The third is
 * not the second. A confirmed copy is stored; a skipped artifact is not, and the index tag may not be trusted
 * after one, or the artifact would never be offered again.
 */
public sealed interface ArtifactFetch {

    /** Content that is newer than what is stored, or that nothing was stored for yet. */
    record Stored(ArchitectureArtifact artifact) implements ArtifactFetch {
    }

    /** The upstream answered "not modified" against the tag of the stored copy: it is still current. */
    record Unchanged() implements ArtifactFetch {
    }

    /**
     * The artifact was not replicated. The reason has been logged where it was found; it is carried here for
     * the summary of the run.
     */
    record Skipped(String reason) implements ArtifactFetch {
    }

    static ArtifactFetch stored(ArchitectureArtifact artifact) {
        return new Stored(artifact);
    }

    static ArtifactFetch unchanged() {
        return new Unchanged();
    }

    static ArtifactFetch skipped(String reason) {
        return new Skipped(reason);
    }
}
