package ch.admin.bit.jeap.doc.domain.port;

/**
 * Something read from an upstream together with the entity tag that names those bytes.
 *
 * @param etag the tag verbatim, quotes and all. It is compared against what an index lists and sent back as
 *             {@code If-None-Match}, and both are the header's own syntax
 */
public record Fetched<T>(T value, String etag) {
}
