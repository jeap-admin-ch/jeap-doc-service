package ch.admin.bit.jeap.doc.domain.port;

/**
 * What the object storage made of a bundle: where it lies, and what it contains.
 *
 * @param objectKey the key the bundle was stored under
 * @param sha256    the SHA-256 of the stored bytes, lower case hexadecimal
 */
public record StoredBundle(String objectKey, String sha256) {
}
