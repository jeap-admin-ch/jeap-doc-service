package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;

import java.util.ArrayList;
import java.util.List;

/**
 * What every page carrying a reaction graph shows: the diagram, the table, and where the reactions came from.
 * <p>
 * <b>A diagram is never the only representation.</b> The plugin makes the text of a zoomable diagram
 * unselectable, hides its minimap from assistive technology on purpose, and refuses a source over its size
 * limit with an explanation instead of a picture - so the table below it is the complete list, the one the
 * browser's own find-in-page searches, and what is left of the page in the case the plugin refuses to draw.
 * <p>
 * <b>And every such page names its own source.</b> The reactions come from the reaction observer on an import
 * schedule of its own, which is a different age from the model's - and on a message page, whose front matter
 * names the model, this section is the only place a reader can be told.
 */
final class Arc42ReactionPages {

    private Arc42ReactionPages() {
    }

    /**
     * The diagram, the table and the provenance of one reaction graph, on a page that carries one.
     *
     * @param componentOnItsOwnPage the component whose page this is, or null on a system's or a message's -
     *                              its own reactions are drawn without a link to where the reader already is
     */
    static void write(MarkdownWriter page, ReactionView view, GenerationContext context,
                      String componentOnItsOwnPage) {
        write(page, view, context, componentOnItsOwnPage, "");
    }

    /**
     * The same, on a page that carries several.
     *
     * @param idPrefix what this graph's node ids are prefixed with, so that two graphs on one page do not
     *                 give the browser two elements under one id
     */
    static void write(MarkdownWriter page, ReactionView view, GenerationContext context,
                      String componentOnItsOwnPage, String idPrefix) {
        page.fence(GraphVizViews.LANGUAGE,
                GraphVizViews.reactions(view, context, componentOnItsOwnPage, idPrefix));
        table(page, view);
        provenance(page, context);
    }

    /** One row per reaction: what triggered it, who reacted, what it published in answer, how often. */
    private static void table(MarkdownWriter page, ReactionView view) {
        if (view.rows().isEmpty()) {
            return;
        }
        List<List<Markdown>> rows = new ArrayList<>();
        for (ReactionView.Row row : view.rows()) {
            rows.add(List.of(
                    row.trigger() == null ? Md.italic("no trigger observed") : Md.code(row.trigger()),
                    Md.code(row.component()),
                    row.answers().isEmpty() ? Md.italic("nothing observed")
                            : Md.text(String.join(", ", row.answers())),
                    row.median() == null ? Md.italic("unknown") : Md.text(Integer.toString(row.median()))));
        }
        page.table(List.of("Triggered by", "Component", "Publishes in answer", "Times observed"), rows);
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
