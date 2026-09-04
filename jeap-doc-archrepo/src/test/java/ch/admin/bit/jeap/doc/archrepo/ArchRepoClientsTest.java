package ch.admin.bit.jeap.doc.archrepo;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an error answer of the architecture repository turns into.
 * <p>
 * The status decides whether one system is skipped or the whole run fails, so it has to survive. The problem
 * type only makes the message better, and nothing about it may fail a run.
 */
class ArchRepoClientsTest {

    private static final String ENVIRONMENT = "dev";

    private WireMockServer archRepo;
    private DocsApiClient client;

    @BeforeEach
    void setUp() {
        archRepo = new WireMockServer(options().dynamicPort());
        archRepo.start();
        client = TestClients.of(ENVIRONMENT, archRepo.baseUrl()).of(ENVIRONMENT).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        archRepo.stop();
    }

    @Test
    void anErrorCarriesTheStatusAndTheProblemType() {
        stubSystems(404, "application/problem+json",
                "{\"type\":\"system-not-found\",\"status\":404,\"title\":\"No such system\"}");

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getProblemType()).isEqualTo("system-not-found");
                    assertThat(e.isNotFound()).isTrue();
                });
    }

    @Test
    void aRefusedTokenIsToldApartFromEverythingElse() {
        stubSystems(403, "application/problem+json", "{\"type\":\"forbidden\",\"status\":403}");

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class, e -> assertThat(e.isUnauthorized()).isTrue());
    }

    /**
     * The body is whatever the upstream sent. A proxy's HTML error page, an empty answer or a body cut off at
     * the read limit all have to come out as the status and no problem type.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<html><body>502 Bad Gateway</body></html>",
            "",
            "{\"type\":\"cut-off-half-way",
            "{\"type\":{\"not\":\"a string\"}}",
            "[]"})
    void aBodyThatIsNotAProblemDocument_stillProducesTheStatus(String body) {
        stubSystems(500, "application/problem+json", body);

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getProblemType()).isNull();
                });
    }

    /**
     * A problem document is the only thing read for a type. An answer that merely happens to carry a
     * {@code type} field is not one.
     */
    @Test
    void aBodyThatIsNotProblemJson_isNotReadForAType() {
        stubSystems(500, "application/json", "{\"type\":\"not-a-problem-document\"}");

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class,
                        e -> assertThat(e.getProblemType()).isNull());
    }

    /**
     * An upstream answering with a large page must not be read whole into memory. What matters is that the read
     * is bounded and the run still ends in the same exception.
     */
    @Test
    void aVeryLargeErrorBody_isNotReadWhole() {
        stubSystems(503, "text/html", "x".repeat(2_000_000));

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(503));
    }

    /**
     * <b>That the bound is really a bound</b>, and not merely that a large answer still produces its status.
     * The problem document here is valid JSON and its {@code type} is the last field of it, pushed past the
     * limit by a {@code detail} large enough to fill it - so a read that took the whole body would find the
     * type, and the read that stops at the limit cannot. Delete the limit and this test says so; the test
     * above it would stay green.
     */
    @Test
    void aProblemDocumentLongerThanTheLimit_isReadOnlyAsFarAsTheLimit() {
        String padding = "x".repeat(16_384);
        stubSystems(503, "application/problem+json",
                "{\"status\":503,\"detail\":\"" + padding + "\",\"type\":\"upstream-unavailable\"}");

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getProblemType())
                            .describedAs("the type is past the read limit, so it is not found")
                            .isNull();
                });
    }

    /**
     * The same document, small enough that the type <i>is</i> inside the limit. Without this the test above
     * would also pass on a client that never reads an error body at all.
     */
    @Test
    void aProblemDocumentInsideTheLimit_isReadWhole() {
        stubSystems(503, "application/problem+json",
                "{\"status\":503,\"detail\":\"" + "x".repeat(1024)
                + "\",\"type\":\"upstream-unavailable\"}");

        assertThatThrownBy(() -> client.systems())
                .isInstanceOfSatisfying(ArchRepoException.class,
                        e -> assertThat(e.getProblemType()).isEqualTo("upstream-unavailable"));
    }

    private void stubSystems(int status, String contentType, String body) {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", contentType)
                .withBody(body)));
    }
}
