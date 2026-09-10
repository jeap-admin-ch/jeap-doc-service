package ch.admin.bit.jeap.doc.domain.port;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;

import java.util.List;
import java.util.Optional;

/**
 * The reaction graphs, as the reaction observer of one environment serves them.
 * <p>
 * Deliberately the shape of {@link ArchitectureArtifactUpstream}: an index of entity tags, then a conditional
 * fetch of what moved. The observer serves both, and a graph that did not move costs a {@code 304}.
 * <p>
 * <b>An observer that serves no index cannot be imported at all.</b> The indexes and the resource server this
 * client authenticates against arrived in the same release of the observer, so one that answers {@code 404} on
 * an index is one this service could not have got a token from either - which makes it a configuration error
 * rather than a slower path, and it is reported as {@link ArchitectureModelUnavailableException} like any other
 * upstream that cannot be read.
 */
public interface ReactionGraphUpstream {

    /**
     * Whether a reaction observer is configured for this environment. An environment without one has no
     * reactions, which is a legitimate landscape rather than something missing.
     */
    boolean isConfiguredFor(String environment);

    /**
     * What the observer holds for one environment and kind, without any content.
     *
     * @param knownIndexEtag the tag of the index as it was last seen, or null to ask unconditionally
     * @return empty when the observer answered "not modified"
     * @throws ArchitectureModelUnavailableException when the observer could not be read - including when it
     *                                               serves no index
     */
    Optional<Fetched<List<ReactionGraphRef>>> index(String environment, ArchitectureImportKind kind,
                                                    String knownIndexEtag);

    /**
     * The graph behind one index entry.
     * <p>
     * One request per message <b>type</b> answers every variant of it at once, so the graph of the ref that
     * was asked for is picked out of that answer here. The step therefore fetches per ref and never has to
     * know that.
     *
     * @param knownEtag the tag of the graph already stored, or null when there is none
     * @throws ArchitectureModelUnavailableException when the observer could not be read
     */
    GraphFetch content(String environment, ReactionGraphRef ref, String knownEtag);
}
