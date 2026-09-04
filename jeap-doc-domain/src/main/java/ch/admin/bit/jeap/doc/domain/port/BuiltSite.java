package ch.admin.bit.jeap.doc.domain.port;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a documentation build produced.
 *
 * @param directory         where the generated site lies, to be published from
 * @param pageCount         how many pages it holds
 * @param sizeInBytes       how large it is
 * @param docusaurusMillis  how long the site generator itself took, as opposed to the rest of the run
 * @param documentedSystems how many systems were documented, by environment - only the environments that read
 *                          an architecture model, so that none is reported for one that reads none. Carried
 *                          here rather than reported when the model is read, because a build can still fail
 *                          after that, and a gauge described as the last successful build must not move for
 *                          a build that was not one
 */
public record BuiltSite(Path directory, int pageCount, long sizeInBytes, long docusaurusMillis,
                        Map<String, Integer> documentedSystems) {

    public BuiltSite {
        documentedSystems = documentedSystems == null ? Map.of() : Map.copyOf(documentedSystems);
    }
}
