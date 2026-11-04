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
        var node = new ObservedReactions.ObservedReaction(new ObservedReactions.Identity("REACTION", "123"), "gateway", BigDecimal.ZERO);
        var network = new ObservedReactions(List.of(), List.of(node), List.of(), List.of());
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(graphs.find("dev")).thenReturn(Optional.of(
                new StoredReactionGraph("dev", "", "tag", "fp", data, 1, Instant.EPOCH)));
        when(content.readFull(data)).thenReturn(network);
        DocumentedComponent component = mock(DocumentedComponent.class);
        when(component.name()).thenReturn("Gateway");
        DocumentedSystem orders = system("Orders", component);
        DocumentedSystem shipping = system("Shipping", component);
        when(models.read("dev")).thenReturn(new ArchitectureSnapshot(new ArchitectureModel(List.of(orders)), Instant.EPOCH));
        var unique = publication.read("dev").orElseThrow();
        assertThat(unique.ownershipComplete()).isTrue();
        assertThat(unique.ownership()).containsExactly(new PublishedReactions.Ownership(node.identity(), "Orders", "Gateway"));
        when(models.read("dev")).thenReturn(new ArchitectureSnapshot(new ArchitectureModel(List.of(orders, shipping)), Instant.EPOCH));
        var ambiguous = publication.read("dev").orElseThrow();
        assertThat(ambiguous.ownershipComplete()).isFalse();
        assertThat(ambiguous.ownership()).isEmpty();
        assertThat(ambiguous.observations()).isEqualTo(network);
        when(models.read("dev")).thenReturn(ArchitectureSnapshot.empty());
        assertThat(publication.read("dev").orElseThrow().ownershipComplete()).isFalse();
    }

    @Test
    void v2OwnershipComesFromTheObserverAndMissingOwnersStayUnknown() {
        byte[] data = {2};
        var owned = new ObservedReactions.ObservedReaction(new ObservedReactions.Identity("REACTION", "R1"), "observer-system", "gateway", BigDecimal.ZERO,
                        ObservedReactions.ReactionStatus.ACTIVE, 2, BigDecimal.ZERO, null);
        var unknown = new ObservedReactions.ObservedReaction(new ObservedReactions.Identity("REACTION", "R2"), null, "gateway", BigDecimal.ONE,
                        ObservedReactions.ReactionStatus.SUPERSEDED, 1, BigDecimal.ONE, null);
        var network = new ObservedReactions("observer-v2", List.of(), List.of(owned, unknown), List.of(), List.of(), List.of());
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(graphs.find("dev")).thenReturn(Optional.of(
                new StoredReactionGraph("dev", "observer-v2", "tag", "fp", data, 2, Instant.EPOCH)));
        when(content.readFull(data)).thenReturn(network);
        var published = publication.read("dev").orElseThrow();
        assertThat(published.ownership()).containsExactly(new PublishedReactions.Ownership(owned.identity(), "observer-system", "gateway"));
        assertThat(published.ownershipComplete()).isFalse();
        assertThat(published.observations()).isEqualTo(network);
        verifyNoInteractions(models);
    }

    @Test
    void publishesOnlyUnambiguousDocumentedMessageSystemsForBothGraphVersions() {
        byte[] data = {3};
        var known = new ObservedReactions.ObservedMessage(new ObservedReactions.Identity("MESSAGE", "1"), "OrderAcceptedEvent", "");
        var variant = new ObservedReactions.ObservedMessage(new ObservedReactions.Identity("MESSAGE", "2"), "OrderAcceptedEvent", "express");
        var ambiguous = new ObservedReactions.ObservedMessage(new ObservedReactions.Identity("MESSAGE", "3"), "SharedEvent", "");
        var unknown = new ObservedReactions.ObservedMessage(new ObservedReactions.Identity("MESSAGE", "4"), "UnknownEvent", "");
        var orderMessage = mock(DocumentedMessage.class);
        when(orderMessage.name()).thenReturn("orderacceptedevent");
        var sharedMessage = mock(DocumentedMessage.class);
        when(sharedMessage.name()).thenReturn("SharedEvent");
        var orders = mock(DocumentedSystem.class);
        when(orders.name()).thenReturn("Orders");
        when(orders.messages()).thenReturn(List.of(orderMessage, sharedMessage));
        var billing = mock(DocumentedSystem.class);
        when(billing.name()).thenReturn("Billing");
        when(billing.messages()).thenReturn(List.of(sharedMessage));
        when(models.read("dev")).thenReturn(new ArchitectureSnapshot(new ArchitectureModel(List.of(orders, billing)), Instant.EPOCH));
        when(upstream.isConfiguredFor("dev")).thenReturn(true);
        when(graphs.find("dev")).thenReturn(Optional.of(
                new StoredReactionGraph("dev", "", "tag", "fp", data, 4, Instant.EPOCH)));
        for (String scheme : List.of("observer-v1", "observer-v2")) {
            var network = new ObservedReactions(scheme, List.of(known, variant, ambiguous, unknown), List.of(), List.of(), List.of(), List.of());
            when(content.readFull(data)).thenReturn(network);
            var published = publication.read("dev").orElseThrow();
            assertThat(published.messageOwnership()).containsExactly(
                    new PublishedReactions.MessageOwnership("orderacceptedevent", "Orders"));
            assertThat(published.observations()).isEqualTo(network);
        }
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
