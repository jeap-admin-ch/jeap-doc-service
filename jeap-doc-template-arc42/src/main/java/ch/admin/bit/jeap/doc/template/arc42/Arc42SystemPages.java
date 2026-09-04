package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.markdown.Markdown;
import ch.admin.bit.jeap.doc.markdown.MarkdownWriter;
import ch.admin.bit.jeap.doc.markdown.Md;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static ch.admin.bit.jeap.doc.markdown.MarkdownWriter.NOT_KNOWN;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.CONTEXT_AND_SCOPE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.INTRODUCTION;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.RUNTIME_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.SYSTEM_SEGMENT;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.WHITEBOX_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.CONTEXT_VIEW_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.SYSTEM_REACTIONS_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.GENERATED_CHAPTERS;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.ID;

/**
 * Turns one system into the arc42 pages of its subtree.
 * <p>
 * Split from {@link Arc42Template} because the two are different jobs. That class says what arc42 <i>is</i> -
 * the chapters, the segments, which of them this template generates into - and it is what an upload and the
 * site generator are checked against; this one is the Markdown, which is the larger half by an order of
 * magnitude and changes for entirely different reasons: a heading reworded, a table gaining a column, a
 * sentence that reads better.
 * <p>
 * The template is handed in rather than looked up, because two things about it belong to the structure and are
 * needed here: what the tree is called, and where a chapter goes in the navigation.
 * <p>
 * Static like {@link Arc42MessagePages} and {@link Arc42Pages}: writing a page holds no state, and a bean
 * whose methods never read a field is a bean for the sake of one.
 */
final class Arc42SystemPages {

    private static final String COMPONENTS_LABEL = "Components";
    private static final String OWNER_LABEL = "Owner";
    private static final String RELATIONS_LABEL = "Relations";
    private static final String NEIGHBOURS_LABEL = "Neighbours";
    private static final String CONTEXT_VIEW_LABEL = "System Context View";
    private static final String SYSTEM_REACTIONS_LABEL = "System Reactions";

    private Arc42SystemPages() {
    }

    /**
     * The whole subtree of one system: the landing page of the structure, and the chapters this template
     * generates into.
     */
    static void write(Arc42Template template, DocumentedSystem system, GenerationContext context,
                      Path systemDirectory) throws IOException {
        Path structure = systemDirectory.resolve(SYSTEM_SEGMENT);
        Arc42Pages.writeCategory(structure, template.systemLabel(), 1);
        writeStructureLandingPage(template, system, context, structure);
        writeIntroduction(template, system, context, structure);
        writeContextAndScope(template, system, context, structure);
        writeBuildingBlockView(template, system, context, structure);
        writeRuntimeView(template, system, context, structure);
    }

    /** What this tree answers, and which of the twelve chapters exist. */
    private static void writeStructureLandingPage(Arc42Template template, DocumentedSystem system, GenerationContext context, Path structure)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(template.systemLabel(), 0, context))
                .heading(1, template.systemLabel() + " - " + system.name())
                .paragraph(Md.sentence("The architecture of {} described according to {}: what it is, who it "
                                       + "talks to, how it is decomposed and how it behaves while it runs.",
                        Md.code(system.name()), Md.link("https://arc42.org/overview/", ID)))
                .paragraph("Chapters with nothing in them do not appear. A gap in the numbering means the "
                           + "chapter has not been written, not that it is empty.");

        // Only the chapters this run wrote. A link to a missing page fails the site build.
        List<List<Markdown>> rows = new ArrayList<>();
        for (StructureChapter chapter : GENERATED_CHAPTERS) {
            rows.add(List.of(
                    Md.link(DocumentationPaths.chapter(system.slug(), SYSTEM_SEGMENT, chapter),
                            chapter.label()),
                    Md.text(Arc42Chapters.summaryOf(chapter))));
        }
        page.table(List.of("Chapter", "What it answers"), rows);
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(structure, Arc42Pages.INDEX, page);
    }

    /** Chapter 1: what the system is and who owns it. It also carries the arc42 attribution. */
    private static void writeIntroduction(Arc42Template template, DocumentedSystem system, GenerationContext context, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, INTRODUCTION);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(INTRODUCTION.label(), 0, context)
                        .put("description", system.description()))
                .heading(1, INTRODUCTION.label())
                .paragraphOrNothing(Md.text(system.description()),
                        "The architecture repository holds no description of this system.");

        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("System"), Md.code(system.name())));
        if (!system.aliases().isEmpty()) {
            rows.add(List.of(Md.text("Also known as"),
                    Md.joinWith(", ", system.aliases().stream().map(Md::code).toList())));
        }
        rows.add(List.of(Md.text("Responsible team"), teamOf(system.team())));
        rows.add(List.of(
                Md.link(DocumentationPaths.chapter(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW),
                        COMPONENTS_LABEL),
                Md.text(String.valueOf(system.components().size()))));
        rows.add(List.of(Md.text("Events"), Md.text(String.valueOf(system.events().size()))));
        rows.add(List.of(Md.text("Commands"), Md.text(String.valueOf(system.commands().size()))));
        page.table(List.of("", ""), rows);

        page.paragraph(Md.sentence("What this system is for, the goals it is built to, and who its "
                                   + "stakeholders are, are written by the team that owns it and appear "
                                   + "beside this page."));

        Arc42Pages.provenance(page, context);
        // The arc42 attribution, once per system and nowhere else on the site.
        page.paragraph(Md.sentence("Structured according to {} by Gernot Starke and Peter Hruschka, used "
                                   + "under {}.",
                Md.link("https://arc42.org", ID),
                Md.link("https://creativecommons.org/licenses/by-sa/4.0/", "CC BY-SA 4.0")));
        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    /**
     * Chapter 3: the outside view. The diagram is a page of its own, which leaves room for a team to write the
     * reasoning beside it.
     */
    private static void writeContextAndScope(Arc42Template template, DocumentedSystem system, GenerationContext context, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, CONTEXT_AND_SCOPE);
        SystemContext systemContext = SystemContext.of(context.model(), system, context.maxDiagramNodes());

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_AND_SCOPE.label(), 0, context))
                .heading(1, CONTEXT_AND_SCOPE.label())
                .paragraph(Md.sentence("Who {} talks to, and about what.", Md.code(system.name())))
                .bulletList(List.of(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT,
                        CONTEXT_AND_SCOPE, CONTEXT_VIEW_PAGE), CONTEXT_VIEW_LABEL)));
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        MarkdownWriter view = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_VIEW_LABEL, 1, context))
                .heading(1, CONTEXT_VIEW_LABEL)
                .paragraph(Md.sentence("The systems {} exchanges anything with, and what travels between them. "
                                       + "A solid arrow is a message, a dotted one a REST call.",
                        Md.code(system.name())));
        if (systemContext.isEmpty()) {
            view.paragraph("The architecture model records no relation between this system and any other.");
        } else {
            PlantUmlViews.Diagram diagram = PlantUmlViews.contextView(systemContext, context);
            view.fence(PlantUmlViews.LANGUAGE, diagram.source());
            if (systemContext.truncated() > 0) {
                view.admonition("note", "Not every neighbour is drawn", Md.text(
                        "%d further systems exchange something with this one and are left out of the diagram "
                        + "so that it stays readable. The table below lists every one of them."
                                .formatted(systemContext.truncated())));
            }
            view.heading(2, NEIGHBOURS_LABEL);
            view.table(List.of("From", "To", "Kind", "What travels"), systemContext.edges().stream()
                    .map(edge -> List.of(
                            systemLink(edge.from(), context),
                            systemLink(edge.to(), context),
                            Md.text(edge.kind().verb()),
                            Md.joinWith(", ", edge.labels().stream().map(Md::code).toList())))
                    .toList());
        }
        Arc42Pages.provenance(view, context);
        Arc42Pages.write(directory, CONTEXT_VIEW_PAGE + ".md", view);
    }

    /**
     * Chapter 5: the inside view, with the components, the events and the commands below it. A component is
     * one of the building blocks, so its documentation lives where the decomposition is described.
     */
    private static void writeBuildingBlockView(Arc42Template template, DocumentedSystem system, GenerationContext context, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, BUILDING_BLOCK_VIEW);
        WhiteboxView whitebox = WhiteboxView.of(context.model(), system, context.maxDiagramNodes());

        // The message groups are written before the listing that links to them, and the listing goes by what
        // they answer: a system defines no events, or no commands, more often than not, and a link to a
        // directory nothing wrote fails the build of every site of the environment.
        boolean events = Arc42MessagePages.write(system, MessageKind.EVENT, Arc42MessagePages.EVENTS, 3,
                context, directory);
        boolean commands = Arc42MessagePages.write(system, MessageKind.COMMAND, Arc42MessagePages.COMMANDS, 4,
                context, directory);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(BUILDING_BLOCK_VIEW.label(), 0, context))
                .heading(1, BUILDING_BLOCK_VIEW.label())
                .paragraph(Md.sentence("How {} is decomposed, and what flows between its parts.",
                        Md.code(system.name())));
        List<Markdown> contents = new ArrayList<>();
        contents.add(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                WHITEBOX_PAGE), "Level 1: Whitebox View " + system.name()));
        if (!system.components().isEmpty()) {
            contents.add(Md.link(DocumentationPaths.group(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    DocumentationPaths.COMPONENTS_SEGMENT), COMPONENTS_LABEL));
        }
        if (events) {
            contents.add(Md.link(DocumentationPaths.group(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    Arc42MessagePages.EVENTS), "Events"));
        }
        if (commands) {
            contents.add(Md.link(DocumentationPaths.group(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    Arc42MessagePages.COMMANDS), "Commands"));
        }
        index.bulletList(contents);
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        writeWhiteboxView(system, context, whitebox, directory);
        writeComponents(system, context, directory);
    }

    /**
     * The level-1 whitebox page: two diagrams of the same system, the components and the relations.
     * <p>
     * The first diagram is the decomposition on its own, which is what arc42 asks a level-1 whitebox for and
     * the only one a reader of a large system can take in. The second adds the neighbouring systems, each as
     * a single box, because the criterion asks for them too. Both are followed by the tables, which carry
     * every component and every relation whatever the diagrams had room to draw.
     */
    private static void writeWhiteboxView(DocumentedSystem system, GenerationContext context, WhiteboxView whitebox,
                                          Path directory) throws IOException {
        String title = "Level 1: Whitebox View " + system.name();
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(title, 1, context))
                .heading(1, title)
                .paragraph(Md.sentence("The components of {} and the relations between them. Other systems are "
                                       + "drawn as a single box each - what is inside them is described in "
                                       + "their own documentation.", Md.code(system.name())));
        if (system.components().isEmpty()) {
            page.paragraph("The architecture model knows no component of this system.");
        } else {
            // Only where there is something to see: a system whose components exchange nothing would get two
            // diagrams of the same boxes, and the second of them says nothing the table does not.
            if (!whitebox.internal().isEmpty()) {
                PlantUmlViews.Diagram inside = PlantUmlViews.internalView(whitebox, system.slug(), context);
                page.heading(2, "Inside the system");
                page.paragraph(Md.sentence("How {} is decomposed, and what flows between its own components.",
                        Md.code(system.name())));
                page.fence(PlantUmlViews.LANGUAGE, inside.source());
            }
            PlantUmlViews.Diagram withNeighbours =
                    PlantUmlViews.whiteboxView(whitebox, system.slug(), context);
            page.heading(2, "With the neighbouring systems");
            page.paragraph("The same components, with every other system they exchange something with as a "
                           + "single box. A solid arrow is a message, a dotted one a REST call.");
            page.fence(PlantUmlViews.LANGUAGE, withNeighbours.source());
            if (whitebox.truncated() > 0) {
                page.admonition("note", "Not every neighbour is drawn", Md.text(
                        "%d further systems exchange something with this one and are left out of the diagram "
                        + "so that it stays readable. The table of relations below lists every one of them."
                                .formatted(whitebox.truncated())));
            }

            page.heading(2, COMPONENTS_LABEL);
            page.table(List.of("Component", "Type", OWNER_LABEL, "Description"), system.components().stream()
                    .map(component -> List.of(
                            Md.link(DocumentationPaths.component(system.slug(), SYSTEM_SEGMENT,
                                    BUILDING_BLOCK_VIEW, component.slug()), component.name()),
                            Md.text(component.type().label()),
                            teamOf(component.team()),
                            Md.text(component.description())))
                    .toList());
            writeRelations(system, context, whitebox, page);
        }
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, WHITEBOX_PAGE + ".md", page);
    }

    /**
     * Every relation of the system, internal ones first: what the diagrams draw as an arrow, in full.
     * <p>
     * It is what makes a summarized label honest. An arrow reading {@code 5 Events} hides five names, and
     * criterion S-050 asks for them - so they are here, each linked to its message page where there is one.
     */
    private static void writeRelations(DocumentedSystem system, GenerationContext context, WhiteboxView whitebox,
                                       MarkdownWriter page) {
        List<WhiteboxView.Edge> edges = Stream.concat(whitebox.internal().stream(),
                whitebox.external().stream()).toList();
        if (edges.isEmpty()) {
            return;
        }
        page.heading(2, RELATIONS_LABEL);
        page.table(List.of("From", "To", "Kind", "What travels"), edges.stream()
                .map(edge -> List.of(
                        endLink(edge.from(), system, context),
                        endLink(edge.to(), system, context),
                        Md.text(edge.kind().verb()),
                        Md.joinWith(", ", edge.labels().stream()
                                .map(label -> travelling(label, system))
                                .toList())))
                .toList());
    }

    /**
     * Where an end of an arrow is documented: a component of this system, or another system as a whole.
     * <p>
     * Resolved through the model, never by lower-casing a name into a path - the ends come from relations and
     * are free text, so a name that is not in the model gets no link rather than a broken one.
     */
    private static Markdown endLink(String name, DocumentedSystem system, GenerationContext context) {
        return system.components().stream()
                .filter(component -> component.name().equalsIgnoreCase(name))
                .findFirst()
                .map(component -> Md.link(DocumentationPaths.component(system.slug(), SYSTEM_SEGMENT,
                        BUILDING_BLOCK_VIEW, component.slug()), component.name()))
                .orElseGet(() -> systemLink(name, context));
    }

    /**
     * What travels along an arrow, linked to its page when this system defines the message. A message another
     * system defines, or a REST resource, stays plain code.
     */
    private static Markdown travelling(String label, DocumentedSystem system) {
        return system.messages().stream()
                .filter(message -> message.name().equalsIgnoreCase(label))
                .findFirst()
                .map(message -> Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT,
                                BUILDING_BLOCK_VIEW, groupOf(message.kind()), message.slug()),
                        Md.code(message.name())))
                .orElseGet(() -> Md.code(label));
    }

    private static String groupOf(MessageKind kind) {
        return kind == MessageKind.COMMAND ? Arc42MessagePages.COMMANDS : Arc42MessagePages.EVENTS;
    }

    /** The root page of a component, and where its own arc42 tree will hang later. */
    private static void writeComponents(DocumentedSystem system, GenerationContext context, Path buildingBlock)
            throws IOException {
        if (system.components().isEmpty()) {
            return;
        }
        Path components = buildingBlock.resolve(DocumentationPaths.COMPONENTS_SEGMENT);
        Arc42Pages.writeCategory(components, COMPONENTS_LABEL, 2);
        writeComponentIndex(system, context, components);
        for (DocumentedComponent component : system.components()) {
            Path directory = components.resolve(component.slug());
            Arc42Pages.writeCategory(directory, component.name(), 0);
            MarkdownWriter page = new MarkdownWriter()
                    .frontMatter(Arc42Pages.generated(component.name(), 0, context)
                            .put("description", component.description()))
                    .heading(1, component.name());
            if (!Md.text(component.description()).isEmpty()) {
                page.paragraph(Md.text(component.description()));
            }

            List<List<Markdown>> rows = new ArrayList<>();
            rows.add(List.of(Md.text("Type"), Md.text(component.type().label())));
            rows.add(List.of(Md.text(OWNER_LABEL), teamOf(component.team())));
            rows.add(List.of(Md.text("System"), Md.link(
                    DocumentationPaths.system(system.slug()), system.name())));
            rows.add(List.of(Md.text("Known from"), Md.textOr(component.importer(), NOT_KNOWN)));
            rows.add(List.of(Md.text("Last seen"), component.lastSeen() == null
                    ? Markdown.EMPTY
                    : Md.text(component.lastSeen().toInstant().toString())));
            rows.add(List.of(Md.text("REST API"),
                    Md.text(component.hasRestApi() ? "yes" : "no")));
            rows.add(List.of(Md.text("Database schema"),
                    Md.text(component.databaseSchema() == null ? "no" : "yes")));
            page.table(List.of("", ""), rows);

            if (component.isStaleAt(context.generatedAt())) {
                page.admonition("warning", "Not seen recently", Md.sentence(
                        "No importer has seen this component since {}. What is documented here may describe "
                        + "something that no longer exists.",
                        Md.code(component.lastSeen().toInstant().toString())));
            }
            Arc42Pages.provenance(page, context);
            Arc42Pages.write(directory, Arc42Pages.INDEX, page);
        }
    }

    /** The landing page of the components group: every component of the system, each linked to its own page. */
    private static void writeComponentIndex(DocumentedSystem system, GenerationContext context, Path components)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(COMPONENTS_LABEL, 0, context))
                .heading(1, COMPONENTS_LABEL)
                .paragraph(Md.sentence("The components of {}.", Md.code(system.name())));
        page.table(List.of("Component", "Type", OWNER_LABEL, "Description"), system.components().stream()
                .map(component -> List.of(
                        Md.link(DocumentationPaths.component(system.slug(), SYSTEM_SEGMENT,
                                BUILDING_BLOCK_VIEW, component.slug()), component.name()),
                        Md.text(component.type().label()),
                        teamOf(component.team()),
                        Md.text(component.description())))
                .toList());
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(components, Arc42Pages.INDEX, page);
    }

    /**
     * Chapter 6: how the system behaves while it runs. The reactions that fill it are imported separately, so
     * the page says what it is waiting for rather than disappearing and coming back.
     */
    private static void writeRuntimeView(Arc42Template template, DocumentedSystem system, GenerationContext context, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, RUNTIME_VIEW);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(RUNTIME_VIEW.label(), 0, context))
                .heading(1, RUNTIME_VIEW.label())
                .paragraph(Md.sentence("How {} behaves while it runs.", Md.code(system.name())))
                .bulletList(List.of(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT,
                        RUNTIME_VIEW, SYSTEM_REACTIONS_PAGE), SYSTEM_REACTIONS_LABEL)));
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        MarkdownWriter reactions = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(SYSTEM_REACTIONS_LABEL, 1, context))
                .heading(1, SYSTEM_REACTIONS_LABEL)
                .paragraph(Md.sentence("Which message makes {} react, and what it does in answer. The "
                                       + "reactions are observed at runtime and imported from the reaction "
                                       + "observer service; that import is not published yet, so this page is "
                                       + "empty.", Md.code(system.name())))
                .paragraph(Md.sentence("Until then, {} shows what the system exchanges with its neighbours.",
                        Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT, CONTEXT_AND_SCOPE,
                                CONTEXT_VIEW_PAGE), "the system context view")));
        Arc42Pages.provenance(reactions, context);
        Arc42Pages.write(directory, SYSTEM_REACTIONS_PAGE + ".md", reactions);
    }

    private static Markdown teamOf(Team team) {
        if (team == null || team.name() == null || team.name().isBlank()) {
            return Md.italic("unknown");
        }
        if (team.contactAddress() == null || team.contactAddress().isBlank()) {
            return Md.text(team.name());
        }
        return Md.linkOrCode("mailto:" + team.contactAddress(), team.name());
    }

    /** A system name, linked when this run documents it. A link to a missing page fails the site build. */
    private static Markdown systemLink(String name, GenerationContext context) {
        return context.model().systems().stream()
                .filter(system -> system.name().equalsIgnoreCase(name))
                .findFirst()
                .map(system -> Md.link(DocumentationPaths.system(system.slug()), system.name()))
                .orElseGet(() -> Md.code(name));
    }
}
