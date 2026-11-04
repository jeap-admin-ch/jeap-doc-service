package ch.admin.bit.jeap.doc.domain.port;

/** Conditional access to one complete environment snapshot. */
public interface ReactionGraphUpstream {
    boolean isConfiguredFor(String environment);

    /** A conditional tag is valid only for its stored source format. */
    GraphFetch fullGraph(String environment, String knownEtag, String knownFormat);
}
