package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Writes what one content source puts into a part: its pages, into the content directory of a build. The
 * generator writes the site-level files around them - the branding, the page about the documentation, what the
 * template reads - and picks the writer by the site's {@code content}.
 * <p>
 * One writer per content source, and nothing outside the writer names it. An instance whose site names a
 * source it has no writer for does not start.
 */
public interface ContentWriter {

    /** The content source this writer writes, by its id. */
    String contentSource();

    /**
     * Writes one part of a site.
     *
     * @param contentDirectory the content directory of the build: one directory per environment tree below it,
     *                         or {@link SiteSources#SINGLE_TREE} for a site without environments
     * @return per environment that reads an architecture model, what it contributed; empty for a source that
     *         reads none
     */
    Map<String, EnvironmentModel> write(Site site, SitePart part, Path contentDirectory, Instant generatedAt)
            throws IOException;
}
