package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;

import java.io.InputStream;
import java.util.List;

/**
 * Receives an uploaded bundle in two steps: read it, then store it.
 * <p>
 * Two steps because what is between them decides whether it is stored at all. The bundle has to be on a file
 * before anything can be said about it - a request body cannot be read twice - and once it is, its list of
 * entries answers every question the upload API asks about the structure of a set. So a set that would not be
 * published is refused before an object exists.
 */
public interface UploadedBundles {

    /**
     * Reads the bundle to its end onto a file and reports what it holds, without decompressing an entry.
     *
     * @param bundle      the body of the request
     * @param sizeInBytes the size the client announced
     * @param limits      what the set may hold, so that reading stops rather than answering
     * @throws InvalidUploadException if the body is not as long as announced, is not an archive, or holds
     *                                more paths or more unpacked bytes than the limits allow
     */
    ReceivedBundle receive(InputStream bundle, long sizeInBytes, BundleLimits limits);

    /**
     * Stores a received bundle under the key of one attempt of one upload.
     *
     * @param uploadId the identifier the doc service gave the upload
     * @param attempt  which attempt of that upload is writing
     */
    StoredBundle store(long uploadId, int attempt, ReceivedBundle received);

    /**
     * An uploaded bundle on a file, read but not yet stored.
     * <p>
     * Nested rather than beside this port: it is what the port hands back, not a port of its own, and every
     * public interface in this package is bound to exactly one adapter.
     * <p>
     * Closing it removes the file, so it is used in a try-with-resources - whichever way the upload ends,
     * nothing is left behind.
     */
    interface ReceivedBundle extends AutoCloseable {

        /** Every path the archive holds, in the order the archive lists them. */
        List<String> paths();

        /**
         * What the archive says its entries unpack to, added up. Declared by the uploader, so it says what a
         * set claims rather than what it is.
         */
        long declaredUnpackedSize();

        /** The SHA-256 of the bundle, lower case hexadecimal. */
        String sha256();

        /** The size of the bundle as it arrived. */
        long sizeInBytes();

        /**
         * The first bytes of one file of the archive, for reading what a page says about itself.
         * <p>
         * <b>Bounded, and read only once a set has been accepted.</b> Nothing is decompressed while the
         * upload is being decided - what it is checked against is the list of paths - so a bundle that is
         * refused is refused without an entry ever being unpacked. This is for the set that got through, and
         * it reads a page's front matter rather than the page.
         *
         * @param path     the path inside the archive
         * @param maxBytes the most to read, however long the file is
         * @return what was read, empty where the archive does not hold that path
         */
        byte[] head(String path, int maxBytes);

        @Override
        void close();
    }
}
