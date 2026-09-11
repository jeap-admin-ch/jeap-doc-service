package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The object storage the current documentation sets lie in, separately from the uploads they arrived as.
 * <p>
 * An upload's bundle is a staging copy that expires; a set is the only copy there is, and nothing removes it
 * by age.
 */
public interface CustomDocumentationStorage {

    /**
     * Copies a stored upload bundle to the key of its set, without reading the bytes back through this
     * service, and answers that key.
     * <p>
     * A server-side copy rather than a second upload: the object then holds exactly the bytes the pipeline
     * sent, with the digest it was told, and nothing has to be re-read to make it current.
     *
     * @param stored   where the upload put its bundle
     * @param key      the set the bundle becomes
     * @param revision the upload this set comes from, which makes the key of a replaced set a new one
     * @param attempt  the attempt of that upload which is writing, so that an attempt that was given up on
     *                 cannot copy its own bytes over what the attempt that took over made current
     */
    String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt);

    /**
     * Reads a set's bundle back, so a build can write its pages into the tree, or answers empty when the
     * object is not there.
     * <p>
     * The whole set is one object, so this is one request per set and per build.
     * <p>
     * <b>A set whose object is gone costs that set, not the build.</b> A removal deletes the object straight
     * after the row, and so does an upload that replaced one - while a build that has already read the rows
     * of that system may not have opened the bundle yet. The same reasoning as
     * {@link OpenedBundle#read}: the removal or the upload has already asked for the build that publishes
     * the correct state.
     */
    Optional<OpenedBundle> open(CustomSet set);

    /**
     * Removes one set's object.
     * <p>
     * Removing an object that is not there is not a failure and is not reported as one: object storage deletes
     * are idempotent, so <i>it is gone</i> and <i>it was already gone</i> are one outcome and no caller could
     * act differently on the two.
     */
    void delete(String objectKey);

    /**
     * Every object under the current documentation that has not been written since the given instant, for the
     * sweep of what no set references.
     * <p>
     * <b>The age is a parameter because the sweep needs it, not because anything expires by age.</b> A set's
     * bundle is copied before the rows that name it are committed, so an object written moments ago may have
     * no row yet - and deleting it would take the documentation of an upload that was about to succeed. What
     * is old enough to judge is the caller's rule; this only has to be able to answer it.
     *
     * @param writtenBefore only objects last written before this instant
     */
    List<String> listWrittenBefore(Instant writtenBefore);

    /**
     * A set's bundle, open while a build writes its pages.
     * <p>
     * Nested rather than beside this port, like {@code UploadedBundles.ReceivedBundle}: it is what the port
     * hands back. Closing it releases whatever it was read from.
     */
    interface OpenedBundle extends AutoCloseable {

        /**
         * One file of the set, or empty when the archive does not hold it - which is a set whose rows and
         * whose object disagree, and is a page left out rather than a failed build.
         *
         * @param path the path inside the set, as a page names it
         */
        Optional<InputStream> read(String path);

        @Override
        void close();
    }
}
