package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.*;
import ch.admin.bit.jeap.doc.domain.architecture.*;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import ch.admin.bit.jeap.doc.domain.template.ReactionDocumentationLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    private Map<String, Optional<PublishedReactions>> publish(SitePart part) throws Exception {
        var snapshots = files.read(site, part);
        files.write(site, part, directory, snapshots, Map.of());
        return snapshots;
    }

    @Test
    void publishesOneSelfContainedSnapshotPerEnvironmentAtItsSiteRelativePath() throws Exception {
        String label = "<script>alert('x')</script>";
        var graph = new ObservedReactions(List.of(new ObservedMessage(new Identity("MESSAGE", "123"),
                label, "priority")), List.of(), List.of(), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph,
                Instant.parse("2026-10-29T08:00:00Z"), List.of(), List.of(new PublishedReactions.MessageOwnership(label, "orders")), true)));
        var snapshots = publish(SitePart.wholeSiteOf(site));
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
        var explorer = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-explorer.json")));
        assertThat(explorer).isEqualTo(json.readTree("""
                {"formatVersion":1,"site":"handbook","environment":"prod","available":true}
                """));
        assertThat(directory.resolve("static/dev/reaction-explorer.json")).doesNotExist();
        var unavailable = json.readTree(Files.readAllBytes(directory.resolve("static/dev/reaction-graph.json")));
        assertThat(unavailable.path("environment").asString()).isEqualTo("dev");
        assertThat(unavailable.path("available").asBoolean()).isFalse();
        assertThat(unavailable.has("graph")).isFalse();
        byte[] first = Files.readAllBytes(directory.resolve("static/reaction-graph.json"));
        publish(SitePart.wholeSiteOf(site));
        assertThat(Files.readAllBytes(directory.resolve("static/reaction-graph.json"))).isEqualTo(first);
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph,
                Instant.parse("2026-10-29T08:00:00Z"), List.of(), List.of(new PublishedReactions.MessageOwnership(label, "shipping")), true)));
        publish(SitePart.wholeSiteOf(site));
        var reassigned = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(reassigned.path("snapshot")).isNotEqualTo(published.path("snapshot"));
        when(networks.read("prod")).thenReturn(Optional.empty());
        publish(SitePart.wholeSiteOf(site));
        assertThat(directory.resolve("static/reaction-explorer.json")).doesNotExist();
    }

    @Test
    void v2MetadataAndHistoryReachTheBrowserPayloadAndAffectItsDigest() throws Exception {
        var identity = new ObservedReactions.Identity("REACTION", "R1");
        var prior = new ObservedReactions.Identity("REACTION", "R0");
        var reaction = new ObservedReaction(identity, "orders", "gateway", BigDecimal.ZERO,
                ReactionStatus.ACTIVE, 2, new BigDecimal("0.25"), null);
        var predecessor = new ObservedReaction(prior, "orders", "gateway", BigDecimal.ONE,
                ReactionStatus.SUPERSEDED, 1, BigDecimal.ONE, null);
        var http = new ObservedHttpOperation(new Identity("INTERFACE", "I1"), "http_request", "catalog.GET./items",
                "catalog", "GET", "/items");
        var graph = new ObservedReactions("observer-v2", List.of(http), List.of(reaction, predecessor), List.of(),
                List.of(new ObservedAction(identity, http.identity())), List.of(new ObservedPredecessor(identity, prior)));
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph, Instant.EPOCH, List.of(), List.of(), true)));
        publish(SitePart.wholeSiteOf(site));
        var json = JsonMapper.builder().build();
        var published = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(published.path("identityScheme").asString()).isEqualTo("observer-v2");
        assertThat(published.at("/graph/nodes/0/interfaceDetails/http/path").asString()).isEqualTo("/items");
        assertThat(published.at("/graph/nodes/1/reaction/unidentifiedActionsRatio").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(published.at("/graph/nodes/2/reaction/status").asString()).isEqualTo("SUPERSEDED");
        assertThat(published.at("/graph/edges/1/edgeType").asString()).isEqualTo("PREDECESSOR");
        var changed = new ObservedReactions("observer-v2", graph.interfaces(), graph.reactions(), graph.triggers(), graph.actions(), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(changed, Instant.EPOCH, List.of(), List.of(), true)));
        publish(SitePart.wholeSiteOf(site));
        var next = json.readTree(Files.readAllBytes(directory.resolve("static/reaction-graph.json")));
        assertThat(next.path("snapshot")).isNotEqualTo(published.path("snapshot"));
    }

    @Test
    void documentationDestinationsArePublishedAndParticipateInTheSnapshotDigest() throws Exception {
        var graph = new ObservedReactions(List.of(), List.of(), List.of(), List.of());
        when(networks.read("prod")).thenReturn(Optional.of(new PublishedReactions(graph, Instant.EPOCH,
                List.of(), List.of(), true)));
        var part = SitePart.wholeSiteOf(site);
        var snapshots = files.read(site, part);
        var withoutLinks = new EnvironmentModel(List.of(), 0, 0, 0, Instant.EPOCH, null);
        assertThat(withoutLinks.reactionLinks()).isEqualTo(ReactionDocumentationLinks.empty());
        files.write(site, part, directory, snapshots, Map.of("prod", withoutLinks));
        var json = JsonMapper.builder().build();
        var path = directory.resolve("static/reaction-graph.json");
        var before = json.readTree(Files.readAllBytes(path));
        assertThat(before.path("documentation").isObject()).isTrue();
        assertThat(before.at("/documentation/componentLinks").size()).isZero();
        var links = new ReactionDocumentationLinks(List.of(
                new ReactionDocumentationLinks.ComponentLink(
                        "orders", "intake", "/site/handbook/systems/orders/intake/")), List.of());
        var model = new EnvironmentModel(List.of(), 0, 0, 0, Instant.EPOCH, links);
        files.write(site, part, directory, snapshots, Map.of("prod", model));
        var after = json.readTree(Files.readAllBytes(path));
        assertThat(after.at("/documentation/componentLinks/0/href").asString())
                .isEqualTo("/site/handbook/systems/orders/intake/");
        assertThat(after.path("snapshot")).isNotEqualTo(before.path("snapshot"));
        verify(networks).read("prod");
        verify(networks).read("dev");
        verifyNoMoreInteractions(networks);
    }

    @Test
    void systemPartsDoNotPublishTheirOwnCopy() throws Exception {
        publish(new SitePart(PartKey.of(site.id(), "system-orders"), "orders", "systems/orders", true,
                List.of("prod", "dev"), List.of("/systems/orders/", "/dev/systems/orders/")));
        verify(networks).read("prod");
        verify(networks).read("dev");
        verifyNoMoreInteractions(networks);
        assertThat(directory.resolve("static")).doesNotExist();
    }

    @Test
    void processingFailureFailsPreparationBeforeItCanReplaceAPublication() {
        when(networks.read("prod")).thenThrow(new IllegalArgumentException("Corrupt stored graph"));
        SitePart part = SitePart.wholeSiteOf(site);
        assertThatThrownBy(() -> publish(part))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("static/reaction-graph.json")).doesNotExist();
    }
}
