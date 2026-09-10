package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraph;

/**
 * What one conditional fetch of a reaction graph came back with.
 * <p>
 * The three answers of {@link ArtifactFetch}, over a graph. It is a type of its own rather than a generified
 * {@code ArtifactFetch} because the two payloads have nothing to do with each other, and a sealed interface
 * that serves both would make every {@code switch} over it prove which one it is looking at.
 */
public sealed interface GraphFetch {

    /** A graph that is newer than what is stored, or that nothing was stored for yet. */
    record Stored(ReactionGraph graph) implements GraphFetch {
    }

    /** The observer answered "not modified" against the tag of the stored graph: it is still current. */
    record Unchanged() implements GraphFetch {
    }

    /**
     * The graph was not replicated - it went away between the index and the fetch, the answer carried no
     * entity tag, the body was over the cap. The reason has been logged where it was found; it is carried here
     * for the summary of the run.
     */
    record Skipped(String reason) implements GraphFetch {
    }

    static GraphFetch stored(ReactionGraph graph) {
        return new Stored(graph);
    }

    static GraphFetch unchanged() {
        return new Unchanged();
    }

    static GraphFetch skipped(String reason) {
        return new Skipped(reason);
    }
}
