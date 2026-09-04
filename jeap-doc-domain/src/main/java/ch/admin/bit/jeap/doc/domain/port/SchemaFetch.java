package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.MessageVersionSchemas;

/**
 * What one conditional fetch of a message type version came back with.
 * <p>
 * Three answers, for the same reason {@link ArtifactFetch} has three: content to store, a "not modified" that
 * confirms what is stored, and a version that could not be replicated - withdrawn between the index and the
 * fetch, a content URL that cannot be fetched, an answer without an entity tag. The third is not the second.
 * A confirmed version keeps its row and has its tag left alone; a skipped one is offered again next run.
 */
public sealed interface SchemaFetch {

    /** Schemas that are newer than what is stored, or that nothing was stored for yet. */
    record Stored(MessageVersionSchemas version) implements SchemaFetch {
    }

    /** The upstream answered "not modified" against the tag of the stored row: it is still current. */
    record Unchanged() implements SchemaFetch {
    }

    /**
     * The version was not replicated. The reason has been logged where it was found; it is carried here for
     * the summary of the run.
     */
    record Skipped(String reason) implements SchemaFetch {
    }

    static SchemaFetch stored(MessageVersionSchemas version) {
        return new Stored(version);
    }

    static SchemaFetch unchanged() {
        return new Unchanged();
    }

    static SchemaFetch skipped(String reason) {
        return new Skipped(reason);
    }
}
