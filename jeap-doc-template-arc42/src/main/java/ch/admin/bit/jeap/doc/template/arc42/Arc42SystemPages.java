package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.DisplayTime;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.architecture.view.SystemContext;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.architecture.view.WhiteboxView;
import ch.admin.bit.jeap.doc.domain.template.DocumentationPaths;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.template.SystemDocumentation;
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
     * The whole subtree of one system: the landing page of the structure, the chapters this template
     * generates into, and the chapters a team filled itself.
     * <p>
     * <b>A system the architecture model does not hold is written here too.</b> There is nothing to generate
     * from, so it gets one page saying so and its uploaded chapters beside it - see
     * {@link Arc42UnknownSubjectPage}.
     */
    static void write(Arc42Template template, SystemDocumentation system, GenerationContext context,
                      Path systemDirectory) throws IOException {
        Path structure = systemDirectory.resolve(SYSTEM_SEGMENT);
        Arc42Pages.writeOpenCategory(structure, template.systemLabel(), 1);
        SystemContext systemContext = system.model()
                .map(model -> SystemContext.of(context.model(), model, context.limits().maxDiagramNodes(),
                        context.viewExclusions()))
                .orElse(null);
        List<StructureChapter> generated = generatedChaptersOf(system, systemContext, context);
        List<StructureChapter> uploaded = Arc42CustomChapters.of(template,
                system.customChaptersOfTheSystem());
        writeStructureLandingPage(template, system, context, structure,
                Arc42CustomChapters.merged(template, generated, uploaded));

        if (system.isInTheArchitectureModel()) {
            DocumentedSystem model = system.model().orElseThrow();
            boolean buildingBlockView = generated.contains(BUILDING_BLOCK_VIEW);
            writeIntroduction(template, model, context, structure, buildingBlockView);
            // No content, no page: a chapter the model has nothing for is left out, like the runtime view.
            if (generated.contains(CONTEXT_AND_SCOPE)) {
                writeContextAndScope(template, model, systemContext, context, structure);
            }
            if (buildingBlockView) {
                writeBuildingBlockView(template, system, model, context, structure);
            }
            writeRuntimeView(template, model, context, structure);
        } else {
            Arc42UnknownSubjectPage.writeForSystem(template, system, context, structure);
            // Its components and libraries all the same. A system nothing has deployed can have documented
            // components - the model holds neither - and they would otherwise be written nowhere, because
            // what writes them for a known system is its building block view.
            writeWhatOnlyTheUploadsKnow(template, system, context, structure);
        }

        // The uploaded pages last, into the chapters this template named. A generated chapter gets them too:
        // the generator owns a chapter's index page and an upload owns the pages beside it.
        Arc42CustomChapters.write(template, system.pages(), system.subject(), context, structure,
                Arc42CustomChapters.of(template, system.customChaptersOfTheSystem()),
                system.isInTheArchitectureModel());
    }

    /**
     * The chapters this run generates. A system the architecture model does not hold gets chapter 1, where
     * the page saying so goes - and chapter 5 as well when it has documented components or libraries, since
     * that is where both are served.
     */
    private static List<StructureChapter> generatedChaptersOf(SystemDocumentation system,
                                                              SystemContext systemContext,
                                                              GenerationContext context) {
        if (system.isInTheArchitectureModel()) {
            DocumentedSystem model = system.model().orElseThrow();
            return chaptersOf(context).stream()
                    .filter(chapter -> chapter != CONTEXT_AND_SCOPE || !systemContext.isEmpty())
                    .filter(chapter -> chapter != BUILDING_BLOCK_VIEW || hasBuildingBlockView(system, model))
                    .toList();
        }
        return hasBuildingBlocks(system) ? List.of(INTRODUCTION, BUILDING_BLOCK_VIEW) : List.of(INTRODUCTION);
    }

    /** Whether chapter 5 has anything to show: a component, a library, an event or a command. */
    private static boolean hasBuildingBlockView(SystemDocumentation documented, DocumentedSystem model) {
        return !model.components().isEmpty() || hasBuildingBlocks(documented) || !model.messages().isEmpty();
    }

    private static boolean hasBuildingBlocks(SystemDocumentation system) {
        return !system.components().isEmpty() || !system.libraries().isEmpty();
    }

    /**
     * The building block view of a system the architecture model does not hold: its documented components and
     * its libraries, and nothing that would have come from a model.
     */
    private static void writeWhatOnlyTheUploadsKnow(Arc42Template template, SystemDocumentation system,
                                                    GenerationContext context, Path structure)
            throws IOException {
        if (!hasBuildingBlocks(system)) {
            return;
        }
        Path buildingBlock = Arc42Pages.chapterDirectory(template, structure, BUILDING_BLOCK_VIEW);
        writeBuildingBlockIndexOfUploadsOnly(system, context, buildingBlock);
        if (!system.components().isEmpty()) {
            Path components = buildingBlock.resolve(DocumentationPaths.COMPONENTS_SEGMENT);
            Arc42Pages.writeCategory(components, COMPONENTS_LABEL, 2);
            writeComponentIndexOfUploadsOnly(system, context, components);
            for (SystemDocumentation.ComponentDocumentation component : system.components()) {
                Arc42UnknownSubjectPage.writeForComponent(template, system, component, context,
                        components.resolve(component.slug()));
            }
        }
        Arc42LibraryPages.write(template, system, context, buildingBlock);
    }

    /** The chapter's own page, saying what is in it - there is no whitebox view to write. */
    private static void writeBuildingBlockIndexOfUploadsOnly(SystemDocumentation system,
                                                             GenerationContext context, Path buildingBlock)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(BUILDING_BLOCK_VIEW.label(), 0, context))
                .heading(1, BUILDING_BLOCK_VIEW.label())
                .paragraph(Md.sentence("The parts of {} that are documented. The architecture model of this "
                                       + "environment does not hold this system, so how it is decomposed is "
                                       + "what its team has written down and nothing more.",
                        Md.code(system.name())));
        Arc42Pages.write(buildingBlock, Arc42Pages.INDEX, page);
    }

    /** The components group's own page, for a system whose components only an upload knows. */
    private static void writeComponentIndexOfUploadsOnly(SystemDocumentation system,
                                                         GenerationContext context, Path components)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(COMPONENTS_LABEL, 0, context))
                .heading(1, COMPONENTS_LABEL)
                .paragraph(Md.sentence("The components of {} that are documented.", Md.code(system.name())));
        page.bulletList(system.components().stream()
                .map(component -> Md.link("./" + component.slug() + "/", component.name()))
                .toList());
        Arc42Pages.write(components, Arc42Pages.INDEX, page);
    }

    /**
     * The chapters this run wrote, which is what the landing page may link.
     * <p>
     * Chapter 6 is the conditional one: it holds the reactions observed at runtime, and a system nothing has
     * been observed reacting to gets no chapter rather than a page saying so.
     */
    private static List<StructureChapter> chaptersOf(GenerationContext context) {
        return context.reactions().hasReactionsOfTheSystem()
                ? GENERATED_CHAPTERS
                : GENERATED_CHAPTERS.stream().filter(chapter -> chapter != RUNTIME_VIEW).toList();
    }

    /** What this tree answers, and which of the twelve chapters exist. */
    private static void writeStructureLandingPage(Arc42Template template, SystemDocumentation system,
                                                  GenerationContext context, Path structure,
                                                  List<StructureChapter> chapters)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(template.systemLabel(), 0, context))
                .heading(1, template.systemLabel() + " - " + system.name())
                .paragraph(Md.sentence("The architecture of {}, described according to {}: what it is, what "
                                       + "systems it interacts with, how it is structured, and how it behaves "
                                       + "at runtime.",
                        Md.code(system.name()), Md.link("https://arc42.org/overview/", ID)))
                .paragraph("Chapters without documented content are omitted. A gap in the chapter "
                           + "numbering indicates that the corresponding section has not yet been authored or "
                           + "that no generated content is available to create it.");

        // Only the chapters this run wrote - generated or uploaded. A link to a missing page fails the
        // whole site build.
        List<List<Markdown>> rows = new ArrayList<>();
        for (StructureChapter chapter : chapters) {
            rows.add(List.of(
                    Md.link(DocumentationPaths.chapter(system.slug(), SYSTEM_SEGMENT, chapter),
                            chapter.label()),
                    Md.text(Arc42Chapters.summaryOf(chapter))));
        }
        page.table(List.of("Chapter", "What it answers"), rows);

        // Beside the chapters, because a library is a building block of this system and its tree is inside
        // chapter 5. The sidebar lists them too - the tree is enough for that - and this is what makes the
        // group readable rather than only expandable.
        List<List<Markdown>> libraries = Arc42LibraryPages.rowsFor(system);
        if (!libraries.isEmpty()) {
            page.heading(2, Arc42LibraryPages.LIBRARIES_LABEL);
            page.paragraph(Md.sentence("Documented by hand: a library publishes no artifact this service can "
                                       + "see and is deployed nowhere, so no architecture model holds one."));
            page.table(List.of("Library", "Version"), libraries);
        }
        Arc42Pages.write(structure, Arc42Pages.INDEX, page);
    }

    /** Chapter 1: what the system is and who owns it. It also carries the arc42 attribution. */
    private static void writeIntroduction(Arc42Template template, DocumentedSystem system, GenerationContext context,
                                          Path structure, boolean buildingBlockView)
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
        if (!system.otherNames().isEmpty()) {
            rows.add(List.of(Md.text("Also known as"),
                    Md.joinWith(", ", system.otherNames().stream().map(Md::code).toList())));
        }
        rows.add(List.of(Md.text("Responsible team"), teamOf(system.team())));
        rows.add(List.of(buildingBlockView
                        ? Md.link(DocumentationPaths.chapter(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW),
                                COMPONENTS_LABEL)
                        : Md.text(COMPONENTS_LABEL),
                Md.text(String.valueOf(system.components().size()))));
        rows.add(List.of(Md.text("Events"), Md.text(String.valueOf(system.events().size()))));
        rows.add(List.of(Md.text("Commands"), Md.text(String.valueOf(system.commands().size()))));
        page.table(List.of("", ""), rows);

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
    private static void writeContextAndScope(Arc42Template template, DocumentedSystem system,
                                             SystemContext systemContext, GenerationContext context,
                                             Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, CONTEXT_AND_SCOPE);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_AND_SCOPE.label(), 0, context))
                .heading(1, CONTEXT_AND_SCOPE.label())
                .paragraph(Md.sentence("Which external systems and actors interact with {}, and what "
                                       + "information or data is exchanged.", Md.code(system.name())))
                .bulletList(List.of(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT,
                        CONTEXT_AND_SCOPE, CONTEXT_VIEW_PAGE), CONTEXT_VIEW_LABEL)));
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        MarkdownWriter view = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_VIEW_LABEL, 1, context))
                .heading(1, CONTEXT_VIEW_LABEL)
                .paragraph(Md.sentence("The external systems {} interacts with and the data exchanged "
                                       + "between them. " + PlantUmlViews.ARROW_LEGEND,
                        Md.code(system.name())));
        PlantUmlViews.Diagram diagram = PlantUmlViews.contextView(systemContext, context);
        view.fence(PlantUmlViews.LANGUAGE, diagram.source());
        if (systemContext.truncated() > 0) {
            view.admonition("note", "Not every neighbour is drawn",
                    neighboursLeftOut(systemContext.truncated(), "table"));
        }
        view.heading(2, NEIGHBOURS_LABEL);
        view.table(List.of("From", "To", "Type", "Interaction"), systemContext.edges().stream()
                .map(edge -> List.of(
                        systemLink(edge.from(), context),
                        systemLink(edge.to(), context),
                        Md.text(edge.kind().type()),
                        Md.joinWith(", ", edge.labels().stream().map(Md::code).toList())))
                .toList());
        Arc42Pages.write(directory, CONTEXT_VIEW_PAGE + ".md", view);
    }

    /**
     * Chapter 5: the inside view, with the components, the events and the commands below it. A component is
     * one of the building blocks, so its documentation lives where the decomposition is described.
     */
    /** Inside chapter 5, after the components and the libraries: what the system publishes. */
    static final int EVENTS_POSITION = 4;
    static final int COMMANDS_POSITION = 5;

    private static void writeBuildingBlockView(Arc42Template template, SystemDocumentation documented,
                                               DocumentedSystem system, GenerationContext context,
                                               Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, BUILDING_BLOCK_VIEW);
        WhiteboxView whitebox = WhiteboxView.of(context.model(), system, context.limits().maxDiagramNodes(),
                context.viewExclusions());

        // The message groups are written before the listing that links to them, and the listing goes by what
        // they answer: a system defines no events, or no commands, more often than not, and a link to a
        // directory nothing wrote fails the build of every site of the environment.
        // Four and five, after the building blocks: the components stand at two and the libraries at three,
        // and two groups of one position would be ordered by their folder names instead.
        boolean events = Arc42MessagePages.write(system, MessageKind.EVENT, Arc42MessagePages.EVENTS,
                EVENTS_POSITION, context, directory);
        boolean commands = Arc42MessagePages.write(system, MessageKind.COMMAND, Arc42MessagePages.COMMANDS,
                COMMANDS_POSITION, context, directory);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(BUILDING_BLOCK_VIEW.label(), 0, context))
                .heading(1, BUILDING_BLOCK_VIEW.label())
                .paragraph(Md.sentence("How {} is decomposed, and what flows between its parts.",
                        Md.code(system.name())));
        List<Markdown> contents = new ArrayList<>();
        // The whitebox view draws the components, so a system with none gets no whitebox page.
        if (!system.components().isEmpty()) {
            contents.add(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    WHITEBOX_PAGE), "Whitebox View " + system.name()));
        }
        if (!documented.components().isEmpty()) {
            contents.add(Md.link(DocumentationPaths.group(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    DocumentationPaths.COMPONENTS_SEGMENT), COMPONENTS_LABEL));
        }
        if (!documented.libraries().isEmpty()) {
            contents.add(Md.link(DocumentationPaths.group(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                    DocumentationPaths.LIBRARIES_SEGMENT), Arc42LibraryPages.LIBRARIES_LABEL));
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
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        if (!system.components().isEmpty()) {
            writeWhiteboxView(system, context, whitebox, directory);
        }
        writeComponents(template, documented, context, directory);
        Arc42LibraryPages.write(template, documented, context, directory);
    }

    /**
     * The level-1 whitebox page: the components, the relations, and at most two pictures of them.
     * <p>
     * <b>A picture is a reading aid and the tables are the facts.</b> A whitebox view's arrows grow with the
     * square of its boxes - it is the one view where components talk to components - so beyond a bound there
     * is no picture of it that a reader can follow, and no part of it that is both readable and honest. The
     * view decides which pictures the page draws and how each is drawn; this writes what it decided, and says
     * so where a picture is folded or left out.
     */
    private static void writeWhiteboxView(DocumentedSystem system, GenerationContext context, WhiteboxView whitebox,
                                          Path directory) throws IOException {
        String title = "Whitebox View " + system.name();
        WhiteboxView.Pictures pictures = whitebox.pictures(context.limits());
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(title, 1, context))
                .heading(1, title)
                .paragraph(Md.sentence("All components of {}, the relations between them, and what they "
                                       + "exchange with other systems.", Md.code(system.name())));
        writeMissingPictureNote(page, system, pictures);

        WhiteboxView.Picture inside = pictures.inside();
        if (inside != null && inside.isDrawn()) {
            page.heading(2, "Inside the system");
            page.paragraph(Md.sentence("How {} is decomposed, and what flows between its own components.",
                    Md.code(system.name())));
            page.fence(PlantUmlViews.LANGUAGE,
                    PlantUmlViews.internalView(whitebox, inside, system.slug(), context).source());
            writeFoldedNote(page, inside, "components");
        }

        WhiteboxView.Picture outside = pictures.outside();
        if (outside != null && outside.isDrawn()) {
            boolean whole = outside.kind() == WhiteboxView.PictureKind.WHOLE;
            page.heading(2, whole ? "With the neighbouring systems" : "Across the system boundary");
            page.paragraph((whole
                    ? "The same components, with every other system they exchange something with as a single "
                      + "box - what is inside it is described in its own documentation. "
                    : "Only what crosses the boundary: the components that exchange something outside, and "
                      + "every other system they exchange it with as a single box - what is inside it is "
                      + "described in its own documentation. ")
                    + PlantUmlViews.ARROW_LEGEND);
            page.fence(PlantUmlViews.LANGUAGE, whole
                    ? PlantUmlViews.whiteboxView(whitebox, outside, system.slug(), context).source()
                    : PlantUmlViews.boundaryView(whitebox, outside, system.slug(), context).source());
            writeFoldedNote(page, outside, "boxes");
            // Only under a picture that draws neighbours at all: a note about the ones left out has nothing
            // to qualify where none is drawn.
            if (whitebox.truncated() > 0) {
                page.admonition("note", "Not every neighbour is drawn",
                        neighboursLeftOut(whitebox.truncated(), "table of relations"));
            }
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
        Arc42Pages.write(directory, WHITEBOX_PAGE + ".md", page);
    }

    /**
     * That a picture is drawn as its shape only, and by how much that reduced it.
     * <p>
     * A grey line and a missing arrowhead are not self-explanatory, and the reduction is the reason the
     * picture is readable at all.
     */
    private static void writeFoldedNote(MarkdownWriter page, WhiteboxView.Picture picture, String boxes) {
        if (picture.rendering() != WhiteboxView.Rendering.FOLDED) {
            return;
        }
        page.paragraph(Md.sentence("Drawn as its shape only: {}, whatever travels between them and in "
                                   + "whichever direction - {} relations folded into {} lines. A line with no "
                                   + "arrowhead joins a pair that exchanges something both ways. The table of "
                                   + "relations below has every relation, its type and its name.",
                Md.bold("one grey line per pair of " + boxes),
                Md.text(String.valueOf(picture.relations())),
                Md.text(String.valueOf(picture.folded().size()))));
    }

    /**
     * What is not drawn, and how much of it there is.
     * <p>
     * <b>The count is in the sentence</b> on purpose: it is the difference between a page that looks broken
     * and a page that made a decision, and it tells whoever owns the system that their landscape has grown.
     * <p>
     * <b>Prose rather than an admonition.</b> The admonition on this page belongs to the neighbours a picture
     * left out, and a second coloured box saying there is no picture at all would compete with it.
     */
    private static void writeMissingPictureNote(MarkdownWriter page, DocumentedSystem system,
                                                WhiteboxView.Pictures pictures) {
        boolean insideMissing = pictures.inside() != null && !pictures.inside().isDrawn();
        boolean outsideMissing = pictures.outside() != null && !pictures.outside().isDrawn();
        if (insideMissing && outsideMissing) {
            page.paragraph(Md.sentence("No diagram is drawn for {}: {} components with {} relations between "
                                       + "them and {} to other systems make a picture too large to read. The "
                                       + "tables below list every component and every relation, and each "
                                       + "component's own page shows what it exchanges.",
                    Md.code(system.name()),
                    Md.text(String.valueOf(system.components().size())),
                    Md.text(String.valueOf(pictures.inside().relations())),
                    Md.text(String.valueOf(pictures.outside().relations()))));
            return;
        }
        if (insideMissing) {
            page.paragraph(Md.sentence("The relations between the components of {} are not drawn: there are "
                                       + "{} of them, too many for a readable picture. The table of relations "
                                       + "below lists every one.",
                    Md.code(system.name()),
                    Md.text(String.valueOf(pictures.inside().relations()))));
        }
        if (outsideMissing) {
            page.paragraph(Md.sentence("The relations of {} to other systems are not drawn: there are {} of "
                                       + "them, too many for a readable picture. The table of relations below "
                                       + "lists every one.",
                    Md.code(system.name()),
                    Md.text(String.valueOf(pictures.outside().relations()))));
        }
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
        page.table(List.of("From", "To", "Type", "Interaction"), edges.stream()
                .map(edge -> List.of(
                        endLink(edge.from(), system, context),
                        endLink(edge.to(), system, context),
                        Md.text(edge.kind().type()),
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

    /**
     * The components of a system: the landing page of the group, and one subtree per component.
     * <p>
     * Two loops rather than one: a component the architecture model holds is written from the model, and a
     * component only the uploaded documentation knows has nothing to be written from - so it gets what a
     * system in that position gets, one page saying so and its own chapters beside it.
     */
    private static void writeComponents(Arc42Template template, SystemDocumentation documented,
                                        GenerationContext context, Path buildingBlock) throws IOException {
        DocumentedSystem system = documented.model().orElseThrow();
        List<SystemDocumentation.ComponentDocumentation> all = documented.components();
        if (all.isEmpty()) {
            return;
        }
        Path components = buildingBlock.resolve(DocumentationPaths.COMPONENTS_SEGMENT);
        Arc42Pages.writeCategory(components, COMPONENTS_LABEL, 2);
        writeComponentIndex(documented, system, context, components);
        for (SystemDocumentation.ComponentDocumentation onlyDocumented : all) {
            if (!onlyDocumented.isInTheArchitectureModel()) {
                Arc42UnknownSubjectPage.writeForComponent(template, documented, onlyDocumented, context,
                        components.resolve(onlyDocumented.slug()));
            }
        }
        for (DocumentedComponent component : system.components()) {
            writeComponent(template, documented, system, component, context,
                    components.resolve(component.slug()));
        }
    }

    /**
     * The root page of a component the architecture model holds, and its own arc42 tree below it.
     */
    private static void writeComponent(Arc42Template template, SystemDocumentation documented,
                                       DocumentedSystem system, DocumentedComponent component,
                                       GenerationContext context, Path directory) throws IOException {
        Arc42Pages.writeCategory(directory, component.name(), 0);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(component.name(), 0, context)
                        .put("description", component.description()))
                .heading(1, component.name());
        if (!Md.text(component.description()).isEmpty()) {
            page.paragraph(Md.text(component.description()));
        }
        if (context.viewExclusions().excludesComponent(component.name())) {
            page.paragraph("This component is left out of the diagrams and relations tables of other pages "
                           + "by configuration. Its own pages show what it exchanges.");
        } else if (context.viewExclusions().excludesRelationsOf(component.name())) {
            // One sentence on the page that owns the subject, rather than a note on each of the pages a
            // relation was taken off.
            page.paragraph("Some of this component's relations are left out of the diagrams and relations "
                           + "tables of other pages by configuration. Its own pages show what it exchanges.");
        }

        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("Type"), Md.text(component.type().label())));
        rows.add(List.of(Md.text(OWNER_LABEL), teamOf(component.team())));
        rows.add(List.of(Md.text("System"), Md.link(
                DocumentationPaths.system(system.slug()), system.name())));
        rows.add(List.of(Md.text("Known from"), Md.textOr(component.importer(), NOT_KNOWN)));
        rows.add(List.of(Md.text("Last seen"), component.lastSeen() == null
                ? Markdown.EMPTY
                : Md.text(DisplayTime.of(component.lastSeen()))));
        rows.add(List.of(Md.text("REST API"),
                Md.text(component.hasRestApi() ? "yes" : "no")));
        rows.add(List.of(Md.text("Database schema"),
                Md.text(component.databaseSchema() == null ? "no" : "yes")));
        page.table(List.of("", ""), rows);

        if (component.isStaleAt(context.generatedAt())) {
            page.admonition("warning", "Not seen recently", Md.sentence(
                    "No importer has seen this component since {}. What is documented here may describe "
                    + "something that no longer exists.",
                    Md.code(DisplayTime.of(component.lastSeen()))));
        }

        // The subtree first and the link to it after, the way a system's landing page is written: a link
        // to a page nothing wrote fails the build of every site of the environment.
        Arc42ComponentPages.write(template, documented, system, component, context, directory);
        page.heading(2, "Documentation");
        page.bulletList(List.of(Md.link(
                Arc42ComponentPages.pathsOf(template, system, component).structure(),
                template.componentLabel())));

        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    /**
     * The landing page of the components group: every component of the system, each linked to its own page.
     * <p>
     * Every component, which is more than the model's: one that is documented and deployed nowhere is listed
     * here too, with the columns the model would have filled left empty.
     */
    private static void writeComponentIndex(SystemDocumentation documented, DocumentedSystem system,
                                            GenerationContext context, Path components)
            throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(COMPONENTS_LABEL, 0, context))
                .heading(1, COMPONENTS_LABEL)
                .paragraph(Md.sentence("The components of {}.", Md.code(system.name())));
        page.table(List.of("Component", "Type", OWNER_LABEL, "Description"), documented.components().stream()
                .map(component -> List.of(
                        Md.link(DocumentationPaths.component(system.slug(), SYSTEM_SEGMENT,
                                BUILDING_BLOCK_VIEW, component.slug()), component.name()),
                        component.model().map(model -> Md.text(model.type().label())).orElse(Markdown.EMPTY),
                        component.model().map(model -> teamOf(model.team())).orElse(Markdown.EMPTY),
                        component.model().map(model -> Md.text(model.description())).orElse(Markdown.EMPTY)))
                .toList());
        Arc42Pages.write(components, Arc42Pages.INDEX, page);
    }

    /**
     * Chapter 6: how the system behaves while it runs - which message makes one of its components react, and
     * what it does in answer.
     * <p>
     * <b>No reactions, no chapter.</b> Nothing is written when the reaction observer of this environment has
     * no graph for the system, or has one with nothing in it: an empty page is a lie about a system that has
     * simply not been observed reacting, and on a platform whose observer has just been switched on that is
     * every system. The landing page links the chapters this run wrote, so a missing one shows as a gap in
     * the numbering.
     */
    private static void writeRuntimeView(Arc42Template template, DocumentedSystem system,
                                         GenerationContext context, Path structure) throws IOException {
        ReactionView reactions = context.reactions().system();
        if (reactions.isEmpty()) {
            return;
        }
        Path directory = Arc42Pages.chapterDirectory(template, structure, RUNTIME_VIEW);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(RUNTIME_VIEW.label(), 0, context))
                .heading(1, RUNTIME_VIEW.label())
                .paragraph(Md.sentence("How {} behaves while it runs.", Md.code(system.name())))
                .bulletList(List.of(Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT,
                        RUNTIME_VIEW, SYSTEM_REACTIONS_PAGE), SYSTEM_REACTIONS_LABEL)));
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(SYSTEM_REACTIONS_LABEL, 1, context))
                .heading(1, SYSTEM_REACTIONS_LABEL)
                .paragraph(Md.sentence("Which message makes {} react, and what it does in answer. Every "
                                       + "reaction here belongs to one of its components; a message outlined "
                                       + "in blue is defined by another system.", Md.code(system.name())));
        Arc42ReactionPages.write(page, reactions, context, null);
        Arc42Pages.write(directory, SYSTEM_REACTIONS_PAGE + ".md", page);
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

    /**
     * The note under a diagram that left neighbours out, written by both of the two diagrams that can leave
     * one out.
     * <p>
     * Agreeing with the count - see {@link Arc42Pages#leftOut}, which is where that rule lives and which the
     * two notes on a component's pages go through as well.
     *
     * @param table what the page calls the list below the diagram
     */
    private static Markdown neighboursLeftOut(int truncated, String table) {
        return Arc42Pages.leftOut(truncated,
                ("One further system exchanges something with this one and is left out of the diagram so "
                 + "that it stays readable. The %s below names it.").formatted(table),
                ("%d further systems exchange something with this one and are left out of the diagram so "
                 + "that it stays readable. The %s below lists every one of them.")
                        .formatted(truncated, table));
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
