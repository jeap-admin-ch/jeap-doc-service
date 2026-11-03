package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import ch.admin.bit.jeap.doc.upstream.UpstreamRetries;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one client of the Governance Service, built once while the service starts - there is no environment to
 * key it by, unlike the architecture repository.
 * <p>
 * The token is a client-credentials token of this service, carrying {@code governance-documentation} /
 * {@code read}, issued by the Governance Service's authorization server rather than this service's own.
 */
class GovernanceClients {

    private final RestClient client;
    private final String url;
    private final RetryTemplate retries;
    private final JsonMapper json;

    /**
     * @param clientBuilders null when no Governance Service is configured for this instance
     */
    GovernanceClients(GovernanceProperties properties, JeapOAuth2RestClientBuilderFactory clientBuilders,
                      JsonMapper json) {
        this.url = properties.getUrl();
        this.json = json;
        this.retries = UpstreamRetries.of(properties.getClient());
        this.client = properties.getUrl() == null ? null
                : UpstreamHttp.client(properties.getUrl(), properties.getClientRegistration(),
                        properties.getClient(), clientBuilders, json);
    }

    /** Whether a Governance Service is configured for this instance at all. */
    boolean isConfigured() {
        return client != null;
    }

    Optional<String> url() {
        return Optional.ofNullable(url);
    }

    /**
     * Runs one request, retrying it if the Governance Service is failing or shedding load.
     * <p>
     * Every request made through here is a {@code GET} with no body, which is what makes retrying it safe.
     */
    <T> T retrying(Supplier<T> request) {
        // invoke, not execute: on exhaustion it rethrows the last original exception rather than wrapping it,
        // so a caller still sees the UpstreamException it decides on.
        return retries.invoke(request);
    }

    /**
     * A conditional {@code GET} of one path below the Governance Service's root, bounded - see
     * {@link UpstreamHttp#getBounded}.
     * <p>
     * <b>The path is appended to the configured URL, whatever path that has.</b> A Governance Service is served
     * below a context path of its own - {@code https://host/applicationplatform-governance-service} - and a
     * request URI that is only {@code /docs-api/systems} would keep the host and drop that path, asking another
     * application altogether. The client's base URL does not prevent that for a URI passed in whole.
     */
    UpstreamHttp.Answer getBounded(String path, String knownEtag, long cap) {
        return UpstreamHttp.getBounded(json, client, URI.create(withoutTrailingSlash(url) + path), knownEtag, cap);
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    <T> T readJson(byte[] body, Class<T> type) {
        return json.readValue(body, type);
    }
}
