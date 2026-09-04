package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureSnapshot;

import java.time.Instant;
import java.util.Optional;

/**
 * Where the architecture model of an environment is read from.
 * <p>
 * The whole landscape in one call, because a run needs every system to compute any system's context.
 * <p>
 * It is what an import stored, not what the architecture repository is serving now. A generation run makes no
 * call to the architecture repository at all, so a repository that is being deployed cannot fail a build.
 */
public interface ArchitectureModelSource {

    /**
     * Whether an architecture repository is configured for the environment. One without is legitimate: its
     * tree carries the root page and whatever was uploaded into it.
     */
    boolean isConfiguredFor(String environment);

    /** Where the model is read from, which every generated page names. Empty when nothing is configured. */
    Optional<String> sourceUrlOf(String environment);

    /**
     * When the architecture repository of this environment was last read successfully, or empty if it never
     * was.
     * <p>
     * <b>Whether the import is still working</b>, and nothing else. It moves for a run that found the
     * landscape unchanged and wrote nothing, which is what makes it the right answer for the staleness
     * warning and the wrong one for a page's provenance. What a page names is
     * {@link ArchitectureSnapshot#importedAt()}.
     */
    Optional<Instant> lastSuccessfulImportAt(String environment);

    /**
     * The stored architecture model of one environment, and when its content was imported, as one moment.
     * <p>
     * <b>Never throws.</b> An environment that has never been imported reads as
     * {@link ArchitectureSnapshot#empty()}, and whether a site may be built from that is the build's decision
     * and not this one's.
     */
    ArchitectureSnapshot read(String environment);
}
