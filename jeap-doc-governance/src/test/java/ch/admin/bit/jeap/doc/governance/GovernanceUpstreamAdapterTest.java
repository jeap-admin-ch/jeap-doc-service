package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Governance Service's {@code /docs-api} against a real HTTP server.
 * <p>
 * Every request is conditional here, unlike the architecture repository's model resources: the Governance
 * Service tags both indexes from a stored hash too, so a conditional request saves it the same work a "not
 * modified" on an artifact index saves the architecture repository.
 */
class GovernanceUpstreamAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-04T08:45:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private WireMockServer governanceService;
    private GovernanceUpstreamAdapter upstream;
    private final GovernanceProperties properties = new GovernanceProperties();

    @BeforeEach
    void setUp() {
        // Gzip off: wiremock appends "--gzip" to a compressed entity tag, and these tests are about the tag
        // arriving verbatim.
        governanceService = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        governanceService.start();
        GovernanceClients clients = TestClients.of(governanceService.baseUrl());
        upstream = new GovernanceUpstreamAdapter(clients, properties, CLOCK);
    }

    @AfterEach
    void tearDown() {
        governanceService.stop();
    }

    @Test
    void isConfigured_thenTrue() {
        assertThat(upstream.isConfigured()).isTrue();
    }

    /**
     * A Governance Service is served below a context path, and the configured URL names it. Every other test here
     * talks to the root of its server, which is how a request that dropped the path went unnoticed: the index was
     * asked for at {@code /docs-api/systems} of the host, and the host answered 403 for a path it does not serve.
     */
    @Test
    void systemsIndex_whenTheConfiguredUrlHasAPath_thenTheRequestIsMadeBelowIt() {
        governanceService.stubFor(get(urlEqualTo("/applicationplatform-governance-service/docs-api/systems"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"index-1\"")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"scoredAt\":\"2026-11-05T09:45:22+01:00\",\"trendPeriodDays\":30,"
                                  + "\"systems\":[{\"name\":\"orders\",\"score\":73,\"trend\":\"DOWN\","
                                  + "\"etag\":\"\\\"e1\\\"\",\"url\":\"/applicationplatform-governance-service"
                                  + "/docs-api/systems/orders\"}]}")));
        governanceService.stubFor(get(urlEqualTo("/applicationplatform-governance-service/docs-api/systems/orders"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"e1\"")
                        .withHeader("Content-Type", "application/json").withBody("{\"name\":\"orders\"}")));
        GovernanceClients below = TestClients.of(governanceService.baseUrl() + "/applicationplatform-governance-service");
        GovernanceUpstreamAdapter underAPath = new GovernanceUpstreamAdapter(below, properties, CLOCK);

        Optional<GovernanceIndexFetch> index = underAPath.systemsIndex(null);

        assertThat(index).isPresent();
        assertThat(index.get().entries()).extracting(GovernanceResourceRef::key).containsExactly("orders");
        assertThat(underAPath.system("orders", null)).isInstanceOf(GovernanceResourceFetch.Stored.class);
    }

    @Test
    void isConfigured_whenNoUrlIsSet_thenFalse() {
        GovernanceClients unconfigured = TestClients.of(null);
        assertThat(new GovernanceUpstreamAdapter(unconfigured, properties, CLOCK).isConfigured()).isFalse();
    }

    @Test
    void systemsIndex_whenTheUpstreamAnswersNotModified_thenItIsEmpty() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems"))
                .willReturn(aResponse().withStatus(304)));

        assertThat(upstream.systemsIndex("\"sha256:index\"")).isEmpty();
    }

    @Test
    void systemsIndex_thenTheStoredTagIsSentBackVerbatimWithItsQuotes() {
        stub("/docs-api/systems", "{\"scoredAt\":null,\"trendPeriodDays\":30,\"systems\":[]}", "\"irrelevant\"");

        upstream.systemsIndex("\"sha256:41ab7c\"");

        // Verbatim on both sides: unquoting on the way in would mean requoting on the way out, and a mismatch
        // there does not fail - it refetches everything on every run, for ever.
        governanceService.verify(getRequestedFor(urlEqualTo("/docs-api/systems"))
                .withHeader("If-None-Match", equalTo("\"sha256:41ab7c\"")));
    }

    @Test
    void systemsIndex_thenTheBodyAndTheEntriesAreBothCarried() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"index-1\"")
                .withBody("""
                        {"scoredAt":"2026-11-03T10:47:00Z","trendPeriodDays":30,
                         "systems":[{"name":"orders","score":73,"trend":"DOWN","etag":"\\"sha256:aaa\\"",
                                     "url":"/docs-api/systems/orders"}]}""")));

        Optional<GovernanceIndexFetch> index = upstream.systemsIndex(null);

        assertThat(index).isPresent();
        assertThat(index.get().etag()).isEqualTo("\"index-1\"");
        assertThat(new String(index.get().body(), StandardCharsets.UTF_8)).contains("\"orders\"");
        assertThat(index.get().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(GovernanceResourceKind.SYSTEM);
            assertThat(entry.key()).isEqualTo("orders");
            assertThat(entry.etag()).isEqualTo("\"sha256:aaa\"");
        });
    }

    @Test
    void rulesIndex_thenTheEntriesCarryTheRuleIdAndTag() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/rules")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("ETag", "\"rules-1\"")
                .withBody("""
                        {"scoredAt":"2026-11-03T10:47:00Z",
                         "rules":[{"ruleId":"naming-convention","name":"Naming convention","active":true,
                                   "weight":10,"share":50.0,"conformanceRate":90,"trend":"STABLE",
                                   "exemptionCount":0,"etag":"\\"sha256:bbb\\"",
                                   "url":"/docs-api/rules/naming-convention"}]}""")));

        Optional<GovernanceIndexFetch> index = upstream.rulesIndex(null);

        assertThat(index).isPresent();
        assertThat(index.get().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(GovernanceResourceKind.RULE);
            assertThat(entry.key()).isEqualTo("naming-convention");
            assertThat(entry.etag()).isEqualTo("\"sha256:bbb\"");
        });
    }

    /**
     * An answer with no list in it is a failure, not an empty index - see {@code ArchRepoUpstreamTest} for why:
     * what a run does with an index that lists nothing is delete every stored system or rule.
     */
    @Test
    void systemsIndex_whenTheAnswerCarriesNoListAtAll_thenItFailsRatherThanReadingAsEmpty() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"irrelevant\"")));

        assertThatThrownBy(() -> upstream.systemsIndex(null))
                .isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("answered with no body");
    }

    /**
     * A body that parses but names no list at all - a different malformation than an empty body, and one
     * that must fail for the same reason: an empty index would delete every stored system.
     */
    @Test
    void systemsIndex_whenTheBodyHasNoSystemsField_thenItFailsRatherThanReadingAsEmpty() {
        stub("/docs-api/systems", "{}", "\"irrelevant\"");

        assertThatThrownBy(() -> upstream.systemsIndex(null))
                .isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("without a list of systems");
    }

    /** An entry dropped from the list reads as withdrawn, and the import deletes what it has stored for it. */
    @Test
    void systemsIndex_whenOneEntryHasNoEntityTag_thenTheWholeIndexIsRefused() {
        stub("/docs-api/systems", """
                {"systems":[{"name":"orders","etag":"\\"sha256:aaa\\""},{"name":"shipping"}]}""", "\"index-1\"");

        assertThatThrownBy(() -> upstream.systemsIndex(null))
                .isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("no identity or no entity tag");
    }

    @Test
    void rulesIndex_whenOneEntryHasNoRuleId_thenTheWholeIndexIsRefused() {
        stub("/docs-api/rules", """
                {"rules":[{"ruleId":"R1","etag":"\\"sha256:aaa\\""},{"etag":"\\"sha256:bbb\\""}]}""",
                "\"index-1\"");

        assertThatThrownBy(() -> upstream.rulesIndex(null))
                .isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("no identity or no entity tag");
    }

    @Test
    void system_thenTheContentArrivesWithItsEntityTag() {
        stub("/docs-api/systems/orders", "{\"name\":\"orders\",\"score\":73}", "\"sha256:aaa\"");

        GovernanceResourceFetch fetch = upstream.system("orders", null);

        assertThat(fetch).isInstanceOfSatisfying(GovernanceResourceFetch.Stored.class, stored -> {
            GovernanceResource resource = stored.resource();
            assertThat(resource.kind()).isEqualTo(GovernanceResourceKind.SYSTEM);
            assertThat(resource.key()).isEqualTo("orders");
            assertThat(resource.etag()).isEqualTo("\"sha256:aaa\"");
            assertThat(new String(resource.content(), StandardCharsets.UTF_8)).contains("orders");
            assertThat(resource.importedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void system_whenANameWithASlash_thenItIsEncoded() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems/orders%2Ffulfilment"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withBody("{\"name\":\"orders/fulfilment\"}")));

        assertThat(upstream.system("orders/fulfilment", null))
                .isInstanceOf(GovernanceResourceFetch.Stored.class);
    }

    @Test
    void rule_whenTheUpstreamAnswersNotModified_thenTheStoredCopyIsConfirmed() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/rules/naming-convention"))
                .withHeader("If-None-Match", equalTo("\"sha256:aaa\""))
                .willReturn(aResponse().withStatus(304)));

        assertThat(upstream.rule("naming-convention", "\"sha256:aaa\""))
                .isInstanceOf(GovernanceResourceFetch.Unchanged.class);
    }

    @Test
    void rule_whenTheUpstreamAnswersNotModifiedToAnUnconditionalRequest_thenItIsSkipped() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/rules/naming-convention"))
                .willReturn(aResponse().withStatus(304)));

        assertThat(upstream.rule("naming-convention", null))
                .isInstanceOf(GovernanceResourceFetch.Skipped.class);
    }

    @Test
    void system_whenTheSystemWentAwayBetweenTheIndexAndTheFetch_thenItIsSkipped() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems/orders"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"system-not-found\"}")));

        assertThat(upstream.system("orders", null)).isInstanceOf(GovernanceResourceFetch.Skipped.class);
    }

    @Test
    void system_whenTheAnswerCarriesNoEntityTag_thenItIsNotReplicated() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems/orders"))
                .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"orders\"}")));

        assertThat(upstream.system("orders", null)).isInstanceOf(GovernanceResourceFetch.Skipped.class);
    }

    @Test
    void system_whenTheUpstreamRedirects_thenItIsNotFollowedAndSkipped() {
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems/orders"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "https://elsewhere.example/steal")));

        assertThat(upstream.system("orders", null)).isInstanceOf(GovernanceResourceFetch.Skipped.class);
        governanceService.verify(0, getRequestedFor(urlEqualTo("/steal")));
    }

    @Test
    void system_whenTheBodyIsLargerThanTheCap_thenItIsNotReplicated() {
        properties.setMaxResourceSize(org.springframework.util.unit.DataSize.ofBytes(8));
        governanceService.stubFor(get(urlEqualTo("/docs-api/systems/orders"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withBody("x".repeat(64))));

        assertThat(upstream.system("orders", null)).isInstanceOf(GovernanceResourceFetch.Skipped.class);
    }

    @Test
    void systemsIndex_thenNoUnexpectedConditionalHeaderIsSentWhenNoneIsKnown() {
        stub("/docs-api/systems", "{\"systems\":[]}", "\"irrelevant\"");

        upstream.systemsIndex(null);

        governanceService.verify(getRequestedFor(urlEqualTo("/docs-api/systems"))
                .withHeader("If-None-Match", absent()));
    }

    private void stub(String path, String body) {
        stub(path, body, null);
    }

    private void stub(String path, String body, String etag) {
        var response = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
        if (etag != null) {
            response = response.withHeader("ETag", etag);
        }
        governanceService.stubFor(get(urlEqualTo(path)).willReturn(response));
    }
}
