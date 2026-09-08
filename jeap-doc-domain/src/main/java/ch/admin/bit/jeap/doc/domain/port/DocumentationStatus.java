package ch.admin.bit.jeap.doc.domain.port;

import java.time.Instant;

/**
 * What one run of the documentation generator cost, written beside the site it produced.
 * <p>
 * <b>This exists because a page cannot describe the build that writes it.</b> How long a run took is known when
 * the generator has finished; the page that would print it was written at the start of the same run. So the
 * numbers are written as JSON at the seam between the generator and the upload - into the output directory,
 * before anything is published - and the page fetches them.
 * <p>
 * <b>Only what is true of the part carrying the page.</b> A site is generated one part at a time, and every part
 * writes this file into its own output - but the page that fetches it belongs to one part, so only that part's
 * copy is ever served. A page count and a size have no place here: they would be one part's while reading as the
 * whole site's. What each part produced is answered by the administration API, which is asked per part.
 * <p>
 * Everything here is publishable. It is the same rule {@code DocumentationFacts} follows and for the same
 * reason: the file is served to anyone who can read the site.
 *
 * @param buildId          the run these numbers belong to, which the page names as its own
 * @param generatedAt      when the run started
 * @param generatedInMillis how long the run took up to the moment these numbers were known - everything but
 *                         the upload of the site, which by definition cannot be included in a file that is part
 *                         of what is being uploaded
 * @param generatorMillis  how much of that was the site generator itself
 */
public record DocumentationStatus(
        long buildId,
        Instant generatedAt,
        long generatedInMillis,
        long generatorMillis) {

    /** The numbers of a finished run, from what it produced. */
    public static DocumentationStatus of(long buildId, Instant generatedAt, long generatedInMillis,
                                         BuiltSite generated) {
        return new DocumentationStatus(buildId, generatedAt, generatedInMillis, generated.docusaurusMillis());
    }
}
