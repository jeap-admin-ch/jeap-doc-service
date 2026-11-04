package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.PublishedReactionNetwork;
import ch.admin.bit.jeap.doc.domain.architecture.ReactionNetwork;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureModelSource;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphContent;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphRepository;
import ch.admin.bit.jeap.doc.domain.port.ReactionGraphUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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

    public Optional<PublishedReactionNetwork> read(String environment) {
        if (!upstream.isConfiguredFor(environment)) {
            return Optional.empty();
        }
        return graphs.find(environment, ArchitectureImportKind.FULL_REACTIONS, "", "", "")
                .map(stored -> publish(environment, content.readFull(stored.data()), stored.importedAt()));
    }

    private PublishedReactionNetwork publish(String environment, ReactionNetwork graph, java.time.Instant importedAt) {
        Map<String, List<Owner>> owners = ownersOf(environment, graph);
        List<PublishedReactionNetwork.Ownership> ownership = new ArrayList<>();
        boolean complete = true;
        for (ReactionNetwork.Node node : graph.nodes()) {
            if (node.reaction() == null) {
                continue;
            }
            Optional<Owner> owner = ownerOf(node.reaction(), owners);
            if (owner.isPresent()) {
                Owner resolved = owner.orElseThrow();
                ownership.add(new PublishedReactionNetwork.Ownership(node.identity(), resolved.system(), resolved.component()));
            } else {
                complete = false;
            }
        }
        return new PublishedReactionNetwork(graph, importedAt, ownership, complete);
    }

    private Map<String, List<Owner>> ownersOf(String environment, ReactionNetwork graph) {
        Map<String, List<Owner>> owners = new HashMap<>();
        // V2 ownership is authoritative, including an explicitly unknown owner.
        if (graph.identityScheme().equals("observer-v1")) {
            for (DocumentedSystem system : models.read(environment).model().systems()) {
                for (DocumentedComponent component : system.components()) {
                    owners.computeIfAbsent(component.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                            .add(new Owner(system.name(), component.name()));
                }
            }
        }
        return owners;
    }

    private static Optional<Owner> ownerOf(ReactionNetwork.ReactionDetails reaction, Map<String, List<Owner>> owners) {
        if (reaction.system() != null) {
            return Optional.of(new Owner(reaction.system(), reaction.component()));
        }
        var candidates = owners.getOrDefault(reaction.component().toLowerCase(Locale.ROOT), List.of());
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private record Owner(String system, String component) {}
}
