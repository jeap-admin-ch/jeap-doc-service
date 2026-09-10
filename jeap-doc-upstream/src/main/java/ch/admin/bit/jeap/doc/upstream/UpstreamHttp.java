package ch.admin.bit.jeap.doc.upstream;

import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * How this module calls an upstream: the client it builds, the bounded conditional {@code GET}, and the way a
 * path out of a payload is resolved.
 * <p>
 * Two upstreams are read from here - the architecture repository and the reaction observer - and the mechanics
 * are the same for both: a client-credentials token of this service, a read that cannot be talked into
 * consuming a gigabyte, no redirects, and one exception class whatever failed. Only the routes, the payloads
 * and the wording of the log lines differ, so those stay with each upstream and this does not.
 */
@Slf4j
public final class UpstreamHttp {

    /** How much of an error body is read while looking for the problem type. */
    private static final int PROBLEM_BODY_LIMIT = 8192;

    private UpstreamHttp() {
    }

    /**
     * A client of one upstream, carrying a client-credentials token of this service.
     * <p>
     * The token is issued by <b>that upstream's</b> authorization server rather than this service's own, which
     * is what the client registration names.
     */
    public static RestClient client(String url, String clientRegistration,
                                    UpstreamClientSettings settings,
                                    JeapOAuth2RestClientBuilderFactory clientBuilders,
                                    JsonMapper json) {
        return clientBuilders.createForClientRegistryId(clientRegistration)
                .baseUrl(url)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(settings.getConnectTimeout())
                                .withReadTimeout(settings.getReadTimeout())
                                // The origin of a URL out of a payload is checked before it is fetched, and a
                                // followed redirect would make that check hold for the first hop only: a 302
                                // off an on-origin path would have this service fetch and store whatever the
                                // Location named, and send its token there. Whether the token survives the hop
                                // is a property of whichever client is detected, which is not something to
                                // rely on.
                                .withRedirects(HttpRedirects.DONT_FOLLOW)))
                // Every failure carries the status and the problem type, so a caller can tell a system that
                // has gone away from an upstream that is down - and so the retry policy can select on the
                // class rather than parse a status a second time.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> raise(json, request,
                        response))
                .build();
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
     * the payload gave, and a redirect off it would fetch and store whatever it named instead.
     * <p>
     * The stored tag goes out <b>verbatim, quotes and all</b>. It is the header's own syntax on both sides:
     * unquoting it on the way in would mean re-quoting it on the way out, and a mismatch there does not fail -
     * it silently refetches every item on every run, for ever.
     */
    public static Answer getBounded(JsonMapper json, RestClient client, URI uri, String knownEtag,
                                    long cap) {
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
     * A path from a payload, resolved against the origin of the upstream rather than appended to it.
     * <p>
     * An upstream's own URLs already carry its context path, and so does the configured upstream. Appending one
     * to the other would produce the context path twice, and every item would answer {@code 404} - which is a
     * case the replication handles quietly, so it would look like an upstream that publishes nothing.
     * <p>
     * Empty means the path cannot be fetched - it is not a URI at all, or it does not stay on the upstream's
     * origin. Neither throws: one unusable entry in an index must not stop the items around it from being
     * replicated.
     *
     * @param upstream where the upstream is, absolute
     * @param subject  what a warning calls the upstream, such as {@code the reaction observer of the
     *                 environment dev}
     */
    public static Optional<URI> resolve(String upstream, String path, String subject) {
        if (path == null || path.isBlank()) {
            // The field is optional in the payload, and a blank path resolves to the upstream's own root -
            // which would fetch the service's home page and store it as content.
            log.warn("{} offered an index entry with no content URL. It is not fetched.", subject);
            return Optional.empty();
        }
        URI base = URI.create(upstream);
        URI resolved;
        try {
            resolved = base.resolve(path);
        } catch (IllegalArgumentException notAUri) {
            // A space, a brace, a pipe - URI.resolve parses its argument, and an unparseable one is the
            // upstream's mistake rather than this service's.
            log.warn("{} offered the content URL '{}', which is not a URI. It is not fetched.", subject, path);
            return Optional.empty();
        }
        // URI.resolve returns an absolute argument unchanged, so a payload naming another host would send this
        // service's token there. An upstream is a trusted peer and this is defence in depth - but the sentence
        // above promises the origin, so make it true.
        if (!sameOrigin(base, resolved)) {
            log.warn("{} offered the content URL '{}', which does not stay on {}. It is not fetched.",
                    subject, path, upstream);
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /** Turns a failing answer into the one exception the importers decide on. Always throws. */
    private static void raise(JsonMapper json, HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String message = "%s %s answered %d.".formatted(request.getMethod(), request.getURI(), status);
        String problemType = problemTypeOf(json, response.getHeaders().getFirst("Content-Type"),
                // Bounded and explicit: an upstream answering with a proxy's HTML error page must not be read
                // whole into memory, and the one field wanted is well inside this.
                new String(response.getBody().readNBytes(PROBLEM_BODY_LIMIT), StandardCharsets.UTF_8));
        throw UpstreamException.isWorthRetrying(status)
                ? new UpstreamException.Retryable(message, status, problemType, null)
                : new UpstreamException(message, status, problemType, null);
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
            log.debug("The error body of the upstream is not readable as JSON.", e);
            return null;
        }
    }

    private static boolean sameOrigin(URI upstream, URI resolved) {
        return upstream.getScheme() != null && upstream.getScheme().equalsIgnoreCase(resolved.getScheme())
               && upstream.getHost() != null && upstream.getHost().equalsIgnoreCase(resolved.getHost())
               && upstream.getPort() == resolved.getPort();
    }

    /**
     * What one bounded {@code GET} answered.
     *
     * @param body     the bytes, or null when the answer carried none - a {@code 304}, a redirect, or a body
     *                 refused for its size
     * @param tooLarge whether the body was refused for exceeding the cap
     */
    public record Answer(HttpStatusCode status, HttpHeaders headers, byte[] body,
                         boolean tooLarge) {

        public boolean isNotModified() {
            return status.value() == HttpStatus.NOT_MODIFIED.value();
        }

        /** A redirect that was not followed. {@code 304} is a {@code 3xx} too and is not one of these. */
        public boolean isRedirect() {
            return status.is3xxRedirection() && !isNotModified();
        }

        /** Where the redirect pointed, for the log line that says an item was skipped. */
        public String location() {
            String location = headers.getFirst(HttpHeaders.LOCATION);
            return location == null ? "nowhere it named" : location;
        }

        /** The entity tag verbatim, as it arrived. */
        public String etag() {
            return headers.getFirst(HttpHeaders.ETAG);
        }

        /**
         * Value equality over the body too, which the generated one would not give for an array.
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Answer answer
                   && Objects.equals(status, answer.status)
                   && Objects.equals(headers, answer.headers)
                   && Arrays.equals(body, answer.body)
                   && tooLarge == answer.tooLarge;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, headers, Arrays.hashCode(body), tooLarge);
        }

        /**
         * Without the body, so that a log line or a test failure does not print megabytes of it.
         */
        @Override
        public String toString() {
            return "Answer[status=%s %d bytes%s]"
                    .formatted(status, body == null ? 0 : body.length, tooLarge ? " tooLarge" : "");
        }
    }
}
