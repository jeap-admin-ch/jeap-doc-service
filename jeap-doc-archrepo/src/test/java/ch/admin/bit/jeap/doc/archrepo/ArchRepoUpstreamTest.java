package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.ArchitectureImportProperties;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifactRef;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.SystemTopology;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.ArtifactFetch;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * The two upstreams against a real HTTP server.
 * <p>
 * The two halves of {@code /docs-api} are read in deliberately different ways, and that is what is pinned here:
 * the model resources unconditionally, because the architecture repository tags them by serializing the whole
 * body and a conditional request would save it nothing; the artifacts conditionally, because there the tag comes
 * from a stored hash and a "not modified" saves a blob.
 */
class ArchRepoUpstreamTest {

    private static final String ENVIRONMENT = "dev";
    private static final ArchitectureImportKind KIND = ArchitectureImportKind.OPENAPI_SPEC;

    private WireMockServer archRepo;
    private ArchRepoModelUpstream model;
    private ArchRepoArtifactUpstream artifacts;
    private final ArchitectureImportProperties importProperties = new ArchitectureImportProperties();

    @BeforeEach
    void setUp() {
        // Gzip off: wiremock appends "--gzip" to an entity tag it compresses, and these tests are about
        // the tag arriving verbatim.
        archRepo = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        archRepo.start();
        ArchRepoClients clients = TestClients.of(ENVIRONMENT, archRepo.baseUrl());
        model = new ArchRepoModelUpstream(clients);
        artifacts = new ArchRepoArtifactUpstream(clients, importProperties);
    }

    @AfterEach
    void tearDown() {
        archRepo.stop();
    }

    @Test
    void systemNames_thenTheListIsReadInTheOrderItIsServed() {
        stub("/docs-api/systems", 200, "{\"systems\":[{\"name\":\"Orders\"},{\"name\":\"Shipping\"}]}");

        assertThat(model.systemNames(ENVIRONMENT)).containsExactly("Orders", "Shipping");
    }

    /**
     * A conditional request would cost the architecture repository a full model load and serialization anyway,
     * because that is how it computes the tag of a model resource. Sending one would only look thrifty.
     */
    @Test
    void systemNames_thenNoConditionalRequestIsMade() {
        stub("/docs-api/systems", 200, "{\"systems\":[]}");

        model.systemNames(ENVIRONMENT);

        archRepo.verify(getRequestedFor(urlEqualTo("/docs-api/systems"))
                .withHeader("If-None-Match", absent()));
    }

    @Test
    void topology_thenTheComponentsArriveWithoutASlug() {
        stub("/docs-api/systems/Orders", 200, """
                {"name":"Orders","description":"Everything about orders",
                 "components":[{"name":"orders-payment-scs","type":"BACKEND"}],
                 "relations":[]}""");

        Optional<SystemTopology> topology = model.topology(ENVIRONMENT, "Orders");

        assertThat(topology).isPresent();
        assertThat(topology.get().name()).isEqualTo("Orders");
        // The slug is the doc service's decision, so the adapter leaves it to the importer.
        assertThat(topology.get().components()).singleElement()
                .satisfies(component -> assertThat(component.slug()).isNull());
    }

    @Test
    void topology_whenTheSystemIsGone_thenItReadsAsNotThereRatherThanAsAFailure() {
        stub("/docs-api/systems/Orders", 404, "{\"type\":\"system-not-found\"}");

        assertThat(model.topology(ENVIRONMENT, "Orders")).isEmpty();
    }

    @Test
    void messages_thenTheyAreMappedWithTheirContracts() {
        stub("/docs-api/systems/Orders/messages", 200, """
                {"messages":[{"name":"OrdersPaymentAcceptedEvent","kind":"EVENT","topic":"orders.payment",
                  "versions":["1.0.0"],
                  "contracts":[{"role":"PUBLISHER","component":"orders-payment-scs","system":"Orders",
                                "versions":["1.0.0"]}]}]}""");

        Optional<List<DocumentedMessage>> messages = model.messages(ENVIRONMENT, "Orders");

        assertThat(messages).isPresent();
        assertThat(messages.get()).singleElement().satisfies(message -> {
            assertThat(message.name()).isEqualTo("OrdersPaymentAcceptedEvent");
            assertThat(message.producers()).hasSize(1);
        });
    }

    @Test
    void systemNames_whenTheRoleIsMissing_thenTheMessageSaysWhichClientRegistrationToLookAt() {
        stub("/docs-api/systems", 403, "{\"type\":\"forbidden\"}");

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("client-registration")
                .hasMessageContaining("architecture-model_#read");
    }

    @Test
    void index_thenTheStoredTagIsSentBackVerbatimWithItsQuotes() {
        stub("/docs-api/openapi-specs", 200, "{\"artifacts\":[]}");

        artifacts.index(ENVIRONMENT, KIND, "\"sha256:41ab7c\"");

        // Verbatim on both sides: unquoting on the way in would mean requoting on the way out, and a mismatch
        // there does not fail - it refetches everything on every run, for ever.
        archRepo.verify(getRequestedFor(urlEqualTo("/docs-api/openapi-specs"))
                .withHeader("If-None-Match", equalTo("\"sha256:41ab7c\"")));
    }

    @Test
    void index_whenTheUpstreamAnswersNotModified_thenItIsEmptyRatherThanAnEmptyList() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/openapi-specs"))
                .willReturn(aResponse().withStatus(304)));

        assertThat(artifacts.index(ENVIRONMENT, KIND, "\"sha256:41ab7c\"")).isEmpty();
    }

    @Test
    void index_thenTheEntriesCarryTheirTagAndTheirContentUrl() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/openapi-specs")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"index-1\"")
                .withBody("""
                        {"artifacts":[{"system":"Orders","component":"orders-payment-scs","version":"2.1.0",
                          "etag":"\\"sha256:aaa\\"",
                          "contentUrl":"/docs-api/systems/Orders/components/orders-payment-scs/openapi"}]}""")));

        Optional<Fetched<List<ArchitectureArtifactRef>>> index = artifacts.index(ENVIRONMENT, KIND, null);

        assertThat(index).isPresent();
        assertThat(index.get().etag()).isEqualTo("\"index-1\"");
        assertThat(index.get().value()).singleElement().satisfies(entry -> {
            assertThat(entry.system()).isEqualTo("Orders");
            assertThat(entry.etag()).isEqualTo("\"sha256:aaa\"");
        });
    }

    /**
     * The content path already carries the architecture repository's context path, so it is resolved against
     * the origin of the upstream and never appended to it. Appending would produce the context path twice and
     * answer 404 on every artifact - which the replication handles quietly, so it would look like an
     * architecture repository that publishes nothing.
     */
    @Test
    void content_whenTheUpstreamHasAContextPath_thenTheUrlIsResolvedAgainstTheOrigin() {
        ArchRepoClients withContextPath = TestClients.of(ENVIRONMENT, archRepo.baseUrl() + "/archrepo");
        ArchRepoArtifactUpstream upstream = new ArchRepoArtifactUpstream(withContextPath, importProperties);
        archRepo.stubFor(get(urlEqualTo("/archrepo/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withBody("{\"openapi\":\"3.0.0\"}")));

        ArtifactFetch content = upstream.content(ENVIRONMENT,
                entryWith("/archrepo/docs-api/systems/Orders/components/a-scs/openapi"), null);

        assertThat(content).isInstanceOf(ArtifactFetch.Stored.class);
        ArchitectureArtifact artifact = ((ArtifactFetch.Stored) content).artifact();
        assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).contains("openapi");
        assertThat(artifact.etag()).isEqualTo("\"sha256:aaa\"");
    }

    @Test
    void content_whenTheArtifactWentAwayBetweenTheIndexAndTheFetch_thenItIsSkipped() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"openapi-spec-not-found\"}")));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    /**
     * A specification larger than the cap is a defect upstream. It is left where it is rather than stored, and
     * the run carries on with the rest.
     */
    @Test
    void content_whenTheArtifactIsLargerThanTheCap_thenItIsNotReplicated() {
        importProperties.setMaxArtifactSize(DataSize.ofBytes(64));
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withBody("x".repeat(65))));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    @Test
    void content_whenTheArtifactIsWithinTheCap_thenItIsReplicated() {
        importProperties.setMaxArtifactSize(DataSize.ofBytes(64));
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withBody("x".repeat(64))));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null)).isInstanceOf(ArtifactFetch.Stored.class);
    }

    /**
     * One entry of an index the upstream should not have offered. It is skipped like an artifact that is not
     * there, so the rest of the index is still replicated - aborting the run over one bad row would cost every
     * other specification of that kind.
     */
    @Test
    void content_whenTheUrlIsOnAnotherOrigin_thenTheArtifactIsSkippedRatherThanTheRunAbandoned() {
        assertThat(artifacts.content(ENVIRONMENT, entryWith("https://elsewhere.example/steal"), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    @Test
    void content_whenTheUrlIsNotAUriAtAll_thenTheArtifactIsSkippedRatherThanTheRunAbandoned() {
        assertThat(artifacts.content(ENVIRONMENT, entryWith("/docs-api/a path/openapi"), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    /**
     * Both fields are optional in the payload, and a blank one resolves to the upstream's own root - which
     * would fetch its home page and store it as a specification.
     */
    @Test
    void content_whenThereIsNoUrlAtAll_thenTheArtifactIsSkippedRatherThanTheRunAbandoned() {
        assertThat(artifacts.content(ENVIRONMENT, entryWith(null), null)).isInstanceOf(ArtifactFetch.Skipped.class);
        assertThat(artifacts.content(ENVIRONMENT, entryWith("  "), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    /**
     * A "not modified" against the tag of the stored copy is a confirmation and not a skip: the copy is
     * current, and the index tag stays trustworthy. Without a stored copy there is nothing to confirm.
     */
    @Test
    void content_whenTheUpstreamAnswersNotModified_thenTheStoredCopyIsConfirmed() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .withHeader("If-None-Match", equalTo("\"sha256:aaa\""))
                .willReturn(aResponse().withStatus(304)));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), "\"sha256:aaa\""))
                .isInstanceOf(ArtifactFetch.Unchanged.class);
    }

    @Test
    void content_whenTheUpstreamAnswersNotModifiedToAnUnconditionalRequest_thenItIsSkipped() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(304)));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null))
                .isInstanceOf(ArtifactFetch.Skipped.class);
    }

    /**
     * What is stored is addressed by its entity tag: the next run asks conditionally with it, and the column
     * that holds it refuses null. An artifact that arrives without one is left where it is rather than taking
     * the replication down on a constraint the upstream caused.
     */
    @Test
    void content_whenTheAnswerCarriesNoEntityTag_thenTheArtifactIsNotReplicated() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withBody("{\"openapi\":\"3.0.0\"}")));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null)).isInstanceOf(ArtifactFetch.Skipped.class);
    }

    /**
     * <b>The cap bounds the read, not only what is stored.</b> The answer advertises no length - chunked - so
     * nothing but the bound itself can stop it, and the artifact is a hundred times the cap: a read that took
     * the whole body would have it in memory before anything could refuse it.
     */
    @Test
    void content_whenAnUnboundedAnswerIsLargerThanTheCap_thenItIsNotReadWhole() {
        importProperties.setMaxArtifactSize(DataSize.ofKilobytes(1));
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:aaa\"")
                        .withChunkedDribbleDelay(2, 10)
                        .withBody("x".repeat(100 * 1024))));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null))
                .isInstanceOfSatisfying(ArtifactFetch.Skipped.class,
                        skipped -> assertThat(skipped.reason()).contains("larger than"));
    }

    /**
     * A component publishing an empty specification is <b>stored</b>, as the empty artifact it is. Spring hands
     * out a null body for a zero-length 200, and reading that as "arrived without a body" skipped it on every
     * run for ever - and one skipped entry keeps the whole kind from ever trusting the index tag again.
     */
    @Test
    void content_whenTheBodyIsEmpty_thenTheEmptyArtifactIsStored() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"sha256:empty\"")));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null))
                .isInstanceOfSatisfying(ArtifactFetch.Stored.class, stored -> {
                    assertThat(stored.artifact().content()).isEmpty();
                    assertThat(stored.artifact().sizeInBytes()).isZero();
                });
    }

    /**
     * <b>A redirect is not followed.</b> The origin of a content URL is checked before it is fetched, and
     * following a hop off it would make that check hold for the first request only - the body of whatever the
     * Location named would be stored as the specification. The entry is skipped; where the content really is
     * is the upstream's to say in its index.
     */
    @Test
    void content_whenTheUpstreamRedirects_thenItIsNotFollowedAndTheArtifactIsSkipped() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems/Orders/components/a-scs/openapi"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "https://elsewhere.example/steal")));

        assertThat(artifacts.content(ENVIRONMENT,
                entryWith("/docs-api/systems/Orders/components/a-scs/openapi"), null))
                .isInstanceOfSatisfying(ArtifactFetch.Skipped.class,
                        skipped -> assertThat(skipped.reason()).contains("redirect"));
        archRepo.verify(0, getRequestedFor(urlEqualTo("/steal")));
    }

    /**
     * <b>An answer with no list in it is a failure, not an empty index.</b> What a run does with an index that
     * lists nothing is delete every artifact stored for the environment and kind - and Spring hands out a null
     * body for any zero-length 200, which is what a proxy or a truncated answer looks like. The 404 case is
     * refused for the same reason; an index that really is empty answers with an empty list, which is a
     * different thing and reads as one.
     */
    @Test
    void index_whenTheAnswerCarriesNoListAtAll_thenItFailsRatherThanReadingAsEmpty() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/openapi-specs"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> artifacts.index(ENVIRONMENT, KIND, null))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("without a list of artifacts");
    }

    @Test
    void index_whenTheListIsEmpty_thenItReadsAsAnEmptyIndex() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/openapi-specs")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"empty\"")
                .withBody("{\"artifacts\":[]}")));

        assertThat(artifacts.index(ENVIRONMENT, KIND, null))
                .get()
                .extracting(Fetched::value, as(list(ArchitectureArtifactRef.class)))
                .isEmpty();
    }

    /**
     * The same for the system list: a run that fetches no system replaces the stored model with an empty one,
     * so an answer that carries no list at all must not read as a landscape without systems.
     */
    @Test
    void systemNames_whenTheAnswerCarriesNoListAtAll_thenItFailsRatherThanReadingAsEmpty() {
        archRepo.stubFor(get(urlEqualTo("/docs-api/systems")).willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> model.systemNames(ENVIRONMENT))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("without a list of systems");
    }

    private static ArchitectureArtifactRef entryWith(String contentUrl) {
        return new ArchitectureArtifactRef(ENVIRONMENT, KIND, "Orders", "a-scs", "2.1.0", "\"sha256:aaa\"",
                null, contentUrl, null);
    }

    private void stub(String path, int status, String body) {
        archRepo.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", status >= 400 ? "application/problem+json" : "application/json")
                .withBody(body)));
    }
}
