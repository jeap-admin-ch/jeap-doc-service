package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is tried again and what is not.
 * <p>
 * Asserted through the real retry policy rather than a mock of it, because what is being pinned is which
 * exception class the policy includes - and that is decided where the status is turned into one.
 */
class ArchRepoRetryTest {

    private static final String ENVIRONMENT = "dev";
    private static final String SYSTEMS = "/docs-api/systems";

    private WireMockServer archRepo;
    private ArchRepoModelUpstream model;

    @BeforeEach
    void setUp() {
        archRepo = new WireMockServer(options().dynamicPort());
        archRepo.start();
        model = new ArchRepoModelUpstream(TestClients.of(ENVIRONMENT, archRepo.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        archRepo.stop();
    }

    @Test
    void whenTheUpstreamFailsOnceAndThenAnswers_thenTheAnswerReachesTheCaller() {
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"systems\":[{\"name\":\"Orders\"}]}")));

        assertThat(model.systemNames(ENVIRONMENT)).containsExactly("Orders");

        archRepo.verify(exactly(2), getRequestedFor(urlEqualTo(SYSTEMS)));
    }

    /**
     * Two retries, so three attempts in all, and the last failure is what the caller sees - not a wrapper
     * saying the retries ran out.
     */
    @Test
    void whenTheUpstreamKeepsFailing_thenItGivesUpAfterThreeAttempts() {
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("500");

        archRepo.verify(exactly(3), getRequestedFor(urlEqualTo(SYSTEMS)));
    }

    /**
     * A wrong token, a missing role or a resource that is not there answers the same way however often it is
     * asked. Retrying only delays the message that says what to fix.
     */
    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void whenTheAnswerWillNotChange_thenItIsNotRetried(int status) {
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class);

        archRepo.verify(exactly(1), getRequestedFor(urlEqualTo(SYSTEMS)));
    }

    /**
     * <b>That the configured read timeout is really configured</b>, and that a read which times out is one of
     * the two things the policy retries. Both are easy to lose without noticing: an upstream that hangs would
     * then hold the import for whatever the JDK client's default is - which is none at all - and the request
     * factory could be dropped from the builder with every other test in this module staying green.
     */
    @Test
    void whenTheUpstreamHangs_thenTheReadTimesOutAndIsRetried() {
        model = new ArchRepoModelUpstream(TestClients.of(ENVIRONMENT, archRepo.baseUrl(), client -> {
            client.setReadTimeout(Duration.ofMillis(100));
            client.setRetryDelay(Duration.ofMillis(1));
        }));
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"systems\":[]}")
                .withFixedDelay(3_000)));

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class);

        archRepo.verify(exactly(3), getRequestedFor(urlEqualTo(SYSTEMS)));
    }

    @Test
    void whenTheUpstreamShedsLoad_thenItIsRetried() {
        archRepo.stubFor(get(urlEqualTo(SYSTEMS)).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class);

        archRepo.verify(exactly(3), getRequestedFor(urlEqualTo(SYSTEMS)));
    }
}
