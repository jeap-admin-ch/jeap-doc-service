package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a view into PlantUML.
 * <p>
 * The shape of a diagram - which boxes and which arrows - is decided in the domain by {@code SystemContext}
 * and {@code WhiteboxView}. Only the syntax is here, and it belongs to arc42.
 * <p>
 * Nothing is rendered to an image. The output is the diagram source, put on the page inside a fence, and the
 * site's plugin renders it in the reader's browser. The diagram therefore stays searchable and readable as
 * text.
 * <p>
 * <b>The direction is a property of the view, not of its size.</b> The context view is a star - one system
 * with its neighbours around it, always two ranks - and {@code left to right direction} turns that into a
 * narrow column. A whitebox view is a deep graph of components calling components, and is narrower top to
 * bottom, which is the direction PlantUML lays out by default. Measured over six real systems, the number of
 * boxes does not predict which is better; the shape of the view does, and each view's shape is fixed by what
 * it is.
 * <p>
 * <b>A label is capped.</b> The engine lays a label out by recursion and overflows the browser's stack at
 * about sixty lines, so an arrow carrying every message type of a busy system is not a large diagram but no
 * diagram at all. Above {@link GenerationContext#maxEdgeLabels()} names an arrow shows their count, and the
 * page's table names every one of them.
 */
final class PlantUmlViews {

    /** The fence language, which the site's diagram plugin picks up. */
    static final String LANGUAGE = "plantuml";

    private static final String END_UML = "@enduml";

    /**
     * Tight spacing. It costs nothing - no label and no box is dropped - and takes 3.7x off the area of the
     * largest diagram in the landscape.
     * <p>
     * Only these two. {@code skinparam componentStyle rectangle} prints <i>"Please use CSS style instead of
     * skinparam"</i> as a text element <b>inside</b> the rendered diagram; these two do not. Any further
     * {@code skinparam} is checked the same way, by looking at the rendered elements, before it ships.
     */
    private static final String SPACING = "skinparam nodesep 8\nskinparam ranksep 20\n";

    private PlantUmlViews() {
    }

    /**
     * One diagram, and whether it had to summarize a label.
     * <p>
     * <b>Nothing writes the second one onto a page any more</b>: the note saying so was dropped, because an
     * arrow reading {@code 5 Events} is self-evidently a count and the table below it names every one of them.
     * It is kept because it is a property of the diagram this class alone can answer - deriving it a second
     * time would mean walking the same edges again - and because a page that wants to say something about a
     * truncated label has no other way of knowing. Its test is what keeps it honest.
     *
     * @param source           the PlantUML, ready to be fenced
     * @param labelsSummarized whether at least one arrow shows a count instead of the names
     */
    record Diagram(String source, boolean labelsSummarized) {
    }

    /** The system in the middle, its neighbours around it, one arrow per kind and direction. */
    static Diagram contextView(SystemContext context, GenerationContext generation) {
        StringBuilder uml = new StringBuilder("@startuml\nleft to right direction\n").append(SPACING);
        Aliases aliases = new Aliases();
        component(uml, aliases, context.system().name(), systemLinkOf(context.system().name(), generation), true);
        for (String neighbour : context.drawn()) {
            component(uml, aliases, neighbour, systemLinkOf(neighbour, generation), false);
        }
        // Only the edges between boxes the diagram has. The page's table carries every one of them.
        boolean summarized = false;
        for (SystemContext.ContextEdge edge : context.edges()) {
            if (isDrawn(context, edge.from()) && isDrawn(context, edge.to())) {
                summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                        generation.maxEdgeLabels());
            }
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    private static boolean isDrawn(SystemContext context, String name) {
        return name.equalsIgnoreCase(context.system().name())
               || context.drawn().stream().anyMatch(drawn -> drawn.equalsIgnoreCase(name));
    }

    /**
     * The components inside the system and nothing else: the decomposition on its own.
     * <p>
     * It is the diagram a reader of a large system uses. What the system exchanges with the outside is the
     * subject of the context view, and of {@link #whiteboxView} next to this one.
     */
    static Diagram internalView(WhiteboxView view, String systemSlug, GenerationContext generation) {
        StringBuilder uml = new StringBuilder("@startuml\n").append(SPACING);
        Aliases aliases = new Aliases();
        systemPackage(uml, aliases, view, systemSlug, generation);
        boolean summarized = false;
        for (WhiteboxView.Edge edge : view.internal()) {
            summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                    generation.maxEdgeLabels());
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    /** The components inside the system, and every other system as a single box outside it. */
    static Diagram whiteboxView(WhiteboxView view, String systemSlug, GenerationContext generation) {
        StringBuilder uml = new StringBuilder("@startuml\n").append(SPACING);
        Aliases aliases = new Aliases();
        systemPackage(uml, aliases, view, systemSlug, generation);
        for (String neighbour : view.drawnNeighbours()) {
            component(uml, aliases, neighbour, systemLinkOf(neighbour, generation), false);
        }
        boolean summarized = false;
        for (WhiteboxView.Edge edge : view.internal()) {
            summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                    generation.maxEdgeLabels());
        }
        for (WhiteboxView.Edge edge : view.external()) {
            // An arrow to a neighbour the diagram left out would point at nothing; the table still lists it.
            if (view.isDrawnNeighbour(view.neighbourOf(edge))) {
                summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                        generation.maxEdgeLabels());
            }
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    /** The package of the system, with a box per component. Every component, whatever their number. */
    private static void systemPackage(StringBuilder uml, Aliases aliases, WhiteboxView view, String systemSlug,
                                      GenerationContext generation) {
        uml.append("package ").append(quoted(view.system().name())).append(" {\n");
        for (DocumentedComponent component : view.components()) {
            uml.append("  ");
            // Not bolded: inside the package box every component is the subject, so bolding all of them says
            // nothing. What each box carries instead is a link to its own page.
            component(uml, aliases, component.name(),
                    generation.diagramLink(DocumentationPaths.component(systemSlug,
                            Arc42Template.SYSTEM_SEGMENT, Arc42Chapters.BUILDING_BLOCK_VIEW,
                            component.slug())), false);
        }
        uml.append("}\n");
    }

    private static void component(StringBuilder uml, Aliases aliases, String name, String linkTarget,
                                  boolean focused) {
        uml.append("component ").append(quoted(name)).append(" as ").append(aliases.of(name));
        if (isSafeLinkTarget(linkTarget)) {
            // The box is a link to the page of what it draws, which the Confluence version could not do.
            uml.append(" [[").append(linkTarget).append("]]");
        }
        // After the link, not before it: PlantUML reads a colour as the end of the declaration, and a link
        // following one is a syntax error - which renders as an error box and fails no build.
        if (focused) {
            uml.append(" #line.bold");
        }
        uml.append('\n');
    }

    /**
     * Whether a link target may go inside a fence. A target is built from a slug and constants, so a bracket
     * or a space in it is a defect rather than a value to render.
     */
    private static boolean isSafeLinkTarget(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '[' || c == ']' || c == '"' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One arrow, and whether its names had to be replaced by their count.
     * <p>
     * This is the one method every arrow of every diagram goes through, which is why the cap lives here: a
     * view added later is capped by construction rather than by remembering to.
     */
    private static boolean arrow(StringBuilder uml, Aliases aliases, String from, String to, RelationKind kind,
                                 List<String> labels, int maxLabels) {
        uml.append(aliases.of(from)).append(' ').append(arrowOf(kind)).append(' ').append(aliases.of(to));
        boolean summarized = labels.size() > Math.max(maxLabels, 0);
        if (!labels.isEmpty()) {
            // A count is digits and a word, so it cannot break out of the label the way a name could.
            uml.append(" : ").append(summarized
                    ? kind.count(labels.size())
                    : escaped(String.join("\\n", labels)));
        }
        uml.append('\n');
        return summarized;
    }

    /** A REST call is dotted and a message is solid, so the two are told apart without reading the labels. */
    private static String arrowOf(RelationKind kind) {
        return kind == RelationKind.REST_API ? "..>" : "-->";
    }

    /**
     * The identifiers of one diagram.
     * <p>
     * PlantUML needs an identifier per box, and a name is not one: a hyphen reads as part of an arrow, so the
     * readable name goes in the quoted label and an identifier goes on the line.
     * <p>
     * Every character that is not a letter or a digit becomes an underscore, which two different names can
     * collapse into - {@code orders-intake} and {@code orders_intake}. PlantUML reads the second declaration
     * as a redefinition of the first, so one box would swallow the other and take all of its arrows. Handing
     * out the identifiers from one place per diagram is what keeps them apart.
     * <p>
     * Names are matched without regard to case, which is how the edges are de-duplicated too.
     */
    private static final class Aliases {

        private final Map<String, String> byName = new HashMap<>();
        private final Set<String> taken = new HashSet<>();

        String of(String name) {
            return byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> unique(baseOf(name)));
        }

        private String unique(String base) {
            String candidate = base;
            int suffix = 1;
            while (!taken.add(candidate)) {
                suffix++;
                candidate = base + "_" + suffix;
            }
            return candidate;
        }

        private static String baseOf(String name) {
            StringBuilder alias = new StringBuilder("c_");
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                alias.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
            }
            return alias.toString();
        }
    }

    /**
     * PlantUML escaping, which is not Markdown escaping.
     * <p>
     * A quote ends a label early and a newline ends the statement. Square brackets go too: PlantUML reads
     * {@code [[...]]} as a link <b>inside a label as well as outside one</b>, so a name containing them would
     * put a link of somebody else's choosing on the diagram. A diagram that fails to parse renders as an error
     * box, and the build does not notice either way.
     */
    private static String escaped(String value) {
        return value.replace("\"", "'")
                .replace("[", "(")
                .replace("]", ")")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /**
     * Where a system's page is, or null when this run does not document it.
     * <p>
     * The name is resolved through the model rather than lower-cased into a path. A neighbour's name comes
     * from a relation and is free text: lower-casing it could point at a page that does not exist, and a name
     * containing {@code ]]} would close the link early and add a second, arbitrary one.
     * <p>
     * The fence is the one place the escaping in {@code Md} cannot help, because nothing inside it is
     * Markdown.
     */
    private static String systemLinkOf(String system, GenerationContext generation) {
        return generation.model().systems().stream()
                .filter(documented -> documented.name().equalsIgnoreCase(system))
                .findFirst()
                .map(documented -> generation.diagramLink(DocumentationPaths.system(documented.slug())))
                .orElse(null);
    }

    private static String quoted(String value) {
        return "\"" + escaped(value) + "\"";
    }
}
