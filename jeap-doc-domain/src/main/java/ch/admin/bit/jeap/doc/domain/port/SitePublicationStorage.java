package ch.admin.bit.jeap.doc.domain.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The object storage the generated sites are published to and served from.
 * <p>
 * A site is written under the identifier of the build that produced it and is referenced only once it is
 * complete, so publishing never touches the site being read: the switch is one row in the database, and that is
 * the only part of publishing that has to be - and can be - atomic.
 */
public interface SitePublicationStorage {

    /**
     * Writes a generated site under the given prefix, and reports how much was written.
     */
    PublishedSite publish(String prefix, Path directory);

    /**
     * Reads one file of a published site, if it is there.
     *
     * @param prefix the prefix of the published site
     * @param path   the path of the file within it, without a leading slash
     */
    Optional<StoredObject> open(String prefix, String path);

    /**
     * Whether one file of a published site is there, <b>without opening it</b>.
     * <p>
     * Not {@code open(...).isPresent()}: what {@link #open} hands back holds an open connection to the object
     * storage, which the caller has to read to its end or close. A caller that only wants to know whether
     * something exists would leak one every time it did, and the pool it is leaking from is what serves the
     * documentation.
     */
    boolean exists(String prefix, String path);

    /**
     * Removes a published site that nothing serves any more.
     */
    void delete(String prefix);
}
