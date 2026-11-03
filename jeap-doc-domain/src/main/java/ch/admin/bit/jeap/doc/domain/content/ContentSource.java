package ch.admin.bit.jeap.doc.domain.content;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePartition;

/**
 * Where the content of a documentation site comes from - the systems of the architecture model and what their
 * teams uploaded, or a tree uploaded for the site itself. A site names its source in {@code content}.
 * <p>
 * A source decides which parts its sites have, which triggers ask for them, and which configuration they need.
 * What it writes into a part is its writer's, in the site generator, which picks it by {@link #id()}.
 * <p>
 * It is a plugin point, not a driven port, like {@code StructureTemplate}: one bean per source, and nothing
 * outside the source's own module names it. A further source is a further bean, not a change to the site model.
 */
public interface ContentSource {

    /** The value of a site's {@code content} that names this source. */
    String id();

    /** How a site of this source is cut into parts, and what each of them owns. */
    SitePartition partition();

    /**
     * Whether an import of the architecture model of one of a site's environments asks for the site. A site
     * nothing imports for is asked for on the reconcile schedule instead.
     */
    default boolean publishedByTheArchitectureImport() {
        return false;
    }

    /**
     * Whether a tree is uploaded for a site of this source itself ({@code type=site-docs}), naming no system. A
     * source that takes one takes nothing else: its site documents no system.
     */
    default boolean takesSiteUploads() {
        return false;
    }

    /**
     * Refuses a site of this source whose configuration cannot work, with an {@link IllegalStateException}
     * naming the site. It is called for every such site while the service starts.
     */
    default void check(Site site) {
        // Nothing to check by default.
    }
}
