package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

    /** How much of an error body is read while looking for the problem type. */
    private static final int PROBLEM_BODY_LIMIT = 8192;

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
        this.retries = ArchRepoRetries.of(properties.getClient());
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
        // so a caller still sees the ArchRepoException it decides on.
        return retries.invoke(request);
    }

    /**
     * What one bounded {@code GET} answered.
     *
     * @param body     the bytes, or null when the answer carried none - a {@code 304}, a redirect, or a body
     *                 refused for its size
     * @param tooLarge whether the body was refused for exceeding the cap
     */
    record Answer(HttpStatusCode status, HttpHeaders headers, byte[] body, boolean tooLarge) {

        boolean isNotModified() {
            return status.value() == HttpStatus.NOT_MODIFIED.value();
        }

        /** A redirect that was not followed. {@code 304} is a {@code 3xx} too and is not one of these. */
        boolean isRedirect() {
            return status.is3xxRedirection() && !isNotModified();
        }

        /** Where the redirect pointed, for the log line that says an item was skipped. */
        String location() {
            String location = headers.getFirst(HttpHeaders.LOCATION);
            return location == null ? "nowhere it named" : location;
        }

        /** The entity tag verbatim, as it arrived. */
        String etag() {
            return headers.getFirst(HttpHeaders.ETAG);
        }
    }

    /**
     * A conditional {@code GET} of one item, whose body is read up to a cap and no further.
     * <p>
     * The cap is the point. {@code retrieve().toEntity(...)} has the whole answer in memory before anything
     * can judge its size, so an upstream offering a specification of a gigabyte would have been believed and
     * only then refused. Here nothing past the cap is ever read - the advertised length is checked first, and a
     * chunked answer that advertises none is bounded all the same by reading one byte more than the cap allows.
     * <p>
     * <b>A redirect is not followed</b>, so a {@code 3xx} other than {@code 304} arrives here and the caller
     * decides what to do with it. Following one would defeat {@link #resolve}: the origin is checked on the URL
     * the index gave, and a redirect off it would fetch and store whatever it named instead.
     * <p>
     * The stored tag goes out <b>verbatim, quotes and all</b>. It is the header's own syntax on both sides:
     * unquoting it on the way in would mean re-quoting it on the way out, and a mismatch there does not fail -
     * it silently refetches every item on every run, for ever.
     */
    Answer getBounded(RestClient client, URI uri, String knownEtag, long cap) {
        return client.get().uri(uri)
                .headers(headers -> {
                    if (knownEtag != null && !knownEtag.isBlank()) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, knownEtag);
                    }
                })
                .exchange((request, response) -> {
                    // exchange bypasses the status handler the client is built with, so the same failure is
                    // raised here. A caller must not have to tell the two paths apart.
                    if (response.getStatusCode().isError()) {
                        raise(json, request, response);
                    }
                    HttpHeaders headers = response.getHeaders();
                    if (response.getStatusCode().is3xxRedirection()) {
                        return new Answer(response.getStatusCode(), headers, null, false);
                    }
                    if (headers.getContentLength() > cap) {
                        return new Answer(response.getStatusCode(), headers, null, true);
                    }
                    // One byte past the cap: enough to know it was exceeded, and never the whole body. The
                    // cap is clamped first, so that a configured size larger than an array can be does not
                    // overflow into a negative length.
                    byte[] bytes = response.getBody()
                            .readNBytes((int) Math.min(cap, Integer.MAX_VALUE - 1) + 1);
                    return bytes.length > cap
                            ? new Answer(response.getStatusCode(), headers, null, true)
                            : new Answer(response.getStatusCode(), headers, bytes, false);
                });
    }

    /**
     * Reads a JSON body this adapter fetched bounded.
     * <p>
     * The message converters of the client are not in the way here, because the bytes were read rather than
     * converted - which is what the cap in {@link #getBounded} needs.
     */
    <T> T readJson(byte[] body, Class<T> type) {
        return json.readValue(body, type);
    }

    private static RestClient restClientFor(ArchRepoProperties.Environment upstream,
                                            ArchRepoProperties.Client settings,
                                            JeapOAuth2RestClientBuilderFactory clientBuilders,
                                            JsonMapper json) {
        return clientBuilders.createForClientRegistryId(upstream.getClientRegistration())
                .baseUrl(upstream.getUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(settings.getConnectTimeout())
                                .withReadTimeout(settings.getReadTimeout())
                                // The origin of a content URL is checked before it is fetched, and a followed
                                // redirect would make that check hold for the first hop only: a 302 off an
                                // on-origin path would have this service fetch and store whatever the Location
                                // named, and send its token there. Whether the token survives the hop is a
                                // property of whichever client is detected, which is not something to rely on.
                                .withRedirects(HttpRedirects.DONT_FOLLOW)))
                // Every failure carries the status and the problem type, so a caller can tell a system that
                // has gone away from a repository that is down - and so the retry policy can select on the
                // class rather than parse a status a second time.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> raise(json, request,
                        response))
                .build();
    }

    /** Turns a failing answer into the one exception the importer decides on. Always throws. */
    private static void raise(JsonMapper json, HttpRequest request, ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        String message = "%s %s answered %d.".formatted(request.getMethod(), request.getURI(), status);
        String problemType = problemTypeOf(json, response.getHeaders().getFirst("Content-Type"),
                // Bounded and explicit: an upstream answering with a proxy's HTML error page must not be read
                // whole into memory, and the one field wanted is well inside this.
                new String(response.getBody().readNBytes(PROBLEM_BODY_LIMIT), StandardCharsets.UTF_8));
        throw ArchRepoException.isWorthRetrying(status)
                ? new ArchRepoException.Retryable(message, status, problemType, null)
                : new ArchRepoException(message, status, problemType, null);
    }

    /**
     * The {@code type} of an RFC 9457 problem document, or null when the answer is not one.
     * <p>
     * The body is whatever the upstream sent, so it may be truncated, empty or not JSON at all. None of that is
     * worth failing over: the problem type only makes the error message better, and the status alone already
     * says what the caller has to decide on.
     */
    private static String problemTypeOf(JsonMapper json, String contentType, String body) {
        if (contentType == null || !contentType.contains("problem+json") || body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode type = json.readTree(body).path("type");
            return type.isString() ? type.stringValue() : null;
        } catch (JacksonException e) {
            log.debug("The error body of the architecture repository is not readable as JSON.", e);
            return null;
        }
    }

    /**
     * A path from a payload, resolved against the origin of the upstream rather than appended to it.
     * <p>
     * The architecture repository's content URLs already carry its context path, and so does the configured
     * upstream. Appending one to the other would produce the context path twice, and every artifact would
     * answer {@code 404} - which is a case the replication handles quietly, so it would look like an
     * architecture repository that publishes nothing.
     * <p>
     * Empty means the path cannot be fetched - it is not a URI at all, or it does not stay on the upstream's
     * origin. Neither throws: one unusable entry in an index must not stop the artifacts around it from being
     * replicated.
     */
    public Optional<URI> resolve(String environment, String path) {
        return urlOf(environment).flatMap(upstream -> {
            if (path == null || path.isBlank()) {
                // The field is optional in the payload, and a blank path resolves to the upstream's own root -
                // which would fetch the service's home page and store it as a specification.
                log.warn("The architecture repository of the environment {} offered an index entry with no "
                         + "content URL. It is not fetched.", environment);
                return Optional.empty();
            }
            URI base = URI.create(upstream);
            URI resolved;
            try {
                resolved = base.resolve(path);
            } catch (IllegalArgumentException notAUri) {
                // A space, a brace, a pipe - URI.resolve parses its argument, and an unparseable one is the
                // upstream's mistake rather than this service's.
                log.warn("The architecture repository of the environment {} offered the content URL '{}', "
                         + "which is not a URI. It is not fetched.", environment, path);
                return Optional.empty();
            }
            // URI.resolve returns an absolute argument unchanged, so a payload naming another host would send
            // this service's token there. The architecture repository is a trusted peer and this is defence in
            // depth - but the sentence above promises the origin, so make it true.
            if (!sameOrigin(base, resolved)) {
                log.warn("The architecture repository of the environment {} offered the content URL '{}', "
                         + "which does not stay on {}. It is not fetched.", environment, path, upstream);
                return Optional.empty();
            }
            return Optional.of(resolved);
        });
    }

    private static boolean sameOrigin(URI upstream, URI resolved) {
        return upstream.getScheme() != null && upstream.getScheme().equalsIgnoreCase(resolved.getScheme())
               && upstream.getHost() != null && upstream.getHost().equalsIgnoreCase(resolved.getHost())
               && upstream.getPort() == resolved.getPort();
    }
}
