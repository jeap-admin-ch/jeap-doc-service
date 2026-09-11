package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;

import java.util.List;
import java.util.Optional;

/**
 * Where the documentation sets the doc service currently publishes are kept.
 * <p>
 * There is no method for changing part of a set. An upload carries the whole set and replaces the whole set,
 * so a page a team deleted disappears and nothing has to detect that it did.
 */
public interface CustomDocumentationRepository {

    /**
     * Replaces the set with this one's key, or records it if there is none, and answers what is stored
     * together with the object the set stopped naming.
     * <p>
     * The set and its pages in one transaction: a build reads both, and a set that is half replaced would be
     * published as one.
     */
    Replaced replace(CustomSet set);

    /** The set stored under this key, if there is one. */
    Optional<CustomSet> find(CustomSetKey key);

    /**
     * Everything documented for one system of one site: its own sets, and those of its components and
     * libraries.
     * <p>
     * What a build reads, once per system. {@link CustomDocumentation#nothing()} where nothing is documented.
     */
    CustomDocumentation of(String site, String system);

    /**
     * Every subject a site holds documentation of, sorted by system and name.
     * <p>
     * What the site partition adds to the systems of the architecture model, so that a system nobody has
     * deployed yet still has a part. It is asked while a request is served, so it has to stay a projection.
     */
    List<CustomSubject> subjectsOf(String site);

    /** Removes one set. Answers false if there was none. */
    boolean remove(CustomSetKey key);

    /** Removes every set of one subject, and answers how many there were. */
    int removeSubject(CustomSubject subject);

    /**
     * Removes every set of one system of one site - its own, and those of all its components and libraries -
     * and answers the objects they lay in.
     * <p>
     * <b>The keys and not the count</b>, because the caller has to delete the objects and the rows are the
     * only thing that names them. One call rather than one per subject: what it removes is everything
     * documented for that system, so asking which subjects there are and then removing them one at a time
     * would let an upload land between the two.
     */
    List<String> removeSystem(String site, String system);

    /** The keys of every set there is, for the sweep that removes objects nothing references. */
    List<String> allObjectKeys();

    /**
     * A set as it is now stored, and the object its predecessor lay in.
     * <p>
     * <b>Reported rather than deleted here.</b> Once the row stops naming an object, nothing can find that
     * object again - so the one moment it can still be deleted is the one that displaced it, and this is what
     * carries it to the caller that is able to.
     *
     * @param set               the set as it is stored
     * @param previousObjectKey the object the replaced set lay in, or empty where the set is new
     */
    record Replaced(CustomSet set, Optional<String> previousObjectKey) {

        /** A set that replaced nothing. */
        public static Replaced first(CustomSet set) {
            return new Replaced(set, Optional.empty());
        }
    }
}
