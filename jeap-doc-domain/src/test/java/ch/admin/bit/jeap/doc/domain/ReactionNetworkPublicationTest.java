package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.*;

import ch.admin.bit.jeap.doc.domain.architecture.imports.*;
import ch.admin.bit.jeap.doc.domain.port.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReactionNetworkPublicationTest {
    private final ReactionGraphRepository graphs = mock(ReactionGraphRepository.class);
    private final ReactionGraphContent content = mock(ReactionGraphContent.class);
    private final ArchitectureModelSource models = mock(ArchitectureModelSource.class);
    private final ReactionGraphUpstream upstream = mock(ReactionGraphUpstream.class);
    private final ReactionNetworkPublication publication = new ReactionNetworkPublication(graphs, content, models, upstream);

    @Test
    void resolvesOnlyUnambiguousOwnersWithoutRemovingUnknownReactions() {
        byte[] data = {1};
        var node = new ReactionNetwork.Node(new ReactionNetwork.Identity("REACTION", "123"), null, null, "gateway", BigDecimal.ZERO);
        var network = new ReactionNetwork(List.of(node), List.of());
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(graphs.find("dev", ArchitectureImportKind.FULL_REACTIONS, "", "", "")).thenReturn(Optional.of(
                new StoredReactionGraph("dev", ArchitectureImportKind.FULL_REACTIONS, "", "", "", "", "tag", "fp", data, 1, Instant.EPOCH)));
        when(content.readFull(data)).thenReturn(network);
        DocumentedComponent component = mock(DocumentedComponent.class);
        when(component.name()).thenReturn("Gateway");
        DocumentedSystem orders = system("Orders", component);
        DocumentedSystem shipping = system("Shipping", component);
        when(models.read("dev")).thenReturn(new ArchitectureSnapshot(new ArchitectureModel(List.of(orders)), Instant.EPOCH));
        var unique = publication.read("dev").orElseThrow();
        assertThat(unique.ownershipComplete()).isTrue();
        assertThat(unique.ownership()).containsExactly(new PublishedReactionNetwork.Ownership(node.identity(), "Orders", "Gateway"));
        when(models.read("dev")).thenReturn(new ArchitectureSnapshot(new ArchitectureModel(List.of(orders, shipping)), Instant.EPOCH));
        var ambiguous = publication.read("dev").orElseThrow();
        assertThat(ambiguous.ownershipComplete()).isFalse();
        assertThat(ambiguous.ownership()).isEmpty();
        assertThat(ambiguous.graph()).isEqualTo(network);
        when(models.read("dev")).thenReturn(ArchitectureSnapshot.empty());
        assertThat(publication.read("dev").orElseThrow().ownershipComplete()).isFalse();
    }

    @Test
    void v2OwnershipComesFromTheObserverAndMissingOwnersStayUnknown() {
        byte[] data = {2};
        var owned = new ReactionNetwork.Node(new ReactionNetwork.Identity("REACTION", "R1"), "REACTION", null,
                new ReactionNetwork.ReactionDetails("observer-system", "gateway", BigDecimal.ZERO,
                        ReactionNetwork.ReactionStatus.ACTIVE, 2, BigDecimal.ZERO, null));
        var unknown = new ReactionNetwork.Node(new ReactionNetwork.Identity("REACTION", "R2"), "REACTION", null,
                new ReactionNetwork.ReactionDetails(null, "gateway", BigDecimal.ONE,
                        ReactionNetwork.ReactionStatus.SUPERSEDED, 1, BigDecimal.ONE, null));
        var network = new ReactionNetwork("observer-v2", List.of(owned, unknown), List.of());
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(graphs.find("dev", ArchitectureImportKind.FULL_REACTIONS, "", "", "")).thenReturn(Optional.of(
                new StoredReactionGraph("dev", ArchitectureImportKind.FULL_REACTIONS, "", "", "", "observer-v2", "tag", "fp", data, 2, Instant.EPOCH)));
        when(content.readFull(data)).thenReturn(network);
        var published = publication.read("dev").orElseThrow();
        assertThat(published.ownership()).containsExactly(new PublishedReactionNetwork.Ownership(owned.identity(), "observer-system", "gateway"));
        assertThat(published.ownershipComplete()).isFalse();
        assertThat(published.graph()).isEqualTo(network);
        verifyNoInteractions(models);
    }

    @Test
    void disabledObserverDoesNotPublishPreviouslyStoredData() {
        assertThat(publication.read("prod")).isEmpty();
        verifyNoInteractions(graphs, content, models);
    }

    private static DocumentedSystem system(String name, DocumentedComponent component) {
        DocumentedSystem system = mock(DocumentedSystem.class);
        when(system.name()).thenReturn(name);
        when(system.components()).thenReturn(List.of(component));
        return system;
    }
}
