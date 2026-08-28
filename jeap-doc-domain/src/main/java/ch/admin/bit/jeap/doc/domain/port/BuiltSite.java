package ch.admin.bit.jeap.doc.domain.port;

import java.nio.file.Path;

/**
 * What a documentation build produced.
 *
 * @param directory        where the generated site lies, to be published from
 * @param pageCount        how many pages it holds
 * @param sizeInBytes      how large it is
 * @param docusaurusMillis how long the site generator itself took, as opposed to the rest of the run
 */
public record BuiltSite(Path directory, int pageCount, long sizeInBytes, long docusaurusMillis) {
}
