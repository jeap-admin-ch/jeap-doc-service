package ch.admin.bit.jeap.doc.domain.architecture.imports;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** One complete environment snapshot. An empty source format identifies legacy unwrapped V1 bytes. */
public record StoredReactionGraph(String environment, String sourceFormat, String etag, String fingerprint,
                                  byte[] data, int drawableNodes, Instant importedAt) {
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

    @Override
    public String toString() {
        return "StoredReactionGraph[%s %s etag=%s %d bytes]".formatted(environment, sourceFormat, etag,
                data == null ? 0 : data.length);
    }
}
