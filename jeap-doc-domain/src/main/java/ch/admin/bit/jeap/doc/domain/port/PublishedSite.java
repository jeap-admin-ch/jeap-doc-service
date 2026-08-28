package ch.admin.bit.jeap.doc.domain.port;

/**
 * What the object storage made of a generated site.
 *
 * @param prefix      where it lies
 * @param fileCount   how many files it holds
 * @param sizeInBytes how large it is
 */
public record PublishedSite(String prefix, int fileCount, long sizeInBytes) {
}
