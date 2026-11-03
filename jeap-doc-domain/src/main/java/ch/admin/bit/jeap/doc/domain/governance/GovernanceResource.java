package ch.admin.bit.jeap.doc.domain.governance;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One resource of the Governance Service's {@code /docs-api}, stored as it arrived: a systems or rules index,
 * or one system or rule the index named.
 * <p>
 * The content is the JSON the upstream served, stored verbatim - the entity tag names exactly those bytes, so a
 * round trip through a decoder would name different ones. The site generator reads it back and parses it when
 * it writes a page; nothing here parses it.
 *
 * @param key         the system name or rule ID, or null for {@link GovernanceResourceKind#SYSTEMS_INDEX} and
 *                    {@link GovernanceResourceKind#RULES_INDEX}
 * @param etag        the entity tag verbatim, quotes and all
 * @param content     the bytes, as served
 * @param sizeInBytes how many bytes, written rather than derived
 * @param importedAt  when this service last stored these bytes
 */
public record GovernanceResource(
        GovernanceResourceKind kind,
        String key,
        String etag,
        byte[] content,
        long sizeInBytes,
        Instant importedAt) {

    /**
     * Value equality over the content too, which the generated one would not give for an array.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof GovernanceResource resource
               && kind == resource.kind
               && Objects.equals(key, resource.key)
               && Objects.equals(etag, resource.etag)
               && Arrays.equals(content, resource.content)
               && sizeInBytes == resource.sizeInBytes
               && Objects.equals(importedAt, resource.importedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, key, etag, Arrays.hashCode(content), sizeInBytes, importedAt);
    }

    /**
     * Without the content, so that a log line or a test failure does not print a megabyte of JSON.
     */
    @Override
    public String toString() {
        return "GovernanceResource[%s %s etag=%s %d bytes]".formatted(kind, key, etag, sizeInBytes);
    }
}
