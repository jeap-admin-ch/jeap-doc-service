package ch.admin.bit.jeap.doc.domain.architecture.view;

import ch.admin.bit.jeap.doc.domain.architecture.*;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReactionDocumentationTest {
    private static final DocumentedSystem A = system("a", List.of("gateway", "old", "no-observations"),
            List.of("AcceptedEvent", "OrphanEvent", "HistoryEvent"));
    private static final DocumentedSystem B = system("b", List.of("gateway"), List.of());
    private static final ArchitectureModel MODEL = ArchitectureModel.of(List.of(A, B));
    private static final Instant IMPORTED = Instant.parse("2026-11-04T08:00:00Z");

    @Test
    void systemAndComponentSelectionsUseSystemOwnershipAndRetainExactMedians() {
        var docs = documentation();
        var a = docs.ofSystem(A);
        var b = docs.ofSystem(B);
        assertThat(a.importedAt()).isEqualTo(IMPORTED);
        assertThat(ids(a.system())).containsExactly("opaque/a#1", "opaque/a#2");
        assertThat(ids(a.ofComponent("GATEWAY"))).containsExactly("opaque/a#1", "opaque/a#2");
        assertThat(ids(b.ofComponent("gateway"))).containsExactly("opaque/b#1");
        assertThat(a.system().rows().getFirst().median()).isEqualByComparingTo("1.25");
        assertThat(a.system().rows().getFirst().answers()).containsExactly("gateway POST /items/{id}");
        assertThat(b.system().rows().getFirst().answers()).containsExactly("FUTURE queue:orders");
        assertThat(a.ofComponent("no-observations").isEmpty()).isTrue();
        assertThat(a.system().components()).extracting(ReactionView.ComponentLink::system).containsOnly("a");
        assertThat(b.system().components()).extracting(ReactionView.ComponentLink::system).containsOnly("b");
    }

    @Test
    void variantsKeepTheirExactNamesAndOnlyDirectReactionsAcrossSystems() {
        var views = documentation().ofSystem(A).ofMessage("ACCEPTEDEVENT");
        assertThat(views).extracting(ReactionViews.VariantView::variant).containsExactly("", "a-b", "a_b");
        assertThat(ids(views.getFirst().view())).containsExactlyInAnyOrder("opaque/a#1", "opaque/b#1");
        // A shared group key must not pull another variant into the view.
        assertThat(ids(views.getLast().view())).containsExactly("opaque/a#2");
        var unresolved = views.get(1).view();
        assertThat(ids(unresolved)).containsExactly("unresolved");
        assertThat(unresolved.components()).isEmpty();
        assertThat(unresolved.rows().getFirst().trigger()).isEqualTo("AcceptedEvent [a-b]");
    }

    @Test
    void absenceAndHiddenSupersededObservationsRemainDifferent() {
        var a = documentation().ofSystem(A);
        assertThat(a.ofMessage("OrphanEvent")).isEmpty();
        assertThat(a.ofMessage("HistoryEvent")).singleElement().satisfies(variant -> {
            assertThat(variant.view().isEmpty()).isFalse();
            assertThat(variant.view().rows()).isEmpty();
        });
        assertThat(a.ofComponent("old").isEmpty()).isFalse();
        assertThat(a.ofComponent("old").rows()).isEmpty();
    }

    @Test
    void ambiguousMessageNamesNeverProduceAnArbitraryDocumentationLink() {
        var duplicate = system("duplicate", List.of(), List.of("AcceptedEvent"));
        var docs = new ReactionDocumentation(snapshot(), ArchitectureModel.of(List.of(A, B, duplicate)));
        assertThat(docs.ofSystem(A).system().messages()).isEmpty();
    }

    @Test
    void messageEntryAlsoIncludesReactionsThatPublishTheMessage() {
        var input = message("input", "InputEvent", "");
        var output = message("output", "AcceptedEvent", "");
        var reaction = reaction("producer", "b", "gateway", "1", null, ReactionStatus.ACTIVE);
        var snapshot = snapshot(List.of(input, output), List.of(reaction), List.of(trigger(input, reaction)),
                List.of(new ObservedAction(reaction.identity(), output.identity())), List.of());
        var view = new ReactionDocumentation(snapshot, MODEL).ofSystem(A).ofMessage("AcceptedEvent").getFirst().view();
        assertThat(ids(view)).containsExactly("producer");
        assertThat(view.rows().getFirst().trigger()).isNull();
        assertThat(view.rows().getFirst().answers()).containsExactly("AcceptedEvent");
    }

    @Test
    void restDestinationsFollowEachEntrySelectionAndDeduplicateProviderOperations() {
        var system = system("orders", List.of("first", "second", "old"), List.of("PaidEvent"));
        var providers = providers("providers", List.of(api("first-api", true), api("second-api", true),
                api("unused-api", true), api("no-api", false)));
        var views = new ReactionDocumentation(restSnapshot(), ArchitectureModel.of(List.of(system, providers))).ofSystem(system);
        var first = new ReactionView.RestLink("first-api", "first-api", "providers");
        var second = new ReactionView.RestLink("second-api", "second-api", "providers");

        assertThat(views.system().restProviders()).containsExactly(first, second);
        assertThat(views.ofComponent("first").restProviders()).containsExactly(first);
        assertThat(views.ofComponent("second").restProviders()).containsExactly(second);
        assertThat(views.ofComponent("old").restProviders()).isEmpty();
        assertThat(views.ofMessage("PaidEvent").getFirst().view().restProviders()).isEmpty();
        assertThat(views.ofMessage("PaidEvent").getLast().view().restProviders()).isEmpty();
    }

    @Test
    void ambiguousRestProvidersRemainUnlinkedEvenWhenOnlyOneHasAnApi() {
        var system = system("orders", List.of("first", "second"), List.of("PaidEvent"));
        var providers = providers("providers", List.of(api("first-api", true), api("second-api", true)));
        var duplicate = providers("other", List.of(api("FIRST-API", false)));
        var views = new ReactionDocumentation(restSnapshot(), ArchitectureModel.of(List.of(system, providers, duplicate))).ofSystem(system);

        assertThat(views.system().restProviders()).containsExactly(new ReactionView.RestLink("second-api", "second-api", "providers"));
        assertThat(views.ofComponent("first").restProviders()).isEmpty();
    }

    private static PublishedReactions restSnapshot() {
        var event = message("paid", "PaidEvent", "");
        var express = message("express", "PaidEvent", "express");
        var first = reaction("first", "orders", "first", "1", null, ReactionStatus.ACTIVE);
        var second = reaction("second", "orders", "second", "1", null, ReactionStatus.ACTIVE);
        var old = reaction("old", "orders", "old", "1", null, ReactionStatus.SUPERSEDED);
        var one = new ObservedHttpOperation(id("one"), "HTTP", "one", "FIRST-API", "GET", "/one");
        var two = new ObservedHttpOperation(id("two"), "HTTP", "two", "first-api", "POST", "/two");
        var trigger = new ObservedHttpOperation(id("trigger"), "HTTP", "trigger", "second-api", "GET", "/trigger");
        var unused = new ObservedHttpOperation(id("unused"), "HTTP", "unused", "unused-api", "GET", "/unused");
        var missing = new ObservedHttpOperation(id("missing"), "HTTP", "missing", "no-api", "GET", "/missing");
        return snapshot(List.of(event, express, one, two, trigger, unused, missing), List.of(first, second, old),
                List.of(trigger(event, first), trigger(trigger, second), trigger(unused, old)),
                List.of(new ObservedAction(first.identity(), one.identity()), new ObservedAction(first.identity(), two.identity()),
                        new ObservedAction(first.identity(), missing.identity()), new ObservedAction(second.identity(), express.identity())), List.of());
    }

    private static DocumentedSystem providers(String name, List<DocumentedComponent> components) {
        return new DocumentedSystem(name, name, null, List.of(), null, components, List.of(), List.of());
    }

    private static DocumentedComponent api(String name, boolean available) {
        return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, null, null, null,
                available ? List.of(new RestApiOperation("GET", "/")) : List.of(), null, null, null);
    }

    private static ReactionDocumentation documentation() { return new ReactionDocumentation(snapshot(), MODEL); }
    private static List<String> ids(ReactionView view) { return view.rows().stream().map(row -> row.reaction().key()).toList(); }

    private static PublishedReactions snapshot() {
        var event = message("event", "AcceptedEvent", "");
        var underscore = message("underscore", "AcceptedEvent", "a_b");
        var hyphen = message("hyphen", "AcceptedEvent", "a-b");
        var orphan = message("orphan", "OrphanEvent", "");
        var history = message("history", "HistoryEvent", "");
        var http = new ObservedHttpOperation(id("http"), "HTTP", null, "gateway", "POST", "/items/{id}");
        var future = new ObservedGenericInterface(id("future"), "UNKNOWN", "FUTURE", "queue:orders");
        var r1 = reaction("opaque/a#1", "a", "gateway", "1.25", "shared", ReactionStatus.ACTIVE);
        var r2 = reaction("opaque/a#2", "a", "gateway", "2", "shared", ReactionStatus.UNKNOWN);
        var r3 = reaction("opaque/b#1", "b", "gateway", "3", "shared", ReactionStatus.ACTIVE);
        var unknown = reaction("unresolved", null, "gateway", "0", "shared", ReactionStatus.ACTIVE);
        var old = reaction("old", "a", "old", "9", null, ReactionStatus.SUPERSEDED);
        return snapshot(List.of(event, underscore, hyphen, orphan, history, http, future), List.of(r1, r2, r3, unknown, old),
                List.of(trigger(event, r1), trigger(underscore, r2), trigger(event, r3), trigger(hyphen, unknown), trigger(history, old)),
                List.of(new ObservedAction(r1.identity(), http.identity()),
                        new ObservedAction(r1.identity(), http.identity()), // duplicate contributes one answer
                        new ObservedAction(r3.identity(), future.identity())),
                List.of(new ObservedPredecessor(r1.identity(), old.identity())));
    }

    private static PublishedReactions snapshot(List<ObservedInterface> interfaces, List<ObservedReaction> reactions,
                                               List<ObservedTrigger> triggers, List<ObservedAction> actions,
                                               List<ObservedPredecessor> predecessors) {
        var owners = reactions.stream().filter(reaction -> reaction.system() != null)
                .map(reaction -> new PublishedReactions.Ownership(reaction.identity(), reaction.system(), reaction.component())).toList();
        return new PublishedReactions(new ObservedReactions("observer-v2", interfaces, reactions, triggers, actions, predecessors),
                IMPORTED, owners, List.of(), false);
    }
    private static Identity id(String key) { return new Identity("INTERFACE", key); }
    private static ObservedMessage message(String key, String name, String variant) {
        return new ObservedMessage(id(key), "MESSAGE", name, name, variant);
    }
    private static ObservedReaction reaction(String key, String system, String component, String median, String group, ReactionStatus status) {
        return new ObservedReaction(new Identity("REACTION", key), system, component, new BigDecimal(median), status, 1, BigDecimal.ZERO, group);
    }
    private static ObservedTrigger trigger(ObservedInterface source, ObservedReaction target) {
        return new ObservedTrigger(source.identity(), target.identity());
    }
    private static DocumentedSystem system(String name, List<String> components, List<String> messages) {
        return new DocumentedSystem(name, name, null, List.of(), null, components.stream().map(component ->
                new DocumentedComponent(component, component, null, ComponentType.SELF_CONTAINED_SYSTEM,
                        null, null, null, List.of(), null, null, null)).toList(), List.of(), messages.stream().map(message ->
                new DocumentedMessage(message, message.toLowerCase(), MessageKind.EVENT, null, null, null, null, null,
                        List.of(), List.of())).toList());
    }
}
