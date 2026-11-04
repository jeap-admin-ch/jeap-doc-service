package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.*;
import ch.admin.bit.jeap.doc.domain.architecture.*;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
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
        var graph = new ObservedReactions(List.of(new ObservedMessage(new Identity("MESSAGE", "123"),
                label, "priority")), List.of(), List.of(), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph,
                Instant.parse("2026-10-29T08:00:00Z"), List.of(), List.of(new PublishedReactions.MessageOwnership(label, "orders")), true)));
        var snapshots = files.write(site, SitePart.wholeSiteOf(site), directory);
        assertThat(snapshots.get("prod")).get().satisfies(snapshot -> assertThat(snapshot.observations()).isSameAs(graph));
        verify(networks).read("prod");
        verify(networks).read("dev");
        verifyNoMoreInteractions(networks);
        var json = JsonMapper.builder().build();
        var published = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(published.path("site").asString()).isEqualTo("handbook");
        assertThat(published.path("environment").asString()).isEqualTo("prod");
        assertThat(published.path("identityScheme").asString()).isEqualTo("observer-v1");
        assertThat(published.path("messageOwnership").get(0).path("message").asString()).isEqualTo(label);
        assertThat(published.path("messageOwnership").get(0).path("system").asString()).isEqualTo("orders");
        assertThat(published.path("graph").path("nodes").get(0).path("interfaceDetails").path("message").path("messageType").asString()).isEqualTo(label);
        assertThat(published.path("snapshot").asString()).hasSize(64);
        var unavailable = json.readTree(Files.readAllBytes(directory.resolve("static/dev/reaction-graph.json")));
        assertThat(unavailable.path("environment").asString()).isEqualTo("dev");
        assertThat(unavailable.path("available").asBoolean()).isFalse();
        assertThat(unavailable.has("graph")).isFalse();
        byte[] first = Files.readAllBytes(directory.resolve("static/reaction-graph.json"));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        assertThat(Files.readAllBytes(directory.resolve("static/reaction-graph.json"))).isEqualTo(first);
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph,
                Instant.parse("2026-10-29T08:00:00Z"), List.of(), List.of(new PublishedReactions.MessageOwnership(label, "shipping")), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var reassigned = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(reassigned.path("snapshot")).isNotEqualTo(published.path("snapshot"));
    }

    @Test
    void v2MetadataAndHistoryReachTheBrowserPayloadAndAffectItsDigest() throws Exception {
        var identity = new ObservedReactions.Identity("REACTION", "R1");
        var prior = new ObservedReactions.Identity("REACTION", "R0");
        var reaction = new ObservedReaction(identity, "orders", "gateway", java.math.BigDecimal.ZERO,
                ReactionStatus.ACTIVE, 2, new java.math.BigDecimal("0.25"), null);
        var predecessor = new ObservedReaction(prior, "orders", "gateway", java.math.BigDecimal.ONE,
                ReactionStatus.SUPERSEDED, 1, java.math.BigDecimal.ONE, null);
        var http = new ObservedHttpOperation(new Identity("INTERFACE", "I1"), "http_request", "catalog.GET./items",
                "catalog", "GET", "/items");
        var graph = new ObservedReactions("observer-v2", List.of(http), List.of(reaction, predecessor), List.of(),
                List.of(new ObservedAction(identity, http.identity())), List.of(new ObservedPredecessor(identity, prior)));
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph, Instant.EPOCH, List.of(), List.of(), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var json = JsonMapper.builder().build();
        var published = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(published.path("identityScheme").asString()).isEqualTo("observer-v2");
        assertThat(published.at("/graph/nodes/0/interfaceDetails/http/path").asString()).isEqualTo("/items");
        assertThat(published.at("/graph/nodes/1/reaction/unidentifiedActionsRatio").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(published.at("/graph/nodes/2/reaction/status").asString()).isEqualTo("SUPERSEDED");
        assertThat(published.at("/graph/edges/1/edgeType").asString()).isEqualTo("PREDECESSOR");
        var changed = new ObservedReactions("observer-v2", graph.interfaces(), graph.reactions(), graph.triggers(), graph.actions(), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(changed, Instant.EPOCH, List.of(), List.of(), true)));
        files.write(site, SitePart.wholeSiteOf(site), directory);
        var next = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(next.path("snapshot")).isNotEqualTo(published.path("snapshot"));
    }

    @Test
    void systemPartsDoNotPublishTheirOwnCopy() throws Exception {
        files.write(site, new SitePart(PartKey.of(site.id(), "system-orders"), "orders", "systems/orders", true,
                List.of("prod", "dev"), List.of("/systems/orders/", "/dev/systems/orders/")), directory);
        verify(networks).read("prod");
        verify(networks).read("dev");
        verifyNoMoreInteractions(networks);
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
