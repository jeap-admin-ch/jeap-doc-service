package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraph;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraphRef;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.Fetched;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.COMPONENT_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.MESSAGE_REACTIONS;
import static ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind.SYSTEM_REACTIONS;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The reaction observer's replication API against a real HTTP server.
 * <p>
 * Two things here are the observer's own and are pinned rather than assumed: an index lists what exists with a
 * tag per entry, and a message type's variants are addressed by <b>keys</b> that carry the type in front of
 * them - which is what makes one index entry several graphs.
 */
class ReactionObserverUpstreamTest {

    private static final String ENVIRONMENT = "dev";
    private static final String SYSTEM_INDEX = "/api/graphs/systems";
    private static final String COMPONENT_INDEX = "/api/graphs/components";
    private static final String MESSAGE_INDEX = "/api/graphs/messages";
    private static final String GRAPH = "{\"nodes\":[],\"edges\":[]}";

    private WireMockServer observer;
    private ReactionObserverUpstream upstream;
    private final ReactionObserverProperties properties = new ReactionObserverProperties();

    @BeforeEach
    void setUp() {
        // Gzip off: wiremock appends "--gzip" to an entity tag it compresses, and these tests are about the
        // tag arriving verbatim. No retries: a test that asserts a failure should not wait one out.
        observer = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        observer.start();
        upstream = new ReactionObserverUpstream(
                TestClients.of(ENVIRONMENT, observer.baseUrl(), client -> client.setRetries(0)),
                properties);
    }

    @AfterEach
    void tearDown() {
        observer.stop();
    }

    @Test
    void isConfiguredFor_thenOnlyTheEnvironmentThatNamesAnObserver() {
        assertThat(upstream.isConfiguredFor(ENVIRONMENT)).isTrue();
        assertThat(upstream.isConfiguredFor("prod")).isFalse();
    }

    @Test
    void systemIndex_thenEveryEntryIsAReferenceCarryingItsOwnTag() {
        stub(SYSTEM_INDEX, 200, """
                {"entries":[{"name":"orders","system":null,"etag":"\\"sha256:a\\"","path":"/api/graphs/systems/orders"},
                            {"name":"shipping","system":null,"etag":"\\"sha256:b\\"","path":"/api/graphs/systems/shipping"}]}
                """, "\"sha256:index\"");

        Fetched<List<ReactionGraphRef>> index = upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, null)
                .orElseThrow();

        assertThat(index.etag()).isEqualTo("\"sha256:index\"");
        assertThat(index.value()).extracting(ReactionGraphRef::name, ReactionGraphRef::etag,
                        ReactionGraphRef::variant, ReactionGraphRef::kind)
                .containsExactly(tuple("orders", "\"sha256:a\"", "",
                                SYSTEM_REACTIONS),
                        tuple("shipping", "\"sha256:b\"", "",
                                SYSTEM_REACTIONS));
    }

    /**
     * The tag of the last run's index goes back verbatim, and a {@code 304} is the whole answer: nothing in
     * this environment's reactions moved.
     */
    @Test
    void systemIndex_whenNothingMoved_thenNothingIsFetched() {
        observer.stubFor(get(urlEqualTo(SYSTEM_INDEX))
                .withHeader("If-None-Match", equalTo("\"sha256:index\""))
                .willReturn(aResponse().withStatus(304)));

        assertThat(upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, "\"sha256:index\"")).isEmpty();
        observer.verify(getRequestedFor(urlEqualTo(SYSTEM_INDEX))
                .withHeader("If-None-Match", equalTo("\"sha256:index\"")));
    }

    /**
     * The component index names the system a component's reactions were published under. It is the field that
     * removes a guess: the architecture repository instead intersects its own component names with the
     * observer's and takes the system from its model.
     */
    @Test
    void componentIndex_thenTheSystemTheReactionsWerePublishedUnderComesWithIt() {
        stub(COMPONENT_INDEX, 200, """
                {"entries":[{"name":"orders-service","system":"orders","etag":"\\"sha256:c\\"",
                             "path":"/api/graphs/components/orders-service"}]}
                """, "\"sha256:index\"");

        assertThat(upstream.index(ENVIRONMENT, COMPONENT_REACTIONS, null).orElseThrow().value())
                .singleElement()
                .satisfies(ref -> {
                    assertThat(ref.name()).isEqualTo("orders-service");
                    assertThat(ref.system()).isEqualTo("orders");
                });
    }

    /**
     * One entry, three graphs: the resource answers every variant of a type at once and its tag covers all of
     * them, so what is stored is a row per variant and what is compared is the type's tag.
     */
    @Test
    void messageIndex_thenOneEntryIsAReferencePerVariant() {
        stub(MESSAGE_INDEX, 200, """
                {"entries":[{"messageType":"OrderCreatedEvent",
                             "variants":["OrderCreatedEvent","OrderCreatedEvent/legacy"],
                             "etag":"\\"sha256:m\\"","path":"/api/graphs/messages/OrderCreatedEvent"}]}
                """, "\"sha256:index\"");

        assertThat(upstream.index(ENVIRONMENT, MESSAGE_REACTIONS, null).orElseThrow().value())
                .extracting(ReactionGraphRef::name, ReactionGraphRef::variant, ReactionGraphRef::etag)
                .containsExactly(
                        tuple("OrderCreatedEvent", "", "\"sha256:m\""),
                        tuple("OrderCreatedEvent", "legacy", "\"sha256:m\""));
    }

    /**
     * The variant is what is left of a key once the message type in front of it is removed - which is the one
     * safe way to read it, and the reason the architecture repository's normalization is not ported: it parses
     * the same key without knowing the type, so a type whose name contains a slash becomes a variant of a
     * shorter type.
     */
    @Test
    void messageIndex_whenATypeNameContainsASlash_thenItIsNotMistakenForAVariant() {
        stub(MESSAGE_INDEX, 200, """
                {"entries":[{"messageType":"orders/OrderCreatedEvent",
                             "variants":["orders/OrderCreatedEvent","orders/OrderCreatedEvent/legacy"],
                             "etag":"\\"sha256:m\\"","path":"/api/graphs/messages/orders%2FOrderCreatedEvent"}]}
                """, "\"sha256:index\"");

        assertThat(upstream.index(ENVIRONMENT, MESSAGE_REACTIONS, null).orElseThrow().value())
                .extracting(ReactionGraphRef::name, ReactionGraphRef::variant)
                .containsExactly(
                        tuple("orders/OrderCreatedEvent", ""),
                        tuple("orders/OrderCreatedEvent", "legacy"));
    }

    /**
     * A landscape nothing has been observed in lists nothing, and that is an answer rather than a failure -
     * it is also what an observer that has not built its first snapshot yet says.
     */
    @Test
    void systemIndex_whenNothingHasBeenObserved_thenTheIndexIsEmptyAndNotAFailure() {
        stub(SYSTEM_INDEX, 200, "{\"entries\":[]}", "\"sha256:empty\"");

        assertThat(upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, null).orElseThrow().value()).isEmpty();
    }

    /**
     * <b>A {@code 404} is an observer too old to be imported</b>, and it says so. Read as an index that lists
     * nothing it would instead delete every graph of the environment - and it cannot be that, because the
     * release that serves the indexes is the release that requires the resource server this client got its
     * token from.
     */
    @Test
    void systemIndex_whenTheObserverServesNoIndex_thenItSaysWhatToDoAboutIt() {
        stub(SYSTEM_INDEX, 404, "");

        assertThatThrownBy(() -> upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, null))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("serves no SYSTEM_REACTIONS index")
                .hasMessageContaining("jeap.doc.reactions.environments");
    }

    /**
     * An answer with no body at all is not an empty index either. Every zero-length {@code 200} arrives as a
     * null body - a proxy, a truncated answer - and what a run does with an index that lists nothing is
     * remove things.
     */
    @Test
    void systemIndex_whenTheAnswerCarriesNoEntries_thenWhatIsStoredIsKept() {
        stub(SYSTEM_INDEX, 200, "");

        assertThatThrownBy(() -> upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, null))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("without a list of entries");
    }

    @Test
    void systemIndex_whenTheTokenIsNotAllowedToRead_thenTheMessageNamesTheRole() {
        stub(SYSTEM_INDEX, 403, "");

        assertThatThrownBy(() -> upstream.index(ENVIRONMENT, SYSTEM_REACTIONS, null))
                .isInstanceOf(ArchitectureModelUnavailableException.class)
                .hasMessageContaining("client-registration")
                .hasMessageContaining("_@reactions_#read");
    }

    /**
     * What is stored is the graph and the fingerprint the observer computed over it - not the envelope around
     * them, which is how it answers rather than part of the graph.
     */
    @Test
    void graph_thenTheGraphItselfIsStored() {
        stub("/api/graphs/systems/orders", 200,
                "{\"graph\":" + GRAPH + ",\"fingerprint\":\"abc123\"}", "\"sha256:abc123\"");

        GraphFetch fetch = upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:abc123\""), null);

        assertThat(fetch).isInstanceOf(GraphFetch.Stored.class);
        ReactionGraph graph = ((GraphFetch.Stored) fetch).graph();
        assertThat(new String(graph.data(), StandardCharsets.UTF_8)).isEqualTo(GRAPH);
        assertThat(graph.fingerprint()).isEqualTo("abc123");
        assertThat(graph.etag()).isEqualTo("\"sha256:abc123\"");
    }

    @Test
    void graph_whenItHasNotMoved_thenTheStoredCopyIsConfirmed() {
        observer.stubFor(get(urlEqualTo("/api/graphs/systems/orders"))
                .withHeader("If-None-Match", equalTo("\"sha256:abc123\""))
                .willReturn(aResponse().withStatus(304)));

        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:abc123\""), "\"sha256:abc123\""))
                .isInstanceOf(GraphFetch.Unchanged.class);
    }

    /**
     * The index listed it and the resource no longer has it: the observer refreshed between the two requests,
     * or its statistics window moved past the last reaction of that system. One graph is skipped and the run
     * goes on.
     */
    @Test
    void graph_whenItWentAwayBetweenTheIndexAndTheFetch_thenItIsSkipped() {
        stub("/api/graphs/systems/orders", 404, "");

        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:a\""), null))
                .isEqualTo(GraphFetch.skipped("it went away between the index and the fetch"));
    }

    /** One request, every variant - and the reference decides which of them is being stored. */
    @Test
    void messageGraph_thenTheVariantOfTheReferenceIsPickedOutOfTheAnswer() {
        stub("/api/graphs/messages/OrderCreatedEvent", 200, """
                {"OrderCreatedEvent":{"graph":{"nodes":["default"],"edges":[]},"fingerprint":"one"},
                 "OrderCreatedEvent/legacy":{"graph":{"nodes":["legacy"],"edges":[]},"fingerprint":"two"}}
                """, "\"sha256:m\"");

        GraphFetch fetch = upstream.content(ENVIRONMENT, refOf(MESSAGE_REACTIONS, "OrderCreatedEvent",
                "legacy", "/api/graphs/messages/OrderCreatedEvent", "\"sha256:m\""), null);

        ReactionGraph graph = ((GraphFetch.Stored) fetch).graph();
        assertThat(new String(graph.data(), StandardCharsets.UTF_8)).contains("legacy");
        assertThat(graph.fingerprint()).isEqualTo("two");
        // The tag of the whole answer, which is what the index listed and what the next run sends back.
        assertThat(graph.etag()).isEqualTo("\"sha256:m\"");
    }

    @Test
    void messageGraph_whenTheVariantIsNoLongerAnswered_thenItIsSkipped() {
        stub("/api/graphs/messages/OrderCreatedEvent", 200,
                "{\"OrderCreatedEvent\":{\"graph\":" + GRAPH + ",\"fingerprint\":\"one\"}}", "\"sha256:m\"");

        assertThat(upstream.content(ENVIRONMENT, refOf(MESSAGE_REACTIONS, "OrderCreatedEvent", "legacy",
                "/api/graphs/messages/OrderCreatedEvent", "\"sha256:m\""), null))
                .isEqualTo(GraphFetch.skipped("it carried no graph"));
    }

    /**
     * The answer is keyed by the observer's spelling, and the reference arrives carrying the model's: the
     * import resolves every name before a graph is fetched. Keyed by the resolved name instead, a message
     * type the two spell differently would lose every variant it has.
     */
    @Test
    void messageGraph_whenTheModelSpellsTheTypeDifferently_thenTheAnswerIsStillAddressed() {
        stub("/api/graphs/messages/ordercreatedevent", 200, """
                {"ordercreatedevent/legacy":{"graph":{"nodes":["legacy"],"edges":[]},"fingerprint":"two"}}
                """, "\"sha256:m\"");

        GraphFetch fetch = upstream.content(ENVIRONMENT, resolvedRef(MESSAGE_REACTIONS, "OrderCreatedEvent",
                "ordercreatedevent", "legacy", "/api/graphs/messages/ordercreatedevent", "\"sha256:m\""),
                null);

        ReactionGraph graph = ((GraphFetch.Stored) fetch).graph();
        assertThat(new String(graph.data(), StandardCharsets.UTF_8)).contains("legacy");
        assertThat(graph.fingerprint()).isEqualTo("two");
    }

    /** Never read whole: the cap is what keeps a graph nobody sized from being this service's problem. */
    @Test
    void graph_whenItIsLargerThanTheCap_thenItIsNotStored() {
        properties.setMaxGraphSize(DataSize.ofBytes(8));
        stub("/api/graphs/systems/orders", 200,
                "{\"graph\":" + GRAPH + ",\"fingerprint\":\"abc123\"}", "\"sha256:abc123\"");

        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:a\""), null))
                .isInstanceOf(GraphFetch.Skipped.class);
    }

    /** What is stored is addressed by its tag, so a graph that arrives without one is left where it is. */
    @Test
    void graph_whenItArrivesWithoutAnEntityTag_thenItIsSkipped() {
        stub("/api/graphs/systems/orders", 200,
                "{\"graph\":" + GRAPH + ",\"fingerprint\":\"abc123\"}");

        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:a\""), null))
                .isEqualTo(GraphFetch.skipped("it arrived without an entity tag"));
    }

    /** One unreadable payload costs one graph, not the environment. */
    @Test
    void graph_whenTheAnswerIsNotJson_thenItIsSkipped() {
        stub("/api/graphs/systems/orders", 200, "<html>not a graph</html>", "\"sha256:a\"");

        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "/api/graphs/systems/orders", "\"sha256:a\""), null))
                .isEqualTo(GraphFetch.skipped("it is not readable as JSON"));
    }

    /**
     * A path that leaves the observer's origin is not fetched: it would send this service's token to whatever
     * host the payload named.
     */
    @Test
    void graph_whenTheIndexPointsSomewhereElse_thenItIsNotFetched() {
        assertThat(upstream.content(ENVIRONMENT, refOf(SYSTEM_REACTIONS, "orders", "",
                "https://elsewhere.example.com/api/graphs/systems/orders", "\"sha256:a\""), null))
                .isEqualTo(GraphFetch.skipped("its URL cannot be fetched"));
    }

    private static ReactionGraphRef refOf(ArchitectureImportKind kind, String name, String variant,
                                          String path, String etag) {
        return resolvedRef(kind, name, name, variant, path, etag);
    }

    /** A reference as the import hands it over: the model's name, and the observer's beside it. */
    private static ReactionGraphRef resolvedRef(ArchitectureImportKind kind, String name, String upstreamName,
                                                String variant, String path, String etag) {
        return new ReactionGraphRef(ENVIRONMENT, kind, name, upstreamName, variant, null, etag, path, null,
                true);
    }

    private void stub(String path, int status, String body) {
        stub(path, status, body, null);
    }

    private void stub(String path, int status, String body, String etag) {
        var answer = aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
        if (etag != null) {
            answer = answer.withHeader("ETag", etag);
        }
        observer.stubFor(get(urlEqualTo(path)).willReturn(answer));
    }
}
