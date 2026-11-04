package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.util.Arrays;
import java.util.Objects;

/**
 * One reaction graph as the observer served it: the bytes, and the two names for them.
 * <p>
 * The bytes are stored as they arrived. The domain importer does not parse them - what a graph is drawn as is
 * decided when a page is generated, so the shape of the payload is the observer's business and the generator's
 * and not the replication's.
 *
 * @param etag        the entity tag verbatim, quotes and all. What the next run sends as {@code If-None-Match}
 * @param fingerprint the fingerprint the payload carries. The same value the tag is built from, kept because it
 *                    is the observer's own name for the graph and a graph is explained by it
 * @param sourceFormat optional format marker for a full snapshot, kept beside its conditional tag
 * @param data        the bytes, with a local format envelope for full snapshots
 * @param drawableNodes how many nodes of the payload this version would draw - counted by the adapter that
 *                    read the envelope, which is the one place that knows the shape. <b>Not the import
 *                    looking inside a graph</b>: what is stored is still the bytes as they arrived, and this
 *                    is a fact <i>about</i> them that the generator would otherwise have to be asked for, one
 *                    graph at a time, to know whether a page exists to link to
 */
public record ReactionGraph(String etag, String fingerprint, byte[] data, int drawableNodes, String sourceFormat) {

    public ReactionGraph(String etag, String fingerprint, byte[] data, int drawableNodes) {
        this(etag, fingerprint, data, drawableNodes, null);
    }

    /** Whether a page is written for this graph at all: an empty graph is a chapter that is not there. */
    public boolean isDrawable() {
        return drawableNodes > 0;
    }

    /**
     * Value equality over the bytes too, which the generated one would not give for an array.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ReactionGraph(var otherEtag, var otherFingerprint, var otherData, var otherDrawableNodes, var otherSourceFormat)
               && Objects.equals(etag, otherEtag)
               && Objects.equals(fingerprint, otherFingerprint)
               && Arrays.equals(data, otherData)
               && drawableNodes == otherDrawableNodes
               && Objects.equals(sourceFormat, otherSourceFormat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(etag, fingerprint, Arrays.hashCode(data), drawableNodes, sourceFormat);
    }

    /**
     * Without the bytes, so that a log line or a test failure does not print a whole graph.
     */
    @Override
    public String toString() {
        return "ReactionGraph[etag=%s fingerprint=%s %d bytes %d drawable]"
                .formatted(etag, fingerprint, data == null ? 0 : data.length, drawableNodes);
    }
}
