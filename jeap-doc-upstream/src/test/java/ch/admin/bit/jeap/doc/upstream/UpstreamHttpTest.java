package ch.admin.bit.jeap.doc.upstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of calling an upstream that belong to neither of the two adapters reading through them.
 * <p>
 * What needs a server on the other end - the cap of a bounded {@code GET}, the problem type of an error
 * answer - is driven against a real one in each adapter's own tests, because what those assert is wire
 * behaviour. What is decided before a request is made, or once one has answered, is decided here.
 */
class UpstreamHttpTest {

    private static final String UPSTREAM = "https://upstream.example.com/context";
    private static final String SUBJECT = "the upstream of the environment dev";

    /**
     * An upstream's own paths carry its context path, and so does the configured URL. Appending one to the
     * other produces it twice and answers {@code 404} on everything - which the replication handles quietly,
     * so it looks like an upstream that publishes nothing.
     */
    @Test
    void aPathIsResolvedAgainstTheOriginRatherThanAppended() {
        assertThat(UpstreamHttp.resolve(UPSTREAM, "/context/api/systems/orders", SUBJECT))
                .contains(URI.create("https://upstream.example.com/context/api/systems/orders"));
    }

    /**
     * {@code URI.resolve} returns an absolute argument unchanged, so a payload naming another host would send
     * this service's token there. The scheme and the port are part of the origin as much as the host is.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://elsewhere.example.com/context/api/systems/orders",
            "http://upstream.example.com/context/api/systems/orders",
            "https://upstream.example.com:8443/context/api/systems/orders"})
    void aPathThatLeavesTheOrigin_isNotFetched(String path) {
        assertThat(UpstreamHttp.resolve(UPSTREAM, path, SUBJECT)).isEmpty();
    }

    /** A blank path resolves to the upstream's own root, which would store its home page as content. */
    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void aPathThatIsBlank_isNotFetched(String path) {
        assertThat(UpstreamHttp.resolve(UPSTREAM, path, SUBJECT)).isEmpty();
    }

    @Test
    void aPathThatIsMissing_isNotFetched() {
        assertThat(UpstreamHttp.resolve(UPSTREAM, null, SUBJECT)).isEmpty();
    }

    /**
     * {@code URI.resolve} parses its argument, and an unparseable one is the upstream's mistake rather than
     * this service's: one unusable index entry must not stop the ones around it from being replicated.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/context/api/a b", "/context/api/{orders}", "/context/api/a|b"})
    void aPathThatIsNotAUri_isNotFetched(String path) {
        assertThat(UpstreamHttp.resolve(UPSTREAM, path, SUBJECT)).isEmpty();
    }

    /** A {@code 304} is a {@code 3xx} as well, and it is not a redirect anything has to decide about. */
    @Test
    void notModified_isNotReadAsARedirect() {
        UpstreamHttp.Answer answer = new UpstreamHttp.Answer(HttpStatus.NOT_MODIFIED, new HttpHeaders(), null,
                false);

        assertThat(answer.isNotModified()).isTrue();
        assertThat(answer.isRedirect()).isFalse();
    }

    @Test
    void aRedirect_saysWhereItPointed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.LOCATION, "https://elsewhere.example.com/");

        UpstreamHttp.Answer answer = new UpstreamHttp.Answer(HttpStatus.FOUND, headers, null, false);

        assertThat(answer.isRedirect()).isTrue();
        assertThat(answer.location()).isEqualTo("https://elsewhere.example.com/");
    }

    /** A redirect that named nowhere still has to log something, and null is not it. */
    @Test
    void aRedirectWithoutALocation_stillSaysSomething() {
        assertThat(new UpstreamHttp.Answer(HttpStatus.FOUND, new HttpHeaders(), null, false).location())
                .isEqualTo("nowhere it named");
    }

    /**
     * The tag is carried verbatim, quotes and all. Unquoting it here would mean re-quoting it on the way out,
     * and a mismatch there does not fail - it silently refetches everything on every run, for ever.
     */
    @Test
    void theEntityTagIsCarriedVerbatim() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ETAG, "\"sha256:abc123\"");

        assertThat(new UpstreamHttp.Answer(HttpStatus.OK, headers, new byte[0], false).etag())
                .isEqualTo("\"sha256:abc123\"");
    }

    /** A body of megabytes must not end up in a log line or a test failure. */
    @Test
    void anAnswerPrintsItsSizeRatherThanItsBody() {
        byte[] body = "the whole architecture model".getBytes(StandardCharsets.UTF_8);

        assertThat(new UpstreamHttp.Answer(HttpStatus.OK, new HttpHeaders(), body, false).toString())
                .contains(body.length + " bytes")
                .doesNotContain("architecture model");
    }

    /** Value equality over the body too, which the generated one would not give for an array. */
    @Test
    void twoAnswersWithTheSameBytes_areEqual() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ETAG, "\"one\"");

        assertThat(new UpstreamHttp.Answer(HttpStatus.OK, headers, "graph".getBytes(StandardCharsets.UTF_8),
                false))
                .isEqualTo(new UpstreamHttp.Answer(HttpStatus.OK, headers,
                        "graph".getBytes(StandardCharsets.UTF_8), false))
                .isNotEqualTo(new UpstreamHttp.Answer(HttpStatus.OK, headers,
                        "other".getBytes(StandardCharsets.UTF_8), false));
    }
}
