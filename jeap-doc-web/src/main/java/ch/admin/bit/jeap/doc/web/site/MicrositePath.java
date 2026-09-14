package ch.admin.bit.jeap.doc.web.site;

import ch.admin.bit.jeap.doc.domain.custom.Microsite;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;

import java.util.Optional;

/**
 * One file of one uploaded HTML microsite, as a request addresses it.
 * <p>
 * A microsite is not part of a generated site: it is published as it was uploaded and served file by file,
 * under a segment of its own so that nothing a build writes can collide with it. What identifies the set is
 * the same key an upload replaces - the subject, the template, and where the microsite is embedded.
 *
 * @param site the site the microsite belongs to
 * @param key  the set whose files are being served
 * @param file the path within that set
 */
public record MicrositePath(Site site, CustomSetKey key, String file) {

    /** The top-level segment every microsite is served under. */
    public static final String SEGMENT = Microsite.SEGMENT;

    /** What a directory is served with - a microsite is opened at its entry point. */
    public static final String INDEX = "index.html";

    /** The segment that names a component in a microsite's path, and the one that names a library. */
    static final String COMPONENTS = "components";
    static final String LIBRARIES = "libraries";

    /**
     * The parts of a microsite's key in a path below a site's root, or empty where the path does not have the
     * shape of one: the segment, the subject, the template, the location and the topic.
     * <p>
     * <b>Structural only, and the one definition of it.</b> The router resolves a request by it and the
     * headers decide by it which responses get a microsite's policy, so the two cannot disagree about a path -
     * and a path too short to name a microsite is neither routed as one nor served with its headers.
     *
     * @param rest the path below the site's root, without a leading slash
     */
    static Optional<String[]> keyPartsOf(String rest) {
        String prefix = SEGMENT + "/";
        if (!rest.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] segments = rest.substring(prefix.length()).split("/", -1);
        return segments.length >= partsOf(segments) ? Optional.of(segments) : Optional.empty();
    }

    /** How many segments name the set: four for a system's microsite, six where a component or library is named. */
    static int partsOf(String[] segments) {
        return isNamed(segments) ? 6 : 4;
    }

    /** Whether the path names a component or a library, rather than being a system's own microsite. */
    static boolean isNamed(String[] segments) {
        return segments.length > 1 && (COMPONENTS.equals(segments[1]) || LIBRARIES.equals(segments[1]));
    }
}
