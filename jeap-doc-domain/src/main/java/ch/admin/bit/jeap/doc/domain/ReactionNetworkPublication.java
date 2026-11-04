package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.PublishedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;

import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Reads a stored snapshot; unknown and ambiguous owners never remove nodes from it. */
@Service
@RequiredArgsConstructor
public class ReactionNetworkPublication {
    private final ReactionGraphRepository graphs;
    private final ReactionGraphContent content;
    private final ArchitectureModelSource models;
    private final ReactionGraphUpstream upstream;

    public Optional<PublishedReactions> read(String environment) {
        if (!upstream.isConfiguredFor(environment)) {
            return Optional.empty();
        }
        return graphs.find(environment)
                .map(stored -> publish(environment, content.readFull(stored.data()), stored.importedAt()));
    }

    private PublishedReactions publish(String environment, ObservedReactions graph, java.time.Instant importedAt) {
        boolean needsModel = graph.identityScheme().equals("observer-v1") || !graph.messages().isEmpty();
        ArchitectureModel model = needsModel ? models.read(environment).model() : ArchitectureModel.empty();
        Map<String, List<Owner>> owners = ownersOf(model, graph);
        List<PublishedReactions.Ownership> ownership = new ArrayList<>();
        boolean complete = true;
        for (ObservedReactions.ObservedReaction reaction : graph.reactions()) {
            Optional<Owner> owner = ownerOf(reaction, owners);
            if (owner.isPresent()) {
                Owner resolved = owner.orElseThrow();
                ownership.add(new PublishedReactions.Ownership(reaction.identity(), resolved.system(), resolved.component()));
            } else {
                complete = false;
            }
        }
        return new PublishedReactions(graph, importedAt, ownership, messageOwnershipOf(model, graph), complete);
    }

    private static List<PublishedReactions.MessageOwnership> messageOwnershipOf(ArchitectureModel model, ObservedReactions graph) {
        Set<String> names = new LinkedHashSet<>();
        graph.messages().stream().map(ObservedReactions.ObservedMessage::messageType).filter(java.util.Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT)).forEach(names::add);
        Map<String, Set<String>> systems = new LinkedHashMap<>();
        for (DocumentedSystem system : model.systems()) {
            for (var message : system.messages()) {
                String name = message.name().toLowerCase(Locale.ROOT);
                if (names.contains(name)) {
                    systems.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(system.name());
                }
            }
        }
        return systems.entrySet().stream().filter(entry -> entry.getValue().size() == 1)
                .map(entry -> new PublishedReactions.MessageOwnership(entry.getKey(), entry.getValue().iterator().next()))
                .toList();
    }

    private Map<String, List<Owner>> ownersOf(ArchitectureModel model, ObservedReactions graph) {
        Map<String, List<Owner>> owners = new HashMap<>();
        // V2 ownership is authoritative, including an explicitly unknown owner.
        if (graph.identityScheme().equals("observer-v1")) {
            for (DocumentedSystem system : model.systems()) {
                for (DocumentedComponent component : system.components()) {
                    owners.computeIfAbsent(component.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(new Owner(system.name(), component.name()));
                }
            }
        }
        return owners;
    }

    private static Optional<Owner> ownerOf(ObservedReactions.ObservedReaction reaction, Map<String, List<Owner>> owners) {
        if (reaction.system() != null) {
            return Optional.of(new Owner(reaction.system(), reaction.component()));
        }
        var candidates = owners.getOrDefault(reaction.component().toLowerCase(Locale.ROOT), List.of());
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private record Owner(String system, String component) {}
}
