package ch.admin.bit.jeap.doc.domain.port;

import java.time.Instant;

/**
 * What one run of the documentation generator cost, written beside the site it produced.
 * <p>
 * <b>This exists because a page cannot describe the build that writes it.</b> The pages, the bytes, the
 * duration and the memory peak are known when the generator has finished; the page that would print them was
 * written at the start of the same run. So the numbers are written as JSON at the seam between the generator
 * and the upload - into the output directory, before anything is published - and the page fetches them.
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
 * @param pageCount        how many pages the run produced
 * @param sizeInBytes      how large the generated site is
 * @param memoryPeakBytes  the highest the container went during the run, or null where it cannot be read
 * @param memoryLimitBytes what the container is killed at, or null with the peak
 * @param memoryPeakExact  whether the peak is this run's own, or only an upper bound on it
 */
public record DocumentationStatus(
        long buildId,
        Instant generatedAt,
        long generatedInMillis,
        long generatorMillis,
        int pageCount,
        long sizeInBytes,
        Long memoryPeakBytes,
        Long memoryLimitBytes,
        Boolean memoryPeakExact) {

    /** The numbers of a finished run, from what it produced and what its container held. */
    public static DocumentationStatus of(long buildId, Instant generatedAt, long generatedInMillis,
                                         BuiltSite generated, ContainerMemory.Peak peak) {
        return new DocumentationStatus(buildId, generatedAt, generatedInMillis, generated.docusaurusMillis(),
                generated.pageCount(), generated.sizeInBytes(),
                peak == null ? null : peak.usedBytes(),
                peak == null || peak.limitBytes() <= 0 ? null : peak.limitBytes(),
                peak == null ? null : peak.exact());
    }
}
