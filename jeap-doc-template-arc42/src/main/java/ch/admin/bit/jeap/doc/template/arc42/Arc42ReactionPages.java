package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;
import ch.admin.bit.jeap.doc.markdown.ReactionEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.RUNTIME_VIEW;

/**
 * What every page carrying a reaction graph shows: the diagram, the table, and where the reactions came from.
 * <p>
 * <b>A diagram is never the only representation.</b> The table below it is selectable, searchable with
 * the browser's find-in-page and available even when the full snapshot cannot be loaded or drawn.
 * <p>
 * <b>And every such page names its own source.</b> The reactions come from the reaction observer on an import
 * schedule of its own, which is a different age from the model's - and on a message page, whose front matter
 * names the model, this section is the only place a reader can be told.
 */
final class Arc42ReactionPages {

    private Arc42ReactionPages() {
    }

    /**
     * Writes inert viewer metadata with documentation links for this entry.
     *
     * @param componentOnItsOwnPage the current component, or null, used to omit links back to the same page
     */
    static void writeViewer(MarkdownWriter page, ReactionEntry entry, ReactionView view,
                            GenerationContext context, String componentOnItsOwnPage) {
        var links = view.components().stream()
                .flatMap(component -> componentLink(component, context, componentOnItsOwnPage)
                        .map(href -> new ReactionEntry.ComponentLink(component.system(), component.component(), href)).stream())
                .distinct().toList();
        page.reactionViewer(entry.withComponentLinks(links).withInterfaceLinks(interfaceLinks(view, context)));
    }

    static List<ReactionEntry.InterfaceLink> interfaceLinks(ReactionView view, GenerationContext context) {
        var links = new ArrayList<ReactionEntry.InterfaceLink>();
        view.messages().stream().flatMap(message -> messageLink(message, context)
                .map(href -> new ReactionEntry.InterfaceLink(message.kind().name(), message.name(), href)).stream())
                .distinct().forEach(links::add);
        for (var provider : view.restProviders()) {
            String path = DocumentationPaths.componentPaths(provider.systemSlug(), Arc42Template.SYSTEM_SEGMENT,
                    BUILDING_BLOCK_VIEW, provider.componentSlug(), Arc42Template.COMPONENT_SEGMENT)
                    .page(BUILDING_BLOCK_VIEW, Arc42Template.REST_API_PAGE);
            links.add(new ReactionEntry.InterfaceLink("REST", provider.provider(), context.diagramLink(path)));
        }
        return List.copyOf(links);
    }

    private static Optional<String> messageLink(ReactionView.MessageLink message, GenerationContext context) {
        String group = message.kind() == MessageKind.COMMAND
                ? Arc42MessagePages.COMMANDS
                : Arc42MessagePages.EVENTS;
        String page = context.diagramLink(DocumentationPaths.page(message.systemSlug(),
                Arc42Template.SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW, group, message.messageSlug()));
        return Optional.of(page);
    }

    static Optional<String> componentLink(ReactionView.ComponentLink group, GenerationContext context,
                                          String componentOnItsOwnPage) {
        if (group.component().equalsIgnoreCase(componentOnItsOwnPage)) {
            return Optional.empty();
        }
        String page = DocumentationPaths.componentPaths(group.systemSlug(), Arc42Template.SYSTEM_SEGMENT,
                        BUILDING_BLOCK_VIEW, group.componentSlug(), Arc42Template.COMPONENT_SEGMENT)
                .page(RUNTIME_VIEW, Arc42Template.COMPONENT_REACTIONS_PAGE);
        return Optional.of(context.diagramLink(page));
    }

    /** Writes the static fallback table; the browser replaces it with live rows after the graph renders. */
    static void writeTableAndProvenance(MarkdownWriter page, ReactionView view, GenerationContext context) {
        table(page, view);
        provenance(page, context);
    }

    /**
     * One row per reaction: what triggered it, who reacted, what it published in answer, how often.
     * <p>
     * <b>The last column says what the number is.</b> It is the observer's median of the daily counts, not a
     * total, and a column called <i>Times observed</i> was read as one.
     */
    private static void table(MarkdownWriter page, ReactionView view) {
        if (view.rows().isEmpty()) {
            return;
        }
        List<List<Markdown>> rows = view.rows().stream()
                .map(row -> List.of(
                    row.trigger() == null ? Md.text("-") : Md.code(row.trigger()),
                    Md.code(row.component()),
                    Md.text(row.answers().isEmpty() ? "-" : String.join(", ", row.answers())),
                    row.median() == null ? Md.italic("unknown") : Md.text(row.median().stripTrailingZeros().toPlainString())))
                .toList();
        page.table(List.of("Trigger", "Component", "Action", "Median per day"), rows);
    }

    /**
     * Where the picture came from, and how old it is. The wording says <b>observed</b> rather than
     * <i>documented</i> on purpose: a reaction is on this page because it happened, not because somebody
     * described it, and the absence of one means nothing was seen rather than that nothing can happen.
     */
    private static void provenance(MarkdownWriter page, GenerationContext context) {
        String importedAt = context.reactionsImportedAtDisplay();
        page.paragraph(importedAt.isEmpty()
                ? Md.sentence("Observed at runtime by the reaction observer of the {} environment.",
                        Md.code(context.environment()))
                : Md.sentence("Observed at runtime by the reaction observer of the {} environment, and "
                              + "imported {}. Only what was seen within its statistics window is drawn.",
                        Md.code(context.environment()), Md.text(importedAt)));
    }
}
