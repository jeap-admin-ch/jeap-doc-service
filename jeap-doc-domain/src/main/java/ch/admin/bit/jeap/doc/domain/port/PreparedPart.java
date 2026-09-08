package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.SitePart;

import java.nio.file.Path;

/**
 * The content of one part, written and hashed, waiting to be generated or discarded.
 *
 * @param buildId   the build the workspace is named after
 * @param part      the part whose content was written
 * @param workspace where it was written
 * @param digest    what the content hashed to. A part whose digest is the one already published produces the
 *                  same site, so the generator is not started at all
 */
public record PreparedPart(long buildId, SitePart part, Path workspace, String digest) {
}
