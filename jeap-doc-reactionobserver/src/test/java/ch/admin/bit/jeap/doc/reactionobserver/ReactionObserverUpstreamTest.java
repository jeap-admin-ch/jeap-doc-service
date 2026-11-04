package ch.admin.bit.jeap.doc.reactionobserver;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ReactionGraph;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Complete snapshot requests against a real HTTP server. */
class ReactionObserverUpstreamTest {

    private static final String ENVIRONMENT = "dev";

    private WireMockServer observer;
    private ReactionObserverUpstream upstream;
    private final ReactionObserverProperties properties = new ReactionObserverProperties();

    @BeforeEach
    void setUp() {
        // Gzip off: wiremock appends "--gzip" to an entity tag it compresses, and these tests are about the
        // tag arriving verbatim. No retries: a test that asserts a failure should not wait one out.
        observer = new WireMockServer(options().dynamicPort().gzipDisabled(true));
        observer.start();
        properties.getEnvironments().put(ENVIRONMENT, new ReactionObserverProperties.Environment());
        upstream = new ReactionObserverUpstream(
                TestClients.of(ENVIRONMENT, observer.baseUrl(), client -> client.setRetries(0)),
                properties);
    }

    @AfterEach
    void tearDown() {
        observer.stop();
    }

    @Test
    void fullGraphNeverReusesATagFromAnotherApiVersion() {
        stub("/api/graphs", 200, fullGraphPayload("0"), "\"v1-tag\"");
        GraphFetch.Stored stored = (GraphFetch.Stored) upstream.fullGraph(ENVIRONMENT, "\"v2-tag\"", "observer-v2");
        observer.verify(getRequestedFor(urlEqualTo("/api/graphs")).withoutHeader("If-None-Match"));
        assertThat(stored.graph().sourceFormat()).isEqualTo("observer-v1");
        var content = new ReactionObserverGraphContent(tools.jackson.databind.json.JsonMapper.builder().build());
        assertThat(content.readFull(stored.graph().data()).identityScheme()).isEqualTo("observer-v1");
        stub("/api/graphs", 304, "", null);
        assertThat(upstream.fullGraph(ENVIRONMENT, "\"v2-tag\"", "observer-v2")).isInstanceOf(GraphFetch.Skipped.class);
    }

    @Test
    void isConfiguredFor_thenOnlyTheEnvironmentThatNamesAnObserver() {
        assertThat(upstream.isConfiguredFor(ENVIRONMENT)).isTrue();
        assertThat(upstream.isConfiguredFor("prod")).isFalse();
    }

    @Test
    void fullGraphPreservesCrossComponentEdgesVariantsAndZeroMedians() {
        stub("/api/graphs", 200, fullGraphPayload("0"), "\"full-tag\"");
        GraphFetch fetched = upstream.fullGraph(ENVIRONMENT, null, null);
        assertThat(fetched).isInstanceOf(GraphFetch.Stored.class);
        ReactionGraph graph = ((GraphFetch.Stored) fetched).graph();
        var mapped = new ReactionObserverGraphContent(tools.jackson.databind.json.JsonMapper.builder().build())
                .readFull(graph.data());
        assertThat(mapped.messages()).singleElement().satisfies(message -> {
            assertThat(message.identity().key()).isEqualTo("9007199254740993");
            assertThat(message.variant()).isEqualTo("priority");
        });
        assertThat(mapped.reactions()).hasSize(2);
        assertThat(mapped.reactions().getFirst().median()).isEqualByComparingTo("0");
        assertThat(mapped.triggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.interfaceId()).isEqualTo(mapped.messages().getFirst().identity());
            assertThat(trigger.reactionId()).isEqualTo(mapped.reactions().getLast().identity());
        });
        assertThat(mapped.actions()).singleElement().satisfies(action -> {
            assertThat(action.reactionId()).isEqualTo(mapped.reactions().getFirst().identity());
            assertThat(action.interfaceId()).isEqualTo(mapped.messages().getFirst().identity());
        });
        assertThat(graph.etag()).isEqualTo("\"full-tag\"");
        stub("/api/graphs", 304, "");
        assertThat(upstream.fullGraph(ENVIRONMENT, graph.etag(), graph.sourceFormat())).isInstanceOf(GraphFetch.Unchanged.class);
        observer.verify(getRequestedFor(urlEqualTo("/api/graphs"))
                .withHeader("If-None-Match", equalTo("\"full-tag\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/observer", "/observer/", "/observer////"})
    void fullGraphRetainsTheObserversContextPath(String contextPath) {
        upstream = new ReactionObserverUpstream(
                TestClients.of(ENVIRONMENT, observer.baseUrl() + contextPath, client -> client.setRetries(0)), properties);
        stub("/observer/api/graphs", 200, fullGraphPayload("1"), "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Stored.class);
        observer.verify(getRequestedFor(urlEqualTo("/observer/api/graphs")));
    }

    @Test
    void invalidFullGraphIsNotOfferedForStorage() {
        for (String median : List.of("null", "-1", "\"unknown\"")) {
            stub("/api/graphs", 200, fullGraphPayload(median), "tag");
            assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        }
        stub("/api/graphs", 200, fullGraphPayload("1").replace("targetReactionId\":3", "targetReactionId\":4"), "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        stub("/api/graphs", 200, "{\"graph\":{}}", "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
    }

    @Test
    void distinctTriggersOfOneReactionAreNotOfferedForStorage() {
        stub("/api/graphs", 200, """
                {"graph":{"nodes":[
                  {"nodeType":"MESSAGE","id":1,"messageType":"A"},
                  {"nodeType":"MESSAGE","id":2,"messageType":"B"},
                  {"nodeType":"REACTION","id":3,"component":"service","median":1}],
                 "edges":[
                  {"edgeType":"TRIGGER","sourceId":1,"targetReactionId":3},
                  {"edgeType":"TRIGGER","sourceId":2,"targetReactionId":3}]}}
                """, "new-tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, "old-tag", "observer-v1"))
                .isEqualTo(GraphFetch.skipped("A reaction cannot have multiple triggers"));
    }

    @Test
    void unsuccessfulResponsesAreNeverOfferedForStorage() {
        stub("/api/graphs", 200, fullGraphPayload("1"), null);
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        stub("/api/graphs", 200, "not json", "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        stub("/api/graphs", 200, "{}", "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        observer.stubFor(get(urlEqualTo("/api/graphs")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/elsewhere")));
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
        observer.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
        properties.setMaxGraphSize(DataSize.ofBytes(10));
        stub("/api/graphs", 200, fullGraphPayload("1"), "tag");
        assertThat(upstream.fullGraph(ENVIRONMENT, null, null)).isInstanceOf(GraphFetch.Skipped.class);
    }

    @Test
    void failedRequestsNameTheEnvironmentAndStatus() {
        for (int status : List.of(401, 403, 404, 500)) {
            stub("/api/graphs", status, "", null);
            assertThatThrownBy(() -> upstream.fullGraph(ENVIRONMENT, null, null))
                    .isInstanceOf(ArchitectureModelUnavailableException.class)
                    .hasMessageContaining(ENVIRONMENT).hasMessageContaining(Integer.toString(status));
        }
    }

    private static String fullGraphPayload(String median) {
        return """
                {"fingerprint":"full","graph":{"nodes":[
                  {"nodeType":"MESSAGE","id":9007199254740993,"messageType":"OrderCreatedEvent","variant":"priority"},
                  {"nodeType":"REACTION","id":2,"component":"orders","median":%s},
                  {"nodeType":"REACTION","id":3,"component":"shipping","median":12}],
                 "edges":[{"edgeType":"ACTION","sourceReactionId":2,"targetId":9007199254740993},
                          {"edgeType":"TRIGGER","sourceId":9007199254740993,"targetReactionId":3}]}}
                """.formatted(median);
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
