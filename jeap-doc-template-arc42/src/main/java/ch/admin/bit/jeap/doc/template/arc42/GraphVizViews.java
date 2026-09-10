package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.RUNTIME_VIEW;

/**
 * The syntax of a reaction graph: GraphViz, rendered in the reader's browser by the site's diagram plugin.
 * <p>
 * The counterpart of {@link PlantUmlViews}, and the same split: <b>what is drawn is decided in the domain</b>
 * ({@link ReactionView}), and nothing outside this class writes DOT.
 * <p>
 * Three things about the source are load-bearing, and each of them is a defect that only shows in a browser:
 * <ul>
 *   <li><b>Every node carries an explicit {@code id}.</b> It is what a deep link addresses - the plugin
 *       resolves {@code #graph?highlight-node=…} by SVG id first - so a link never lands on a node that merely
 *       happens to contain the same text.</li>
 *   <li><b>A link inside a fence is not rewritten by anything.</b> A remark plugin adds the environment prefix
 *       to a Markdown link and Docusaurus adds the base URL; neither looks inside a fence, so every
 *       {@code URL} goes through {@link GenerationContext#diagramLink}.</li>
 *   <li><b>Nothing is filled, and the one colour set is a stroke.</b> The plugin's dark mode
 *       retargets Graphviz's black defaults at the page's text colour, and gives a linked node's
 *       text the theme's link colour - both of which are <i>light</i> in dark mode, and a CSS rule
 *       beats any font colour written here. So a light fill is a node whose label cannot be read in
 *       dark mode, whatever else is done about it. A message of another system is marked with a blue
 *       outline instead: a stroke sits on the page's background rather than under its text, and blue
 *       reads on both.</li>
 * </ul>
 */
final class GraphVizViews {

    /** The fence's language, which is what the site's diagram plugin renders. */
    static final String LANGUAGE = "dot";

    /**
     * What a message of another system is outlined in - a fact about the node, not a decoration.
     * <p>
     * A mid blue, which carries enough contrast against a white page and against a dark one, because the site
     * is read in both. <b>Not a fill</b>: see the class documentation.
     */
    private static final String OF_ANOTHER_SYSTEM = "#4a90d9";

    private GraphVizViews() {
    }

    /**
     * One reaction graph, with every node linked to the page that documents it <b>where this run writes
     * one</b>.
     *
     * @param componentOnItsOwnPage the component whose page this is, or null on a system's or a message's.
     *                              Its own reactions are drawn without a link: a link to the page the reader
     *                              is already on is worse than a node that is not a link
     * @param idPrefix              what the node ids of this graph are prefixed with, or {@code ""} on a page
     *                              that carries one graph. <b>A node id becomes a DOM id</b>, and a message
     *                              type with variants draws a diagram per variant on one page - the same
     *                              reaction is in several of them, and a deep link into a duplicated id lands
     *                              on whichever the browser finds first
     */
    static String reactions(ReactionView view, GenerationContext context, String componentOnItsOwnPage,
                            String idPrefix) {
        String prefix = idPrefix == null ? "" : idPrefix;
        StringBuilder dot = new StringBuilder("digraph ").append(quoted(prefix + "reactions")).append(" {\n")
                .append("  rankdir=LR\n")
                .append("  node [shape=box style=rounded]\n");
        Map<Long, String> messageIds = new LinkedHashMap<>();
        for (ReactionView.MessageNode message : view.messages()) {
            String id = prefix + "MESSAGE-" + message.id();
            messageIds.put(message.id(), id);
            dot.append("  ").append(quoted(id)).append(" [id=").append(quoted(id))
                    .append(" shape=ellipse label=").append(quoted(labelOf(message)));
            if (message.ofAnotherSystem()) {
                dot.append(" color=").append(quoted(OF_ANOTHER_SYSTEM)).append(" penwidth=2");
            }
            linkOfMessage(message, context).ifPresent(link ->
                    dot.append(" URL=").append(quoted(link)).append(" target=").append(quoted("_self")));
            dot.append("]\n");
        }
        Map<Long, String> reactionIds = new LinkedHashMap<>();
        int cluster = 0;
        for (ReactionView.ReactionCluster group : view.clusters()) {
            // A dashed box per component and trigger. The colour is left to the plugin, which is what makes
            // the label readable in both themes.
            dot.append("  subgraph ").append("cluster_").append(cluster++).append(" {\n")
                    .append("    label=").append(quoted(group.label())).append('\n')
                    .append("    style=").append(quoted("rounded,dashed")).append('\n');
            for (ReactionView.ReactionNode reaction : group.reactions()) {
                String id = prefix + "REACTION-" + reaction.id();
                reactionIds.put(reaction.id(), id);
                dot.append("    ").append(quoted(id)).append(" [id=").append(quoted(id))
                        .append(" label=").append(quoted(reaction.label()));
                linkOfReaction(group, reaction, context, componentOnItsOwnPage).ifPresent(link ->
                        dot.append(" URL=").append(quoted(link)).append(" target=").append(quoted("_self")));
                dot.append("]\n");
            }
            dot.append("  }\n");
        }
        // Both ends of an edge have to be a node that was drawn. DOT invents a node it has not seen -
        // outside every cluster, labelled with its own id - so an edge to one end that is missing is worse
        // than no edge: it is a box no reader can interpret.
        for (ReactionView.TriggerEdge trigger : view.triggers()) {
            String message = messageIds.get(trigger.messageId());
            String reaction = reactionIds.get(trigger.reactionId());
            if (message == null || reaction == null) {
                continue;
            }
            dot.append("  ").append(quoted(message)).append(" -> ").append(quoted(reaction))
                    .append(" [penwidth=").append(trigger.weight()).append("]\n");
        }
        for (ReactionView.ActionEdge action : view.actions()) {
            String message = messageIds.get(action.messageId());
            String reaction = reactionIds.get(action.reactionId());
            if (message == null || reaction == null) {
                continue;
            }
            dot.append("  ").append(quoted(reaction)).append(" -> ").append(quoted(message)).append('\n');
        }
        return dot.append('}').toString();
    }

    /** The message type, and its variant on a second line where it has one. */
    private static String labelOf(ReactionView.MessageNode message) {
        return message.variant() == null || message.variant().isEmpty()
                ? message.label()
                : message.label() + "\n[" + message.variant() + "]";
    }

    /**
     * The page of a message, where this run writes one. A message of a system this site does not carry is
     * drawn and not linked: the plugin would hand the path to the browser and the reader would get a
     * {@code 404} from the server.
     */
    private static Optional<String> linkOfMessage(ReactionView.MessageNode message,
                                                  GenerationContext context) {
        if (!message.isLinkable()) {
            return Optional.empty();
        }
        String group = message.kind() == MessageKind.COMMAND
                ? Arc42MessagePages.COMMANDS
                : Arc42MessagePages.EVENTS;
        return Optional.of(context.diagramLink(DocumentationPaths.page(message.systemSlug(),
                Arc42Template.SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW, group, message.messageSlug())));
    }

    /**
     * The component's own runtime view, <b>focused on this reaction</b>. The hash is what the plugin watches:
     * it scrolls to the diagram, highlights the node and centres it.
     * <p>
     * Three things have to be true before a link is written, and the second one is why this method takes the
     * whole run rather than the cluster alone:
     * <ul>
     *   <li>the landscape documents the component, so there is a tree to point into;</li>
     *   <li><b>the environment has a graph of that component</b>, because that is what makes chapter 6 be
     *       written for it - a component the observer saw reacting may have none of its own. Asked of the
     *       environment and not of the system being written: a reaction on a system's graph is regularly a
     *       component of another system, whose page another pass of this run writes. A URL inside a fence is
     *       not checked by anything: Docusaurus verifies Markdown links and never looks in a code block, so a
     *       link to a page nobody wrote builds cleanly and answers 404 to the reader who follows it;</li>
     *   <li>and it is not the page the reader is on already.</li>
     * </ul>
     */
    private static Optional<String> linkOfReaction(ReactionView.ReactionCluster group,
                                                   ReactionView.ReactionNode reaction,
                                                   GenerationContext context,
                                                   String componentOnItsOwnPage) {
        if (!group.isLinkable()
            || !context.reactions().hasGraphOfComponent(group.component())
            || group.component().equalsIgnoreCase(componentOnItsOwnPage)) {
            return Optional.empty();
        }
        String page = DocumentationPaths.componentPaths(group.systemSlug(), Arc42Template.SYSTEM_SEGMENT,
                        BUILDING_BLOCK_VIEW, group.componentSlug(), Arc42Template.COMPONENT_SEGMENT)
                .page(RUNTIME_VIEW, Arc42Template.COMPONENT_REACTIONS_PAGE);
        // Unprefixed, because the node addressed is on the component's own page and that page carries one
        // graph. The prefix belongs to the page a graph is drawn on, not to the reaction.
        return Optional.of(context.diagramLink(page) + "#graph?highlight-node=REACTION-" + reaction.id());
    }

    /**
     * A DOT string literal: everything a name can contain that DOT reads as syntax, escaped. A label with a
     * second line arrives with a real line break in it and leaves as {@code \n}, which is what DOT reads as
     * one.
     * <p>
     * Null is the empty literal rather than an exception. {@link ReactionView} drops the nodes that have no
     * name, so nothing should arrive here without one - and a graph that slips through is a node without a
     * label rather than a site build that fails.
     */
    private static String quoted(String value) {
        if (value == null) {
            return "\"\"";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
