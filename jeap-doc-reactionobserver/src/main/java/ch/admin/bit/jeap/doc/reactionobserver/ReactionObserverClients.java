package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import ch.admin.bit.jeap.doc.upstream.UpstreamRetries;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One reaction observer client per configured environment, built once while the service starts.
 * <p>
 * The token is a client-credentials token of this service carrying the role
 * {@code <system-name>_@reactions_#read}, issued by the authorization server of that stage. The timeouts and
 * the retries come from {@code jeap.doc.reactions.client}, whose defaults are the values every other upstream
 * client of this service uses.
 */
public class ReactionObserverClients {

    private final Map<String, RestClient> byEnvironment = new LinkedHashMap<>();
    private final Map<String, String> urlByEnvironment = new LinkedHashMap<>();
    private final RetryTemplate retries;
    private final JsonMapper json;

    /**
     * @param clientBuilders may be null when no environment is configured, which is what an instance that
     *                       reads no reactions looks like
     */
    public ReactionObserverClients(ReactionObserverProperties properties,
                                   JeapOAuth2RestClientBuilderFactory clientBuilders, JsonMapper json) {
        UpstreamClientSettings clientSettings = properties.getClient();
        this.retries = UpstreamRetries.of(clientSettings);
        this.json = json;
        if (!properties.isEnabled()) {
            // Off means no client at all, which is what makes the import steps inert without the domain ever
            // reading this flag: an environment nothing is built for is an environment with no reactions.
            return;
        }
        properties.getEnvironments().forEach((environment, observer) -> {
            urlByEnvironment.put(environment, observer.getUrl());
            byEnvironment.put(environment, UpstreamHttp.client(observer.getUrl(),
                    observer.getClientRegistration(), clientSettings, clientBuilders, json));
        });
    }

    /** The client of one environment, or empty when no reaction observer is configured for it. */
    Optional<RestClient> of(String environment) {
        return Optional.ofNullable(byEnvironment.get(environment));
    }

    /** Where the reactions of an environment are read from, which a generated page names. */
    Optional<String> urlOf(String environment) {
        return Optional.ofNullable(urlByEnvironment.get(environment));
    }

    /** The environments a reaction observer is configured for. */
    Set<String> environments() {
        return Set.copyOf(byEnvironment.keySet());
    }

    /**
     * Runs one request, retrying it if the observer is failing or shedding load. Every request made through
     * here is a {@code GET} with no body, which is what makes retrying it safe.
     */
    <T> T retrying(Supplier<T> request) {
        // invoke, not execute: on exhaustion it rethrows the last original exception rather than wrapping it,
        // so a caller still sees the UpstreamException it decides on.
        return retries.invoke(request);
    }

    /** A conditional {@code GET} of one graph, bounded - see {@link UpstreamHttp#getBounded}. */
    UpstreamHttp.Answer getBounded(RestClient client, URI uri, String knownEtag, long cap) {
        return UpstreamHttp.getBounded(json, client, uri, knownEtag, cap);
    }

    /** A path out of an index, resolved against the origin of this environment's observer. */
    Optional<URI> resolve(String environment, String path) {
        return urlOf(environment).flatMap(observer -> UpstreamHttp.resolve(observer, path,
                "The reaction observer of the environment %s".formatted(environment)));
    }

    /**
     * Reads a graph answer as a tree, because the graph inside it is not this service's shape to know: what is
     * stored is the bytes of the {@code graph} field, and the only other thing read is the fingerprint beside
     * it.
     */
    JsonNode readTree(byte[] body) {
        return json.readTree(body);
    }

    /** The bytes of one node of an answer, as they will be stored. */
    byte[] writeBytes(JsonNode node) {
        return json.writeValueAsBytes(node);
    }
}
