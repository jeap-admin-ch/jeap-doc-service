package ch.admin.bit.jeap.doc.domain.architecture.imports;

import ch.admin.bit.jeap.doc.domain.BuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.PartKey;
import ch.admin.bit.jeap.doc.domain.SharedAssets;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.port.DocumentationBuildRepository;
import ch.admin.bit.jeap.doc.domain.port.GraphFetch;
import ch.admin.bit.jeap.doc.domain.port.PartPublication;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import ch.admin.bit.jeap.doc.domain.port.SitePublicationStorage;
import ch.admin.bit.jeap.doc.sitegenerator.ReactionNetworkFiles;
import ch.admin.bit.jeap.doc.web.DocServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the stored snapshot, serialization, object publication and site routing together. */
class ReactionNetworkPublicationIT extends DocServiceIntegrationTestBase {
    private static final String SITE = "reaction-data";
    private static final String ENVIRONMENT = "reaction-main";
    private static final String OTHER_ENVIRONMENT = "reaction-next";

    @Autowired private FullReactionImportStep importer;
    @Autowired private ReactionNetworkFiles files;
    @Autowired private DocumentationSites sites;
    @Autowired private DocumentationBuildRepository builds;
    @Autowired private SitePublicationStorage storage;
    @Autowired private MockMvc mvc;
    @MockitoBean private ReactionGraphUpstream upstream;

    @DynamicPropertySource
    static void configureSite(DynamicPropertyRegistry registry) {
        String prefix = "jeap.doc.sites." + SITE;
        registry.add(prefix + ".title", () -> "Reaction documentation");
        registry.add(prefix + ".environments[0].id", () -> ENVIRONMENT);
        registry.add(prefix + ".environments[0].order", () -> 1);
        registry.add(prefix + ".environments[0].main", () -> true);
        registry.add(prefix + ".environments[0].latest", () -> true);
        registry.add(prefix + ".environments[1].id", () -> OTHER_ENVIRONMENT);
        registry.add(prefix + ".environments[1].order", () -> 2);
    }

    @Test
    void failedUpdateKeepsTheSnapshotAndNonDefaultSiteAndEnvironmentStayIsolated(@TempDir Path workspace) throws Exception {
        when(upstream.isConfiguredFor(ENVIRONMENT)).thenReturn(true);
        when(upstream.isConfiguredFor(OTHER_ENVIRONMENT)).thenReturn(true);
        byte[] data = """
                {"sourceFormat":"observer-v1","graph":{"nodes":[{"nodeType":"MESSAGE","id":1,"messageType":"OrderCreatedEvent","variant":"priority"},
                          {"nodeType":"REACTION","id":2,"component":"orders-intake","median":0}],
                 "edges":[{"edgeType":"TRIGGER","sourceId":1,"targetReactionId":2}]}}
                """.getBytes(StandardCharsets.UTF_8);
        when(upstream.fullGraph(eq(ENVIRONMENT), any(), any())).thenReturn(GraphFetch.stored(new ReactionGraph("first-tag", "first", data, 2, "observer-v1")));
        assertThat(importer.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);
        publish(workspace.resolve("first"));
        String first = mvc.perform(get("/site/" + SITE + "/reaction-graph.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.site").value(SITE))
                .andExpect(jsonPath("$.environment").value(ENVIRONMENT))
                .andExpect(jsonPath("$.identityScheme").value("observer-v1"))
                .andExpect(jsonPath("$.graph.nodes[1].reaction.median").value(0))
                .andExpect(jsonPath("$.graph.nodes[0].identity.key").value("1"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/site/" + SITE + "/" + OTHER_ENVIRONMENT + "/reaction-graph.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value(OTHER_ENVIRONMENT))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.graph").doesNotExist());
        when(upstream.fullGraph(ENVIRONMENT, "first-tag", "observer-v1")).thenReturn(GraphFetch.skipped("Invalid replacement"));
        assertThat(importer.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);
        publish(workspace.resolve("after-failure"));
        mvc.perform(get("/site/" + SITE + "/reaction-graph.json"))
                .andExpect(status().isOk()).andExpect(content().string(first));
        // A successful switch needs neither a database migration nor a new publication model.
        byte[] v2 = """
                {"sourceFormat":"observer-v2","graph":{"nodes":[
                  {"nodeType":"HTTP_OPERATION","id":"I1","interfaceType":"http_request","fqn":"catalog GET /items","provider":"catalog","method":"GET","path":"/items"},
                  {"nodeType":"REACTION","id":"R1","system":"orders","component":"intake","median":0,"status":"ACTIVE","reactionModelVersion":3,"unidentifiedActionsRatio":0.25},
                  {"nodeType":"REACTION","id":"R0","system":"orders","component":"intake","median":1,"status":"SUPERSEDED","reactionModelVersion":2,"unidentifiedActionsRatio":1}],
                  "edges":[{"edgeType":"ACTION","sourceId":"R1","targetId":"I1"},{"edgeType":"PREDECESSOR","sourceId":"R1","targetId":"R0"}]}}
                """.getBytes(StandardCharsets.UTF_8);
        when(upstream.fullGraph(ENVIRONMENT, "first-tag", "observer-v1"))
                .thenReturn(GraphFetch.stored(new ReactionGraph("v2-tag", "v2", v2, 3, "observer-v2")));
        assertThat(importer.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.REPLACED);
        publish(workspace.resolve("v2"));
        String second = mvc.perform(get("/site/" + SITE + "/reaction-graph.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityScheme").value("observer-v2"))
                .andExpect(jsonPath("$.graph.nodes[0].interfaceDetails.http.path").value("/items"))
                .andExpect(jsonPath("$.graph.nodes[1].reaction.reactionModelVersion").value(3))
                .andExpect(jsonPath("$.graph.nodes[1].reaction.unidentifiedActionsRatio").value(0.25))
                .andExpect(jsonPath("$.graph.nodes[2].reaction.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.graph.edges[1].edgeType").value("PREDECESSOR"))
                .andExpect(jsonPath("$.ownership[0].system").value("orders"))
                .andExpect(jsonPath("$.ownershipComplete").value(true))
                .andReturn().getResponse().getContentAsString();
        when(upstream.fullGraph(ENVIRONMENT, "v2-tag", "observer-v2")).thenReturn(GraphFetch.skipped("Invalid V2 update"));
        assertThat(importer.run(ENVIRONMENT, Deadline.none())).isEqualTo(ImportOutcome.FAILED);
        publish(workspace.resolve("v2-failure"));
        mvc.perform(get("/site/" + SITE + "/reaction-graph.json"))
                .andExpect(status().isOk()).andExpect(content().string(second));
    }

    private void publish(Path content) throws Exception {
        var site = sites.find(SITE).orElseThrow();
        files.write(site, SitePart.wholeSiteOf(site), content);
        Files.writeString(content.resolve("static/index.html"), "Documentation");
        var build = builds.start(PartKey.shellOf(SITE), BuildTrigger.IMPORT, "test", Instant.now(), null);
        String prefix = SITE + "/" + build.id();
        storage.publish(new PartPublication(prefix, SharedAssets.prefixOf(SITE)), content.resolve("static"));
        builds.succeeded(build.id(), prefix, 1, 100, 1, "test-digest", Instant.now());
    }
}
