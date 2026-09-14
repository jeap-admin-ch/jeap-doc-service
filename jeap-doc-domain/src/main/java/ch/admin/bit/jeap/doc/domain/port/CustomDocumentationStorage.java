package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.MicrositePageText;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;

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
     * Unpacks an HTML set into one object per file and answers the prefix they lie under.
     * <p>
     * <b>Files rather than one archive, because a microsite is served file by file.</b> A reader opens its
     * entry point and the browser fetches what that page names; serving those out of a ZIP would mean
     * unpacking it per request. The prefix ends with {@code /} and is what the set's row names, so
     * {@link #delete(String)} removes the whole of it.
     * <p>
     * The files nobody wrote are left out, as they are everywhere else, and what the set really unpacks to
     * is counted here - the archive's own declaration was checked when it was received, and that is the
     * uploader's to state.
     *
     * @param received the bundle as it was received, still on its file
     * @param key      the set the files become
     * @param revision the upload this set comes from
     * @param attempt  the attempt of that upload which is writing
     * @param limits   what the set may unpack to
     * @throws InvalidUploadException if the files unpack to more than the limits allow
     */
    String promoteFiles(UploadedBundles.ReceivedBundle received, CustomSetKey key, long revision,
                        int attempt, BundleLimits limits);

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
     * Stores the text of a microsite's pages under the set's own prefix, so that the search index can read it
     * without ever fetching the HTML again.
     * <p>
     * <b>Under the prefix rather than beside it.</b> The row names the prefix, a removal deletes everything
     * below it and the sweep counts a referenced prefix as covering everything beneath - so a file inside it
     * needs none of those three to learn about it. What that costs is one reserved name,
     * {@code MicrositeRules.SEARCH_TEXT}, which the upload validation refuses.
     * <p>
     * The encoding is this adapter's own business: what crosses the port are the records, and
     * {@link #readSearchText(String)} is what reads them back.
     *
     * @param prefix the prefix a set's row names, ending with a slash
     * @param pages  what the set contributes to the index, in the order it contributes it
     */
    void storeSearchText(String prefix, List<MicrositePageText> pages);

    /**
     * The text of a microsite's pages as it was stored, or empty where there is none.
     * <p>
     * Empty is a set uploaded before the text was extracted, or one uploaded while this instance was not
     * indexing at all. It costs that microsite's content in the index and nothing else, so it is answered
     * rather than thrown.
     */
    List<MicrositePageText> readSearchText(String prefix);

    /**
     * Reads one file of an unpacked microsite, or answers empty where the prefix does not hold it.
     * <p>
     * One request per file, which is what serving a microsite to a browser is: the reader opens its entry
     * point and the page fetches what it names.
     *
     * @param prefix the prefix a set's row names, ending with a slash
     * @param path   the path within the set
     */
    Optional<StoredObject> openFile(String prefix, String path);

    /**
     * Removes one set's object, or everything under its prefix where the key ends with {@code /} - which is
     * what an HTML set's row names.
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
