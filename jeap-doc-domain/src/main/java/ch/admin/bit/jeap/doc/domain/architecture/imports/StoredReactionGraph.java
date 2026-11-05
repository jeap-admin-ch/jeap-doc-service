package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One complete environment snapshot, independent of the separately replaced architecture model.
 *
 * @param sourceFormat the source API format; empty identifies legacy unwrapped V1 bytes
 * @param etag the upstream entity tag verbatim, valid only for this source format
 * @param fingerprint the observer's fingerprint, or null when absent
 * @param data the stored graph with its local format envelope, or legacy unwrapped V1 bytes
 * @param drawableNodes the validated node count, used to guard against an empty replacement
 * @param importedAt when these bytes were stored; confirming an unchanged snapshot does not advance it
 */
public record StoredReactionGraph(String environment, String sourceFormat, String etag, String fingerprint,
                                  byte[] data, int drawableNodes, Instant importedAt) {
    /** Compares the graph bytes by value rather than array identity. */
    @Override
    public boolean equals(Object other) {
        return other instanceof StoredReactionGraph(var otherEnvironment, var otherSourceFormat, var otherEtag,
                var otherFingerprint, var otherData, var otherDrawableNodes, var otherImportedAt)
                && Objects.equals(environment, otherEnvironment)
                && Objects.equals(sourceFormat, otherSourceFormat)
                && Objects.equals(etag, otherEtag) && Objects.equals(fingerprint, otherFingerprint)
                && Arrays.equals(data, otherData) && drawableNodes == otherDrawableNodes
                && Objects.equals(importedAt, otherImportedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environment, sourceFormat, etag, fingerprint, Arrays.hashCode(data), drawableNodes, importedAt);
    }

    /** Omits the graph bytes from logs and assertion messages. */
    @Override
    public String toString() {
        return "StoredReactionGraph[%s %s etag=%s %d bytes]".formatted(environment, sourceFormat, etag,
                data == null ? 0 : data.length);
    }
}
