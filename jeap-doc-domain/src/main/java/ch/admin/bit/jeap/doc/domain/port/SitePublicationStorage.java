package ch.admin.bit.jeap.doc.domain.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The object storage the generated sites are published to and served from.
 * <p>
 * A part is written under the identifier of the build that produced it and is referenced only once it is
 * complete, so publishing never touches what is being read: the switch is one row in the database, and that is
 * the only part of publishing that has to be - and can be - atomic.
 * <p>
 * The exception is the shared files - see {@link ch.admin.bit.jeap.doc.domain.SharedAssets}. They go to one
 * prefix per site, written by every part build, and what lands there is the same bytes under the same name.
 */
public interface SitePublicationStorage {

    /**
     * Writes a generated part, its own files under its own prefix and its shared files under the site's, and
     * reports how many of <b>this part's own</b> files it wrote and how many bytes they are.
     * <p>
     * The shared files are left out of both numbers on purpose: every part writes the same ones, so counting
     * them per part would make a fifty-two-part site fifty-one copies of that bundle too large - and the
     * numbers belong to the row of one part.
     */
    PublishedSite publish(PartPublication where, Path directory);

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
