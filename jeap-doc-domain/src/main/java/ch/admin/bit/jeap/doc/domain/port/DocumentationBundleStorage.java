package ch.admin.bit.jeap.doc.domain.port;

import java.io.InputStream;

/**
 * The object storage the uploaded bundles are kept in, separately from the documentation the generator writes.
 */
public interface DocumentationBundleStorage {

    /**
     * Stores the bundle of one upload and reports where it put it and what it contained.
     * <p>
     * Where that is, is the storage's business: the upload gives its identifier, and the key it gets back is
     * recorded with the upload so a later reader does not have to know how a key is built. The digest is taken
     * of the bytes that were actually stored - it is what makes it possible to say later whether the bundle in
     * the storage is the one a pipeline sent, and whether two attempts of one upload sent the same.
     *
     * Every attempt of one upload gets a place of its own: an attempt that was taken over as abandoned is not
     * dead, it is slow, and it will finish and write. Were both writing the same place, the bundle of the attempt
     * that won could be replaced by the one of the attempt it replaced - and the upload would then describe, down
     * to its digest, bytes other than the ones lying there.
     *
     * @param uploadId    the identifier of the upload the bundle belongs to
     * @param attempt     which attempt of that upload is writing
     * @param bundle      the bundle, read to its end
     * @param sizeInBytes the size the upload announced
     * @return where the bundle lies and what it contains
     */
    StoredBundle store(long uploadId, int attempt, InputStream bundle, long sizeInBytes);
}
