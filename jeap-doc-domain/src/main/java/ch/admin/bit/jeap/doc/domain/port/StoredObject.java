package ch.admin.bit.jeap.doc.domain.port;

import java.io.InputStream;

/**
 * One file of a published site, as it is served.
 *
 * @param content       the bytes, to be read and closed by the caller
 * @param sizeInBytes   how many there are
 * @param entityTag     the object storage's entity tag, for a conditional request, or null
 * @param contentType   what the file is
 */
public record StoredObject(InputStream content, long sizeInBytes, String entityTag, String contentType) {
}
