package ch.admin.bit.jeap.doc.domain.port;

/**
 * What a bundle may hold, as the reader of one is told before it reads.
 * <p>
 * Passed in rather than configured in the adapter: what a documentation set may be is the domain's rule, and
 * the adapter's job is to stop reading when it is reached.
 *
 * @param maxPaths        the most files a set may hold
 * @param maxUnpackedSize the most those files may unpack to, added up
 */
public record BundleLimits(int maxPaths, long maxUnpackedSize) {
}
