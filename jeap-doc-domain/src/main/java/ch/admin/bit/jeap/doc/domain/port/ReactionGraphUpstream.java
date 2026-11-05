package ch.admin.bit.jeap.doc.domain.port;

/** Conditional access to one complete environment snapshot. */
public interface ReactionGraphUpstream {
    /** An environment without an observer is legitimate and requires no import. */
    boolean isConfiguredFor(String environment);

    /**
     * Fetches and validates the complete snapshot before the importer may replace it.
     *
     * @param knownEtag the stored tag verbatim, or null for an unconditional request
     * @param knownFormat the format that tag belongs to; a format change requires an unconditional request
     * @throws ArchitectureModelUnavailableException when the observer cannot be read
     */
    GraphFetch fullGraph(String environment, String knownEtag, String knownFormat);
}
