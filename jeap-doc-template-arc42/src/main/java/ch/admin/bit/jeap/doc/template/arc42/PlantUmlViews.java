package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSchema;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentContext;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
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
 * The shape of a diagram - which boxes and which arrows - is decided in the domain by {@code SystemContext},
 * {@code WhiteboxView}, {@code ComponentContext} and {@code DocumentedSchema}. Only the syntax is here, and it
 * belongs to arc42.
 * <p>
 * Nothing is rendered to an image. The output is the diagram source, put on the page inside a fence, and the
 * site's plugin renders it in the reader's browser. The diagram therefore stays searchable and readable as
 * text.
 * <p>
 * <b>The direction is a property of the view, not of its size.</b> A context view is a star - one system, or
 * one component, with its neighbours around it, always two ranks - and {@code left to right direction} turns
 * that into a narrow column. A whitebox view is a deep graph of components calling components, and is narrower
 * top to bottom, which is the direction PlantUML lays out by default. An entity relationship diagram keeps
 * that default too: its boxes are tall, one row per column of the table, so laying them out side by side is
 * what would not fit. Measured over six real systems, the number of boxes does not predict which is better;
 * the shape of the view does, and each view's shape is fixed by what it is.
 * <p>
 * <b>A label is capped.</b> The engine lays a label out by recursion and overflows the browser's stack at
 * about sixty lines, so an arrow carrying every message type of a busy system is not a large diagram but no
 * diagram at all. Above {@link DiagramLimits#maxEdgeLabels()} names an arrow shows their count, and the
 * page's table names every one of them.
 */
final class PlantUmlViews {

    /** The fence language, which the site's diagram plugin picks up. */
    static final String LANGUAGE = "plantuml";

    private static final String START_UML = "@startuml\n";

    /** The layout a star-shaped view is laid out with - see the class comment. */
    private static final String LEFT_TO_RIGHT = "left to right direction\n";

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

    /**
     * What the box of the subject carries: the current system on a system context view, the current component
     * on a component context view. Gold fill and a bold outline, so the middle of a star is found without
     * reading a label.
     * <p>
     * <b>Element syntax rather than a {@code skinparam}</b>, which is what keeps it out of the two the class
     * may emit - and out of the warning one of those would print into the picture.
     * <p>
     * <b>The same colour in dark mode.</b> The site's plugin re-renders a diagram with the engine's dark flag
     * when the reader switches, which changes PlantUML's <i>own</i> palette - but never a colour written into
     * the source. This one is written into the source deliberately: the subject is the subject in either mode.
     */
    private static final String SUBJECT_STYLE = " #Gold;line.bold";

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
        StringBuilder uml = new StringBuilder(START_UML).append(LEFT_TO_RIGHT).append(SPACING);
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
                        generation.limits().maxEdgeLabels());
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
        StringBuilder uml = new StringBuilder(START_UML).append(SPACING);
        Aliases aliases = new Aliases();
        systemPackage(uml, aliases, view, systemSlug, generation);
        boolean summarized = false;
        for (WhiteboxView.Edge edge : view.internal()) {
            summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                    generation.limits().maxEdgeLabels());
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    /** The components inside the system, and every other system as a single box outside it. */
    static Diagram whiteboxView(WhiteboxView view, String systemSlug, GenerationContext generation) {
        StringBuilder uml = new StringBuilder(START_UML).append(SPACING);
        Aliases aliases = new Aliases();
        systemPackage(uml, aliases, view, systemSlug, generation);
        for (String neighbour : view.drawnNeighbours()) {
            component(uml, aliases, neighbour, systemLinkOf(neighbour, generation), false);
        }
        boolean summarized = false;
        for (WhiteboxView.Edge edge : view.internal()) {
            summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                    generation.limits().maxEdgeLabels());
        }
        for (WhiteboxView.Edge edge : view.external()) {
            // An arrow to a neighbour the diagram left out would point at nothing; the table still lists it.
            if (view.isDrawnNeighbour(view.neighbourOf(edge))) {
                summarized |= arrow(uml, aliases, edge.from(), edge.to(), edge.kind(), edge.labels(),
                        generation.limits().maxEdgeLabels());
            }
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    /**
     * One component in the middle, and the components it exchanges something with - the siblings inside its
     * own system's package, the counterparts of other systems inside a package for the system that owns each.
     * <p>
     * <b>A neighbour whose counterpart component the model does not name is one box</b>, and so is one the
     * bound on the boxes left no room to open. Which of the two a neighbour is, is
     * {@link ComponentContext}'s decision; this draws it.
     * <p>
     * <b>The boxes are aliased by identity, not by name.</b> Two systems may each have a component called
     * {@code gateway}, and two boxes sharing an alias would be one box carrying the arrows of both.
     * <p>
     * Laid out left to right, like the system context view: the shape is the same star of one box with its
     * counterparts around it.
     */
    static Diagram componentContextView(ComponentContext context, GenerationContext generation) {
        StringBuilder uml = new StringBuilder(START_UML).append(LEFT_TO_RIGHT).append(SPACING);
        Aliases aliases = new Aliases();
        for (ComponentContext.DrawnSystem drawn : context.drawnSystemsWithComponents()) {
            // The package carries the link to the system's page, which the box for a whole system used to.
            // Opening a neighbour must not cost the reader the way into its own documentation.
            //
            // And it carries an alias, because an arrow may land on it: a relation that names a neighbour and
            // none of its components points at the system, and the system is this package. Without the alias
            // PlantUML would read that arrow's end as a new box of its own.
            uml.append("package ").append(quoted(drawn.name()))
                    .append(" as ").append(aliases.of(drawn.asNode()));
            String systemLink = drawn.slug() == null ? null
                    : generation.diagramLink(DocumentationPaths.system(drawn.slug()));
            if (isSafeLinkTarget(systemLink)) {
                uml.append(" [[").append(systemLink).append("]]");
            }
            uml.append(" {\n");
            for (ComponentContext.Node node : drawn.components()) {
                uml.append("  ");
                component(uml, aliases.of(node), node.label(), linkOf(node, generation),
                        node.kind() == ComponentContext.NodeKind.SELF);
            }
            uml.append("}\n");
        }
        for (ComponentContext.Node whole : context.drawnWholeSystems()) {
            component(uml, aliases.of(whole), whole.label(), linkOf(whole, generation), false);
        }
        boolean summarized = false;
        // Every arrow the view kept: it has already dropped the ones with no box to point at, and merged the
        // ones that land on the same pair of boxes.
        for (ComponentContext.Edge arrow : context.arrows()) {
            summarized |= arrow(uml, aliases.of(arrow.from()), aliases.of(arrow.to()), arrow.kind(),
                    arrow.labels(), generation.limits().maxEdgeLabels());
        }
        return new Diagram(uml.append(END_UML).toString(), summarized);
    }

    /**
     * Where the page of one box is, or null where this run does not document it.
     * <p>
     * The slugs are the view's, resolved from the model while it was computed - a template reads a slug and
     * never derives a path segment. A box the model documents no page for carries no link at all, which is
     * the same answer a neighbour whose name is not in the model has always got.
     */
    private static String linkOf(ComponentContext.Node node, GenerationContext generation) {
        if (node.systemSlug() == null) {
            return null;
        }
        if (node.isSystem()) {
            return generation.diagramLink(DocumentationPaths.system(node.systemSlug()));
        }
        return node.componentSlug() == null ? null
                : componentLinkOf(node.systemSlug(), node.componentSlug(), generation);
    }

    /**
     * The entity relationship diagram of one database schema: an entity per table, its primary key columns
     * above the separator, and one arrow per foreign key.
     * <p>
     * <b>Bounded by the number of tables</b>, like every other diagram here. What it leaves out is in the
     * list of tables on the page below it.
     * <p>
     * An arrow is only drawn between two tables the diagram has. A foreign key into a table that was cut, or
     * into a machinery table, would point at nothing - and an arrow that reaches one is drawn with the
     * <b>table's</b> spelling of its name rather than the foreign key's, which need not agree with it.
     * <b>A key into a shard finds the family that shard was collapsed into</b>, or collapsing a schema would
     * drop every arrow in it; both are {@link DocumentedSchema#drawnEntityNameOf}'s doing.
     */
    static Diagram databaseSchema(DocumentedSchema schema) {
        List<SchemaTable> drawn = schema.drawn();
        StringBuilder uml = new StringBuilder(START_UML).append(SPACING);
        for (SchemaTable table : drawn) {
            entity(uml, table);
        }
        for (SchemaTable table : drawn) {
            for (SchemaForeignKey key : table.foreignKeys()) {
                String referenced = schema.drawnEntityNameOf(key.referencedTableName());
                if (referenced != null) {
                    uml.append(quoted(table.name())).append(" }o--|| ").append(quoted(referenced));
                    if (!key.columnNames().isEmpty()) {
                        uml.append(" : ").append(escaped(String.join(", ", key.columnNames())));
                    }
                    uml.append('\n');
                }
            }
        }
        // An arrow here carries column names, never a list that could need summarizing.
        return new Diagram(uml.append(END_UML).toString(), false);
    }

    /** One table as an entity: the primary key columns, a separator, then the rest. */
    private static void entity(StringBuilder uml, SchemaTable table) {
        List<SchemaColumn> keyColumns = table.keyColumns();
        List<SchemaColumn> otherColumns = table.otherColumns();
        uml.append("entity ").append(quoted(table.name())).append(" {\n");
        for (SchemaColumn column : keyColumns) {
            columnLine(uml, table, column, true);
        }
        if (!keyColumns.isEmpty() && !otherColumns.isEmpty()) {
            uml.append("  --\n");
        }
        for (SchemaColumn column : otherColumns) {
            columnLine(uml, table, column, false);
        }
        uml.append("}\n");
    }

    /**
     * One column: {@code *} where it is not nullable, then its name, its type and the keys it belongs to.
     * <p>
     * <b>No {@code {field}} marker, and none is needed.</b> PlantUML tells a field from a method by whether
     * the line has parentheses, which a column type usually has - {@code numeric(12,2)}, {@code text[]} - but
     * that heuristic is a {@code class} member's and not an {@code entity}'s: every member of an entity is a
     * field, drawn where it was declared. Measured in a browser against the site's own renderer; the
     * assertion that says so is in {@code SiteTemplateBrowserIT}.
     * <p>
     * The name and the type come out of somebody's database, and nothing about Markdown reaches into a fence,
     * so both go through {@link #escaped}.
     * <p>
     * <b>A nullable column's name stands at the start of its line</b>, where PlantUML reads several
     * characters as something else entirely: {@code '} is a comment, so the column would silently vanish from
     * the diagram; {@code --} is a separator; {@code }} ends the entity and takes every column after it with
     * it. So a nullable name that begins with one of them is introduced by the {@code {field}} marker, which
     * says <i>what follows is a field</i> and moves the name off the start of the line. A non-nullable column
     * needs none: the {@code *} in front of it has moved the name already, and the marker is not otherwise
     * needed - see above.
     */
    private static void columnLine(StringBuilder uml, SchemaTable table, SchemaColumn column,
                                   boolean primaryKey) {
        String name = escaped(column.name());
        uml.append(column.nullable() ? "    " : "  * ");
        if (column.nullable() && startsWithALineStartHazard(name)) {
            uml.append("{field} ");
        }
        uml.append(name);
        if (column.type() != null && !column.type().isBlank()) {
            uml.append(" : ").append(escaped(column.type()));
        }
        if (primaryKey) {
            uml.append(" <<PK>>");
        }
        if (table.isForeignKeyColumn(column.name())) {
            uml.append(" <<FK>>");
        }
        uml.append('\n');
    }

    private static String componentLinkOf(String systemSlug, String componentSlug,
                                          GenerationContext generation) {
        return generation.diagramLink(DocumentationPaths.component(systemSlug,
                Arc42Template.SYSTEM_SEGMENT, Arc42Chapters.BUILDING_BLOCK_VIEW, componentSlug));
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
                    componentLinkOf(systemSlug, component.slug(), generation), false);
        }
        uml.append("}\n");
    }

    private static void component(StringBuilder uml, Aliases aliases, String name, String linkTarget,
                                  boolean focused) {
        component(uml, aliases.of(name), name, linkTarget, focused);
    }

    /** The same, for a view whose boxes are told apart by more than their name - see {@link Aliases}. */
    private static void component(StringBuilder uml, String alias, String name, String linkTarget,
                                  boolean focused) {
        uml.append("component ").append(quoted(name)).append(" as ").append(alias);
        if (isSafeLinkTarget(linkTarget)) {
            // The box is a link to the page of what it draws, which the Confluence version could not do.
            uml.append(" [[").append(linkTarget).append("]]");
        }
        // After the link, not before it: PlantUML reads a colour as the end of the declaration, and a link
        // following one is a syntax error - which renders as an error box and fails no build.
        if (focused) {
            uml.append(SUBJECT_STYLE);
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
        return arrow(uml, aliases.of(from), aliases.of(to), kind, labels, maxLabels);
    }

    /** The same, for a view whose boxes are told apart by more than their name - see {@link Aliases}. */
    private static boolean arrow(StringBuilder uml, String fromAlias, String toAlias, RelationKind kind,
                                 List<String> labels, int maxLabels) {
        uml.append(fromAlias).append(' ').append(arrowOf(kind)).append(' ').append(toAlias);
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

    /**
     * The arrow of each kind of relation, as the architecture repository's Confluence pages drew them: an
     * event dashed green, a command dashed blue, a REST call solid blue. A message and a REST call differ by
     * the line as well as the colour, so the colour alone carries no meaning.
     * <p>
     * The style goes <b>inside</b> the arrow, which is where PlantUML takes it. The entity relationship diagram
     * does not use these: its crow's feet are data-model notation.
     */
    private static String arrowOf(RelationKind kind) {
        return switch (kind) {
            case EVENT -> "-[#green,dashed]->";
            case COMMAND -> "-[#blue,dashed]->";
            case REST_API -> "-[#blue]->";
            case OTHER -> "-[#gray]->";
        };
    }

    /** What a page says under a diagram about its arrows. */
    static final String ARROW_LEGEND = "Dashed arrows represent asynchronous messages \u2014 green for events "
                                       + "and blue for commands \u2014 while solid arrows represent "
                                       + "synchronous REST calls.";

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

        private final Map<Object, String> byIdentity = new HashMap<>();
        private final Set<String> taken = new HashSet<>();

        /** A view whose boxes are told apart by their name: a system context view, a whitebox view. */
        String of(String name) {
            return byIdentity.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> unique(baseOf(name)));
        }

        /**
         * A view where two boxes may share a name, so the node's own identity is what tells them apart - two
         * systems may each have a component called {@code gateway}.
         * <p>
         * The two keyspaces cannot be confused: a key record is never equal to a string, so a view keying on
         * names and one keying on nodes cannot hand out the same alias for different things. The collision
         * suffix below is what makes the second {@code gateway} a second box.
         */
        String of(ComponentContext.Node node) {
            return byIdentity.computeIfAbsent(node.key(), ignored -> unique(baseOf(node.label())));
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
     * A quote ends a label early and a newline ends the statement. Doubled square brackets go too: PlantUML
     * reads {@code [[...]]} as a link <b>inside a label as well as outside one</b>, so a name containing them
     * would put a link of somebody else's choosing on the diagram. A diagram that fails to parse renders as
     * an error box, and the build does not notice either way.
     * <p>
     * <b>Only the doubled ones.</b> A single bracket opens no link, and a column type is where this shows:
     * {@code text[]} is an array of text and has to keep saying so. Replacing the pairs left to right leaves
     * no {@code [[} behind - three in a row become {@code (([} - so nothing that could open a link survives.
     * <p>
     * <b>A quote becomes a typographic one and not an apostrophe.</b> An ASCII {@code '} is PlantUML's own
     * line comment, so mapping a quote onto one put the comment introducer into every name that carried a
     * quote - and {@code /'} opens a block comment that runs to the next {@code '/} from anywhere in a line,
     * which is why that pair is broken up here rather than only at the start of a line. Where a name begins
     * with one, {@link #columnLine} is what keeps it off the start of its line.
     */
    private static String escaped(String value) {
        return value.replace("\"", "\u2019")
                .replace("/'", "/(")
                .replace("[[", "((")
                .replace("]]", "))")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /**
     * What PlantUML reads as something other than a name when it stands at the start of a line: its line
     * comment, a separator between an entity's fields, the brace that ends the entity, and the visibility
     * modifiers. A name is not allowed to mean any of them.
     */
    private static final String LINE_START_HAZARDS = "'/-.=_}*+#~";

    /**
     * Whether a name would mean one of those at the start of its line.
     * <p>
     * <b>Read past leading whitespace.</b> What PlantUML reads is the first character of the line that is not
     * blank, and {@link #escaped} deliberately leaves whitespace where it is - so a quoted identifier called
     * {@code " 'total"} reads as a comment and the column vanishes from the diagram, with nothing failing:
     * nothing inside a fence is checked by anything.
     */
    private static boolean startsWithALineStartHazard(String name) {
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!Character.isWhitespace(character)) {
                return LINE_START_HAZARDS.indexOf(character) >= 0;
            }
        }
        return false;
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
