package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import ch.admin.bit.jeap.doc.upstream.UpstreamException;
import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Fetches and validates the complete environment graph with conditional, bounded requests. */
@Slf4j
@RequiredArgsConstructor
class ReactionObserverUpstream implements ReactionGraphUpstream {
    private static final String GRAPH = "graph";
    // Switch path and format together when adopting V2. Stored snapshots keep their own format.
    private static final String FULL_GRAPH_PATH = "/api/graphs";
    private static final String LEGACY_GRAPH_FORMAT = "observer-v1";
    private static final String FULL_GRAPH_FORMAT = LEGACY_GRAPH_FORMAT;
    private final ReactionObserverClients clients;
    private final ReactionObserverProperties properties;

    @Override
    public boolean isConfiguredFor(String environment) {
        return clients.environments().contains(environment);
    }

    @Override
    public GraphFetch fullGraph(String environment, String knownEtag, String knownFormat) {
        var client = clients.of(environment).orElseThrow(() -> new ArchitectureModelUnavailableException(
                "No reaction observer is configured for the environment " + environment + "."));
        String base = withoutTrailingSlashes(urlOf(environment));
        var uri = clients.resolve(environment, base + FULL_GRAPH_PATH);
        if (uri.isEmpty()) return GraphFetch.skipped("its URL cannot be fetched");
        String previousFormat = knownFormat == null || knownFormat.isBlank() ? LEGACY_GRAPH_FORMAT : knownFormat;
        String tag = FULL_GRAPH_FORMAT.equals(previousFormat) ? knownEtag : null;
        UpstreamHttp.Answer answer;
        try {
            answer = clients.retrying(() -> clients.getBounded(client, uri.orElseThrow(), tag,
                    properties.getMaxGraphSize().toBytes()));
        } catch (UpstreamException e) {
            throw unavailable(environment, e);
        } catch (RuntimeException e) {
            throw unreachable(environment, e);
        }
        if (answer.isNotModified()) {
            return tag == null || tag.isBlank()
                    ? GraphFetch.skipped("it answered \"not modified\" to an unconditional request")
                    : GraphFetch.unchanged();
        }
        if (answer.isRedirect()) return GraphFetch.skipped("it answered with a redirect, which is not followed");
        if (answer.tooLarge()) return GraphFetch.skipped("it is larger than the %s this service stores"
                .formatted(properties.getMaxGraphSize()));
        if (answer.etag() == null || answer.etag().isBlank()) return GraphFetch.skipped("it arrived without an entity tag");
        return graphOf(environment, answer.etag(), answer.body());
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    private GraphFetch graphOf(String environment, String etag, byte[] body) {
        JsonNode envelope;
        try {
            envelope = clients.readTree(body);
        } catch (JacksonException _) {
            return GraphFetch.skipped("it is not readable as JSON");
        }
        if (envelope == null || envelope.path(GRAPH).isMissingNode() || envelope.path(GRAPH).isNull()) {
            return GraphFetch.skipped("it carried no graph");
        }
        JsonNode graph = envelope.path(GRAPH);
        try {
            var network = FullReactionGraphReader.read(graph, FULL_GRAPH_FORMAT.equals("observer-v2"));
            var stored = java.util.Map.of("sourceFormat", FULL_GRAPH_FORMAT, GRAPH, graph);
            JsonNode fingerprint = envelope.path("fingerprint");
            return GraphFetch.stored(new ReactionGraph(etag,
                    fingerprint.isString() ? fingerprint.stringValue() : null, clients.writeBytes(stored),
                    network.interfaces().size() + network.reactions().size(), FULL_GRAPH_FORMAT));
        } catch (IllegalArgumentException e) {
            log.warn("The complete reaction graph of {} is invalid: {}. The stored graph is kept.", environment, e.getMessage());
            return GraphFetch.skipped(e.getMessage());
        }
    }

    private ArchitectureModelUnavailableException unavailable(String environment, UpstreamException e) {
        if (e.isUnauthorized()) {
            return new ArchitectureModelUnavailableException((
                    "The doc service is not allowed to read the reactions of the environment %s at %s (%d). "
                    + "Check the client registration configured in "
                    + "jeap.doc.reactions.environments.%s.client-registration and that its client has the role "
                    + "<system-name>_@reactions_#read on the reaction observer.")
                    .formatted(environment, urlOf(environment), e.getStatus(), environment), e);
        }
        return new ArchitectureModelUnavailableException(
                ("The reaction observer of the environment %s at %s answered %d.")
                        .formatted(environment, urlOf(environment), e.getStatus()), e);
    }

    private ArchitectureModelUnavailableException unreachable(String environment, RuntimeException e) {
        return new ArchitectureModelUnavailableException(
                "The reaction observer of the environment %s at %s could not be reached: %s"
                        .formatted(environment, urlOf(environment), e.getMessage()), e);
    }

    private String urlOf(String environment) {
        return clients.urlOf(environment).orElse("");
    }

}
