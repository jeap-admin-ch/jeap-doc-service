package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import ch.admin.bit.jeap.doc.upstream.UpstreamException;
import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import ch.admin.bit.jeap.doc.upstream.UpstreamRetries;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One client per configured environment, built once while the service starts.
 * <p>
 * The token is a client-credentials token of this service, from
 * {@link JeapOAuth2RestClientBuilderFactory}. It carries the role
 * {@code <system-name>_@architecture-model_#read} and is issued by the architecture repository's authorization
 * server, which is not this service's own.
 */
@Slf4j
public class ArchRepoClients {

    private final Map<String, DocsApiClient> byEnvironment = new LinkedHashMap<>();
    private final Map<String, RestClient> restClientByEnvironment = new LinkedHashMap<>();
    private final Map<String, String> urlByEnvironment = new LinkedHashMap<>();
    private final RetryTemplate retries;
    private final JsonMapper json;

    /**
     * @param clientBuilders may be null when no environment is configured. An instance that reads no
     *                       architecture model needs no OAuth2 client
     */
    public ArchRepoClients(ArchRepoProperties properties, JeapOAuth2RestClientBuilderFactory clientBuilders,
                           JsonMapper json) {
        this.retries = UpstreamRetries.of(properties.getClient());
        this.json = json;
        properties.getEnvironments().forEach((environment, upstream) -> {
            RestClient client = restClientFor(upstream, properties.getClient(), clientBuilders, json);
            urlByEnvironment.put(environment, upstream.getUrl());
            restClientByEnvironment.put(environment, client);
            byEnvironment.put(environment, HttpServiceProxyFactory
                    .builderFor(RestClientAdapter.create(client))
                    .build()
                    .createClient(DocsApiClient.class));
        });
    }

    /** The client of one environment, or empty when none is configured for it. */
    Optional<DocsApiClient> of(String environment) {
        return Optional.ofNullable(byEnvironment.get(environment));
    }

    /** The plain client, for the artifact contents whose URL comes from an index rather than from a template. */
    Optional<RestClient> restClientOf(String environment) {
        return Optional.ofNullable(restClientByEnvironment.get(environment));
    }

    /** Where the model of an environment is read from, which every generated page names. */
    public Optional<String> urlOf(String environment) {
        return Optional.ofNullable(urlByEnvironment.get(environment));
    }

    /** The environments an architecture repository is configured for. */
    public Set<String> environments() {
        return Set.copyOf(byEnvironment.keySet());
    }

    /**
     * Runs one request, retrying it if the architecture repository is failing or shedding load.
     * <p>
     * Every request made through here is a {@code GET} with no body, which is what makes retrying it safe.
     */
    <T> T retrying(Supplier<T> request) {
        // invoke, not execute: on exhaustion it rethrows the last original exception rather than wrapping it,
        // so a caller still sees the UpstreamException it decides on.
        return retries.invoke(request);
    }

    /**
     * A conditional {@code GET} of one item, bounded - see {@link UpstreamHttp#getBounded}.
     */
    UpstreamHttp.Answer getBounded(RestClient client, URI uri, String knownEtag, long cap) {
        return UpstreamHttp.getBounded(json, client, uri, knownEtag, cap);
    }

    /**
     * Reads a JSON body this adapter fetched bounded.
     * <p>
     * The message converters of the client are not in the way here, because the bytes were read rather than
     * converted - which is what the cap in {@link UpstreamHttp#getBounded} needs.
     */
    <T> T readJson(byte[] body, Class<T> type) {
        return json.readValue(body, type);
    }

    private static RestClient restClientFor(ArchRepoProperties.Environment upstream,
                                            UpstreamClientSettings settings,
                                            JeapOAuth2RestClientBuilderFactory clientBuilders,
                                            JsonMapper json) {
        return UpstreamHttp.client(upstream.getUrl(), upstream.getClientRegistration(), settings,
                clientBuilders, json);
    }

    /**
     * A content URL out of an index, resolved against the origin of this environment's architecture
     * repository - see {@link UpstreamHttp#resolve}.
     */
    public Optional<URI> resolve(String environment, String path) {
        return urlOf(environment).flatMap(upstream -> UpstreamHttp.resolve(upstream, path,
                "The architecture repository of the environment %s".formatted(environment)));
    }
}
