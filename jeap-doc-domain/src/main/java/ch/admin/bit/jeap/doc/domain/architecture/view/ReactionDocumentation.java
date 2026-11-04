package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.*;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import java.util.*;
import static ch.admin.bit.jeap.doc.domain.template.ReactionViews.fold;

/** Indexes the published snapshot once and derives the same expanded entry selections as the viewer. */
public final class ReactionDocumentation {
    private final PublishedReactions snapshot;
    private final Map<Identity, ObservedInterface> interfaces = new LinkedHashMap<>();
    private final Map<Identity, ObservedReaction> reactions = new LinkedHashMap<>();
    private final Map<Identity, PublishedReactions.Ownership> owners = new HashMap<>();
    private final Map<Identity, Identity> triggers = new HashMap<>();
    private final Map<Identity, Set<Identity>> actions = new HashMap<>();
    private final Map<Identity, Set<Identity>> neighbours = new HashMap<>();
    private final Map<Identity, Group> groupOf = new HashMap<>();
    private final Map<Group, Set<Identity>> groups = new LinkedHashMap<>();

    private final Map<String, Map<String, Set<Identity>>> variantsByMessage = new HashMap<>();
    private final Map<String, Set<Identity>> reactionsBySystem = new HashMap<>();
    private final Map<Owner, Set<Identity>> reactionsByComponent = new HashMap<>();
    private final Map<Owner, ReactionView.ComponentLink> componentLinks = new HashMap<>();
    private final Map<String, List<ReactionView.MessageLink>> messageLinks = new HashMap<>();
    private final Map<String, ReactionView.RestLink> restLinks = new HashMap<>();

    public ReactionDocumentation(PublishedReactions snapshot, ArchitectureModel model) {
        this.snapshot = snapshot;
        indexObservations();
        indexDocumentation(model);
        indexVariants();
        indexReactionGroups();
    }

    private void indexObservations() {
        snapshot.observations().interfaces().forEach(observed -> interfaces.put(observed.identity(), observed));
        snapshot.observations().reactions().forEach(reaction -> reactions.put(reaction.identity(), reaction));
        snapshot.ownership().forEach(owner -> owners.put(owner.reaction(), owner));
        for (ObservedTrigger trigger : snapshot.observations().triggers()) {
            triggers.put(trigger.reactionId(), trigger.interfaceId());
            neighbours.computeIfAbsent(trigger.interfaceId(), ignored -> new LinkedHashSet<>()).add(trigger.reactionId());
        }
        for (ObservedAction action : snapshot.observations().actions()) {
            actions.computeIfAbsent(action.reactionId(), ignored -> new LinkedHashSet<>()).add(action.interfaceId());
            neighbours.computeIfAbsent(action.interfaceId(), ignored -> new LinkedHashSet<>()).add(action.reactionId());
        }
    }

    private void indexDocumentation(ArchitectureModel model) {
        Map<String, Integer> componentNames = new HashMap<>();
        for (DocumentedSystem system : model.systems()) {
            for (DocumentedComponent component : system.components()) {
                String name = fold(component.name());
                componentNames.merge(name, 1, Integer::sum);
                if (component.hasRestApi()) {
                    restLinks.put(name, new ReactionView.RestLink(component.name(), component.slug(), system.slug()));
                }
                componentLinks.put(new Owner(fold(system.name()), fold(component.name())),
                        new ReactionView.ComponentLink(system.name(), component.name(), component.slug(), system.slug()));
            }
            for (DocumentedMessage message : system.messages()) {
                messageLinks.computeIfAbsent(fold(message.name()), ignored -> new ArrayList<>()).add(
                        new ReactionView.MessageLink(message.name(), system.slug(), message.slug(), message.kind()));
            }
        }
        // HTTP observations name no system. A duplicate component without an API is still ambiguous.
        restLinks.keySet().removeIf(name -> componentNames.get(name) != 1);
    }

    private void indexVariants() {
        for (ObservedMessage message : snapshot.observations().messages()) {
            if (!neighbours.getOrDefault(message.identity(), Set.of()).isEmpty()) {
                variantsByMessage.computeIfAbsent(fold(message.messageType()), ignored -> new TreeMap<>())
                        .computeIfAbsent(Objects.requireNonNullElse(message.variant(), ""), ignored -> new LinkedHashSet<>())
                        .add(message.identity());
            }
        }
    }

    private void indexReactionGroups() {
        for (ObservedReaction reaction : reactions.values()) {
            var owner = owners.get(reaction.identity());
            if (owner != null) {
                reactionsBySystem.computeIfAbsent(fold(owner.system()), ignored -> new LinkedHashSet<>()).add(reaction.identity());
                reactionsByComponent.computeIfAbsent(new Owner(fold(owner.system()), fold(owner.component())),
                        ignored -> new LinkedHashSet<>()).add(reaction.identity());
            }
            Identity trigger = triggers.get(reaction.identity());
            Object key = reaction.groupKey() == null ? trigger : reaction.groupKey();
            Group group = owner == null || trigger == null || owner.system() == null || owner.component() == null
                    ? new Group(null, null, null, reaction.identity())
                    : new Group(owner.system(), owner.component(), key, null);
            groups.computeIfAbsent(group, ignored -> new LinkedHashSet<>()).add(reaction.identity());
            groupOf.put(reaction.identity(), group);
        }
    }

    public ReactionViews ofSystem(DocumentedSystem system) {
        Map<String, ReactionView> components = new LinkedHashMap<>();
        for (DocumentedComponent component : system.components()) {
            ReactionView view = view(reactionsOf(system.name(), component.name()), Set.of());
            if (!view.isEmpty()) components.put(component.name(), view);
        }
        Map<String, List<ReactionViews.VariantView>> messages = new LinkedHashMap<>();
        for (DocumentedMessage message : system.messages()) {
            Map<String, Set<Identity>> variants = variantsByMessage.getOrDefault(fold(message.name()), Map.of());
            List<ReactionViews.VariantView> views = new ArrayList<>();
            for (var variant : variants.entrySet()) {
                Set<Group> selectedGroups = new LinkedHashSet<>();
                variant.getValue().forEach(id -> neighbours.getOrDefault(id, Set.of()).stream().filter(this::active)
                        .map(groupOf::get).forEach(selectedGroups::add));
                Set<Identity> selected = new LinkedHashSet<>();
                selectedGroups.forEach(group -> selected.addAll(groups.get(group)));
                // A message with only superseded neighbours still shows the viewer's hidden-content marker.
                views.add(new ReactionViews.VariantView(variant.getKey(), view(selected, variant.getValue())));
            }
            if (!views.isEmpty()) messages.put(message.name(), views);
        }
        return ReactionViews.of(snapshot.importedAt(), view(reactionsOf(system.name(), null), Set.of()), components, messages);
    }

    private Set<Identity> reactionsOf(String system, String component) {
        return component == null ? reactionsBySystem.getOrDefault(fold(system), Set.of())
                : reactionsByComponent.getOrDefault(new Owner(fold(system), fold(component)), Set.of());
    }

    private boolean active(Identity id) { return reactions.get(id).status() != ReactionStatus.SUPERSEDED; }

    private ReactionView view(Set<Identity> selectedReactions, Set<Identity> pinned) {
        Set<Identity> selectedInterfaces = new LinkedHashSet<>(pinned);
        List<ReactionView.Row> rows = new ArrayList<>();
        Set<ReactionView.ComponentLink> components = new LinkedHashSet<>();
        for (Identity id : selectedReactions) {
            componentLink(owners.get(id)).ifPresent(components::add);
            if (!active(id)) continue;
            Identity trigger = triggers.get(id);
            if (trigger != null) selectedInterfaces.add(trigger);
            Set<Identity> answers = actions.getOrDefault(id, Set.of());
            selectedInterfaces.addAll(answers);
            rows.add(new ReactionView.Row(id, trigger == null ? null : label(trigger), reactions.get(id).component(),
                    answers.stream().map(this::label).sorted().toList(), reactions.get(id).median()));
        }
        rows.sort(Comparator.comparing(ReactionView.Row::component).thenComparing(row -> Objects.requireNonNullElse(row.trigger(), ""))
                .thenComparing(row -> row.reaction().key()));
        List<ReactionView.MessageLink> links = selectedInterfaces.stream().map(interfaces::get)
                .filter(ObservedMessage.class::isInstance).map(ObservedMessage.class::cast)
                .map(ObservedMessage::messageType).distinct()
                .flatMap(name -> messageLink(name).stream()).distinct().toList();
        List<ReactionView.RestLink> restProviders = selectedInterfaces.stream().map(interfaces::get)
                .filter(ObservedHttpOperation.class::isInstance).map(ObservedHttpOperation.class::cast)
                .map(http -> restLinks.get(fold(http.provider()))).filter(Objects::nonNull).distinct().toList();
        return new ReactionView(links, List.copyOf(components), restProviders, rows, !selectedReactions.isEmpty() || !pinned.isEmpty());
    }

    private Optional<ReactionView.ComponentLink> componentLink(PublishedReactions.Ownership owner) {
        if (owner == null) return Optional.empty();
        return Optional.ofNullable(componentLinks.get(new Owner(fold(owner.system()), fold(owner.component()))));
    }

    private Optional<ReactionView.MessageLink> messageLink(String name) {
        List<ReactionView.MessageLink> matches = messageLinks.getOrDefault(fold(name), List.of());
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private String label(Identity id) {
        return switch (interfaces.get(id)) {
            case ObservedMessage message -> message.messageType()
                    + (message.variant() == null || message.variant().isEmpty() ? "" : " [" + message.variant() + "]");
            case ObservedHttpOperation http -> http.provider() + " " + http.method() + " " + http.path();
            case ObservedGenericInterface generic -> Objects.requireNonNullElse(generic.interfaceType(), generic.representation())
                    + " " + Objects.requireNonNullElse(generic.fqn(), id.key());
        };
    }

    private record Owner(String system, String component) {}
    private record Group(String system, String component, Object key, Identity single) {}
}
