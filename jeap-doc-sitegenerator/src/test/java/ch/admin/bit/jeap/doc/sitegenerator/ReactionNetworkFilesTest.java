package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.*;
import ch.admin.bit.jeap.doc.domain.architecture.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReactionNetworkFilesTest {
    @TempDir
    Path directory;
    private final ReactionNetworkPublication networks = mock(ReactionNetworkPublication.class);
    private final ReactionNetworkFiles files = new ReactionNetworkFiles(networks);
    private final Site site = new Site("handbook", "Documentation", null, null, null, "jeap", List.of(
            new SiteEnvironment("prod", "PROD", "Production", 1, true, false),
            new SiteEnvironment("dev", "DEV", "Development", 2, false, true)), true, true);

    @Test
    void publishesOneSelfContainedSnapshotPerEnvironmentAtItsSiteRelativePath() throws Exception {
        String label = "<script>alert('x')</script>";
        var graph = new ReactionNetwork(List.of(new ReactionNetwork.Node(new ReactionNetwork.Identity("MESSAGE", "123"),
                label, "priority", null, null)), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactionNetwork(graph,
                Instant.parse("2026-10-29T08:00:00Z"), List.of(), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var json = JsonMapper.builder().build();
        var published = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(published.path("site").asString()).isEqualTo("handbook");
        assertThat(published.path("environment").asString()).isEqualTo("prod");
        assertThat(published.path("identityScheme").asString()).isEqualTo("observer-v1");
        assertThat(published.path("graph").path("nodes").get(0).path("interfaceDetails").path("message").path("messageType").asString()).isEqualTo(label);
        assertThat(published.path("snapshot").asString()).hasSize(64);
        var unavailable = json.readTree(Files.readAllBytes(directory.resolve("static/dev/reaction-graph.json")));
        assertThat(unavailable.path("environment").asString()).isEqualTo("dev");
        assertThat(unavailable.path("available").asBoolean()).isFalse();
        assertThat(unavailable.has("graph")).isFalse();
        byte[] first = Files.readAllBytes(directory.resolve("static/reaction-graph.json"));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        assertThat(Files.readAllBytes(directory.resolve("static/reaction-graph.json"))).isEqualTo(first);
    }

    @Test
    void v2MetadataAndHistoryReachTheBrowserPayloadAndAffectItsDigest() throws Exception {
        var identity = new ReactionNetwork.Identity("REACTION", "R1");
        var prior = new ReactionNetwork.Identity("REACTION", "R0");
        var reaction = new ReactionNetwork.Node(identity, "REACTION", null,
                new ReactionNetwork.ReactionDetails("orders", "gateway", java.math.BigDecimal.ZERO,
                        ReactionNetwork.ReactionStatus.ACTIVE, 2, new java.math.BigDecimal("0.25"), null));
        var predecessor = new ReactionNetwork.Node(prior, "REACTION", null,
                new ReactionNetwork.ReactionDetails("orders", "gateway", java.math.BigDecimal.ONE,
                        ReactionNetwork.ReactionStatus.SUPERSEDED, 1, java.math.BigDecimal.ONE, null));
        var http = new ReactionNetwork.Node(new ReactionNetwork.Identity("INTERFACE", "I1"), "HTTP_OPERATION",
                new ReactionNetwork.InterfaceDetails("http_request", "catalog.GET./items", null,
                        new ReactionNetwork.HttpOperation("catalog", "GET", "/items")), null);
        var graph = new ReactionNetwork("observer-v2", List.of(reaction, predecessor, http), List.of(
                new ReactionNetwork.Edge("PREDECESSOR", identity, prior), new ReactionNetwork.Edge("ACTION", identity, http.identity())));
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactionNetwork(graph, Instant.EPOCH, List.of(), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var json = JsonMapper.builder().build();
        var published = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(published.path("identityScheme").asString()).isEqualTo("observer-v2");
        assertThat(published.path("graph")).isEqualTo(json.readTree(json.writeValueAsBytes(graph)));
        var changed = new ReactionNetwork("observer-v2", graph.nodes(), List.of(graph.edges().getLast()));
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactionNetwork(changed, Instant.EPOCH, List.of(), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var next = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(next.path("snapshot")).isNotEqualTo(published.path("snapshot"));
    }

    @Test
    void systemPartsDoNotPublishTheirOwnCopy() throws Exception {
        files.write(site, new SitePart(PartKey.of(site.id(), "system-orders"), "orders", "systems/orders", true,
                List.of("prod", "dev"), List.of("/systems/orders/", "/dev/systems/orders/")), directory);
        verifyNoInteractions(networks);
        assertThat(directory.resolve("static")).doesNotExist();
    }

    @Test
    void processingFailureFailsPreparationBeforeItCanReplaceAPublication() {
        when(networks.read("prod")).thenThrow(new IllegalArgumentException("Corrupt stored graph"));
        SitePart part = SitePart.wholeSiteOf(site);
        assertThatThrownBy(() -> files.write(site, part, directory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("static/reaction-graph.json")).doesNotExist();
    }
}
