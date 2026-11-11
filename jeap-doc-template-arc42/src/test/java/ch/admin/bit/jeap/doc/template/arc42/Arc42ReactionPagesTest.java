package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.*;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionDocumentation;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions.*;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.markdown.ReactionEntry;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Arc42ReactionPagesTest {
    @Test
    void interfaceLinksUseOnlyTheDestinationsSuppliedByTheView() {
        var model = ArchitectureModel.of(List.of(
                system("s", List.of(component("provider", true), component("ambiguous", true), component("no-api", false))),
                system("other", List.of(component("ambiguous", false)))));
        var context = new GenerationContext(model, "dev", null, Instant.EPOCH, Instant.EPOCH,
                new DiagramLimits(100, 4, 40, 100, 200, 40, 20, 1000), "/site/docs/dev/");
        var view = new ReactionView(List.of(
                new ReactionView.MessageLink("AcceptedEvent", "s", "accepted", MessageKind.EVENT),
                new ReactionView.MessageLink("AcceptedEvent", "s", "accepted", MessageKind.EVENT),
                new ReactionView.MessageLink("AcceptCommand", "s", "accept", MessageKind.COMMAND)),
                List.of(), List.of(new ReactionView.RestLink("provider", "provider", "s")), List.of(), true);
        assertThat(Arc42ReactionPages.interfaceLinks(view, context)).containsExactly(
                new ReactionEntry.InterfaceLink("EVENT", "AcceptedEvent", "/site/docs/dev/systems/s/system-architecture/building-block-view/events/accepted/"),
                new ReactionEntry.InterfaceLink("COMMAND", "AcceptCommand", "/site/docs/dev/systems/s/system-architecture/building-block-view/commands/accept/"),
                new ReactionEntry.InterfaceLink("REST", "provider", "/site/docs/dev/systems/s/system-architecture/building-block-view/components/provider/component-architecture/building-block-view/rest-api/"));
    }

    @Test
    void componentLinksRequireAnAvailableRuntimePageAndOmitTheCurrentComponent() {
        var context = new GenerationContext(ArchitectureModel.of(List.of(system("s", List.of()))),
                "dev", null, Instant.EPOCH, Instant.EPOCH,
                new DiagramLimits(100, 4, 40, 100, 200, 40, 20, 1000), "/site/docs/dev/");
        var group = new ReactionView.ComponentLink("s", "provider", "provider", "s");
        assertThat(Arc42ReactionPages.componentLink(group, context, null)).contains(
                "/site/docs/dev/systems/s/system-architecture/building-block-view/components/provider/component-architecture/runtime-view/component-reactions/");
        assertThat(Arc42ReactionPages.componentLink(group, context, "PROVIDER")).isEmpty();

    }

    @Test
    void unrelatedRestProvidersDoNotChangeViewerContentForV1OrHttpViews() {
        var system = system("s", List.of(component("consumer", false), component("provider", true)));
        var model = ArchitectureModel.of(List.of(system));
        var largerModel = ArchitectureModel.of(List.of(system, system("unrelated", List.of(component("unrelated-api", true)))));
        for (boolean http : List.of(false, true)) {
            var message = new ObservedMessage(new Identity(http ? "INTERFACE" : "MESSAGE", "event"), "AcceptedEvent", "");
            var reaction = new ObservedReaction(new Identity("REACTION", "1"), "consumer", java.math.BigDecimal.ONE);
            var operation = new ObservedHttpOperation(new Identity("INTERFACE", "http"), "HTTP", "GET /items", "provider", "GET", "/items");
            var observations = new ObservedReactions(http ? "observer-v2" : "observer-v1",
                    http ? List.of(message, operation) : List.of(message), List.of(reaction),
                    List.of(new ObservedTrigger(message.identity(), reaction.identity())),
                    http ? List.of(new ObservedAction(reaction.identity(), operation.identity())) : List.of(), List.of());
            var snapshot = new PublishedReactions(observations, Instant.EPOCH,
                    List.of(new PublishedReactions.Ownership(reaction.identity(), "s", "consumer")), List.of(), true);
            var view = new ReactionDocumentation(snapshot, model).ofSystem(system).system();
            var withUnrelated = new ReactionDocumentation(snapshot, largerModel).ofSystem(system).system();

            assertThat(view.restProviders()).hasSize(http ? 1 : 0);
            assertThat(render(withUnrelated, largerModel)).isEqualTo(render(view, model));
        }
    }

    @Test
    void reorderedSnapshotProducesIdenticalViewerAndTable() {
        var accepted = new ObservedMessage(new Identity("INTERFACE", "accepted"), "AcceptedEvent", "");
        var shipped = new ObservedMessage(new Identity("INTERFACE", "shipped"), "ShippedEvent", "");
        var first = new ObservedReaction(new Identity("REACTION", "first"), "first", BigDecimal.ONE);
        var second = new ObservedReaction(new Identity("REACTION", "second"), "second", BigDecimal.TEN);
        var firstHttp = new ObservedHttpOperation(new Identity("INTERFACE", "first-http"),
                "HTTP", "GET /items", "first", "GET", "/items");
        var secondHttp = new ObservedHttpOperation(new Identity("INTERFACE", "second-http"),
                "HTTP", "GET /items", "second", "GET", "/items");
        var system = new DocumentedSystem("s", "s", null, List.of(), null,
                List.of(component("first", true), component("second", true)), List.of(), List.of(
                new DocumentedMessage("AcceptedEvent", "accepted", MessageKind.EVENT, null, null, null, null, null, List.of(), List.of()),
                new DocumentedMessage("ShippedEvent", "shipped", MessageKind.EVENT, null, null, null, null, null, List.of(), List.of())));
        var model = ArchitectureModel.of(List.of(system));
        var observations = new ObservedReactions("observer-v2", List.of(accepted, shipped, firstHttp, secondHttp),
                List.of(first, second),
                List.of(new ObservedTrigger(accepted.identity(), first.identity()), new ObservedTrigger(shipped.identity(), second.identity())),
                List.of(new ObservedAction(first.identity(), firstHttp.identity()), new ObservedAction(second.identity(), secondHttp.identity())),
                List.of());
        var ownership = List.of(new PublishedReactions.Ownership(first.identity(), "s", "first"),
                new PublishedReactions.Ownership(second.identity(), "s", "second"));
        var snapshot = new PublishedReactions(observations, Instant.EPOCH, ownership, List.of(), true);
        var reordered = new PublishedReactions(new ObservedReactions("observer-v2",
                observations.interfaces().reversed(), observations.reactions().reversed(),
                observations.triggers().reversed(), observations.actions().reversed(), List.of()),
                Instant.EPOCH, ownership.reversed(), List.of(), true);
        var before = new ReactionDocumentation(snapshot, model).ofSystem(system).system();
        var after = new ReactionDocumentation(reordered, model).ofSystem(system).system();

        assertThat(before.components()).hasSize(2);
        assertThat(before.messages()).hasSize(2);
        assertThat(before.restProviders()).hasSize(2);
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(render(after, model)).isEqualTo(render(before, model));
    }

    private static String render(ReactionView view, ArchitectureModel model) {
        var context = new GenerationContext(model, "dev", null, Instant.EPOCH, Instant.EPOCH,
                new DiagramLimits(100, 4, 40, 100, 200, 40, 20, 1000), "/site/docs/dev/");
        var page = new MarkdownWriter();
        Arc42ReactionPages.writeViewer(page, new ReactionEntry(ReactionEntry.Kind.SYSTEM, "s", null, null, null), view, context, null);
        Arc42ReactionPages.writeTableAndProvenance(page, view, context);
        return page.text();
    }

    private static DocumentedSystem system(String name, List<DocumentedComponent> components) {
        return new DocumentedSystem(name, name, null, List.of(), null, components, List.of(), List.of());
    }

    private static DocumentedComponent component(String name, boolean rest) {
        return new DocumentedComponent(name, name, null, ComponentType.SELF_CONTAINED_SYSTEM, null, null, null,
                rest ? List.of(new RestApiOperation("GET", "/items")) : List.of(), null, null, null);
    }
}
