package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ApiGroup;
import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.architecture.view.ComponentContext;
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

import static ch.admin.bit.jeap.doc.markdown.MarkdownWriter.NOT_KNOWN;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.BUILDING_BLOCK_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.CONTEXT_AND_SCOPE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.INTRODUCTION;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Chapters.RUNTIME_VIEW;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.COMPONENT_CONTEXT_VIEW_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.COMPONENT_REACTIONS_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.DATABASE_SCHEMA_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.MESSAGES_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.REST_API_PAGE;
import static ch.admin.bit.jeap.doc.template.arc42.Arc42Template.SYSTEM_SEGMENT;

/**
 * Turns one component into the arc42 pages of its own subtree.
 * <p>
 * The same twelve chapters as a system's, one level down. Four of them are generated; the other eight are not
 * created at all, so that an empty chapter never claims to have content. What a team writes by hand arrives
 * beside these pages.
 * <p>
 * <b>The arc42 attribution is not repeated here.</b> It sits once per system tree, at the foot of the
 * system's chapter 1, and this is inside that tree.
 * <p>
 * Static like {@link Arc42SystemPages} and {@link Arc42MessagePages}: writing a page holds no state.
 */
final class Arc42ComponentPages {

    private static final String CONTEXT_VIEW_LABEL = "Component Context View";
    private static final String COMPONENT_REACTIONS_LABEL = "Component Reactions";
    private static final String DATABASE_SCHEMA_LABEL = "Database Schema";
    private static final String REST_API_LABEL = "REST API";
    private static final String MESSAGES_LABEL = "Messages";
    private static final String TOPIC_LABEL = "Topic";
    private static final String VERSIONS_LABEL = "Versions";
    private static final String NONE = "None.";

    /** The columns of the two contract tables on the messages page. */
    private static final List<String> MESSAGE_COLUMNS = List.of("Message", "Kind", TOPIC_LABEL, VERSIONS_LABEL);

    private Arc42ComponentPages() {
    }

    /**
     * Writes the whole subtree of one component, below the page {@link Arc42SystemPages} wrote for it.
     *
     * @param componentDirectory {@code …/building-block-view/components/<slug>}
     */
    static void write(Arc42Template template, DocumentedSystem system, DocumentedComponent component,
                      GenerationContext context, Path componentDirectory) throws IOException {
        DocumentationPaths.ComponentPaths paths = pathsOf(template, system, component);
        Path structure = componentDirectory.resolve(template.componentPathSegment());
        // Closed, and the one category here that is. Every component of a system expanded down to its own
        // twelve chapters is a sidebar that a system of thirty components makes unusable; what a reader needs
        // to see under a component is that its architecture is documented at all.
        Arc42Pages.writeCategory(structure, template.componentLabel(), 1, false);

        // The chapters first, so the landing page lists only the ones that exist. A link to a page nothing
        // wrote fails the whole site build.
        writeIntroduction(template, system, component, context, structure);
        writeContextAndScope(template, system, component, context, paths, structure);
        boolean buildingBlockView =
                writeBuildingBlockView(template, system, component, context, paths, structure);
        writeRuntimeView(template, component, context, paths, structure);

        writeLandingPage(template, system, component, context, paths, structure, buildingBlockView);
    }

    /** Where the pages of this component are served. The one place a component path is built. */
    static DocumentationPaths.ComponentPaths pathsOf(Arc42Template template, DocumentedSystem system,
                                                     DocumentedComponent component) {
        return DocumentationPaths.componentPaths(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW,
                component.slug(), template.componentPathSegment());
    }

    /** What this tree answers, and which of the twelve chapters exist. */
    private static void writeLandingPage(Arc42Template template, DocumentedSystem system,
                                         DocumentedComponent component, GenerationContext context,
                                         DocumentationPaths.ComponentPaths paths, Path structure,
                                         boolean buildingBlockView) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(template.componentLabel(), 0, context))
                .heading(1, template.componentLabel() + " - " + component.name())
                .paragraph(Md.sentence("The architecture of {}, a component of {}, described according to "
                                       + "{}: what it is, what it talks to, what it keeps and how it behaves "
                                       + "while it runs.",
                        Md.code(component.name()),
                        Md.link(DocumentationPaths.system(system.slug()), system.name()),
                        Md.link("https://arc42.org/overview/", Arc42Template.ID)))
                .paragraph("Chapters with nothing in them do not appear. A gap in the numbering means the "
                           + "chapter has not been written, not that it is empty.");

        List<List<Markdown>> rows = new ArrayList<>();
        for (StructureChapter chapter : chaptersOf(buildingBlockView)) {
            rows.add(List.of(Md.link(paths.chapter(chapter), chapter.label()),
                    Md.text(Arc42Chapters.componentSummaryOf(chapter))));
        }
        page.table(List.of("Chapter", "What it answers"), rows);
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(structure, Arc42Pages.INDEX, page);
    }

    /**
     * The chapters this run wrote. Chapter 5 is the only conditional one: a component with no schema, no REST
     * API and no message contract has nothing to put in it.
     */
    private static List<StructureChapter> chaptersOf(boolean buildingBlockView) {
        return buildingBlockView
                ? List.of(INTRODUCTION, CONTEXT_AND_SCOPE, BUILDING_BLOCK_VIEW, RUNTIME_VIEW)
                : List.of(INTRODUCTION, CONTEXT_AND_SCOPE, RUNTIME_VIEW);
    }

    /**
     * Chapter 1: what the component is and who owns it.
     * <p>
     * It repeats the table on the component's page in the system's tree on purpose. That page is where a
     * reader finds the component; this is the chapter arc42 asks for, and someone who followed a link into
     * the subtree should not have to go back out for the owner's name.
     */
    private static void writeIntroduction(Arc42Template template, DocumentedSystem system,
                                          DocumentedComponent component, GenerationContext context,
                                          Path structure) throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, INTRODUCTION);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(INTRODUCTION.label(), 0, context)
                        .put("description", component.description()))
                .heading(1, INTRODUCTION.label())
                .paragraphOrNothing(Md.text(component.description()),
                        "The architecture repository holds no description of this component.");

        List<List<Markdown>> rows = new ArrayList<>();
        rows.add(List.of(Md.text("Component"), Md.code(component.name())));
        rows.add(List.of(Md.text("Type"), Md.text(component.type().label())));
        rows.add(List.of(Md.text("Responsible team"), teamOf(component.team())));
        rows.add(List.of(Md.text("System"),
                Md.link(DocumentationPaths.system(system.slug()), system.name())));
        rows.add(List.of(Md.text("Known from"), Md.textOr(component.importer(), NOT_KNOWN)));
        rows.add(List.of(Md.text("Last seen"), component.lastSeen() == null
                ? Markdown.EMPTY
                : Md.text(component.lastSeen().toInstant().toString())));
        page.table(List.of("", ""), rows);

        if (component.isStaleAt(context.generatedAt())) {
            page.admonition("warning", "Not seen recently", Md.sentence(
                    "No importer has seen this component since {}. What is documented here may describe "
                    + "something that no longer exists.",
                    Md.code(component.lastSeen().toInstant().toString())));
        }
        page.paragraph("What this component is for and the goals it is built to are written by the team that "
                       + "owns it and appear beside this page.");
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, page);
    }

    /** Chapter 3: what the component talks to. The diagram is a page of its own, like the system's. */
    private static void writeContextAndScope(Arc42Template template, DocumentedSystem system,
                                             DocumentedComponent component, GenerationContext context,
                                             DocumentationPaths.ComponentPaths paths, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, CONTEXT_AND_SCOPE);
        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_AND_SCOPE.label(), 0, context))
                .heading(1, CONTEXT_AND_SCOPE.label())
                .paragraph(Md.sentence("What {} talks to, and about what.", Md.code(component.name())))
                .bulletList(List.of(Md.link(paths.page(CONTEXT_AND_SCOPE, COMPONENT_CONTEXT_VIEW_PAGE),
                        CONTEXT_VIEW_LABEL)));
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        writeContextView(system, component, context, directory);
    }

    /**
     * The component with its siblings and the systems around it, and a table of every relation below it.
     * <p>
     * The table is what makes a bounded diagram honest: an arrow reading {@code 5 Events} hides five names.
     */
    private static void writeContextView(DocumentedSystem system, DocumentedComponent component,
                                         GenerationContext context, Path directory) throws IOException {
        ComponentContext componentContext = ComponentContext.of(context.model(), system, component,
                context.limits().maxContextComponents(), context.limits().maxDiagramNodes());
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(CONTEXT_VIEW_LABEL, 1, context))
                .heading(1, CONTEXT_VIEW_LABEL)
                .paragraph(Md.sentence("What {} exchanges with the other components of {} and with the "
                                       + "systems around it, and what travels between them. Another system is "
                                       + "one box - what is inside it is described in its own documentation. "
                                       + "A solid arrow is a message, a dotted one a REST call.",
                        Md.code(component.name()),
                        Md.link(DocumentationPaths.system(system.slug()), system.name())));

        if (componentContext.isEmpty()) {
            page.paragraph("The architecture model records no relation between this component and anything "
                           + "else. It exchanges nothing that any importer has seen.");
        } else {
            PlantUmlViews.Diagram diagram =
                    PlantUmlViews.componentContextView(componentContext, system.slug(), context);
            page.fence(PlantUmlViews.LANGUAGE, diagram.source());
            int counterparts = componentContext.siblings().size()
                               + componentContext.externalSystems().size();
            if (componentContext.truncated() > 0) {
                page.admonition("note", "Not every counterpart is drawn",
                        Arc42Pages.leftOut(componentContext.truncated(),
                                ("One of the %d components and systems this one exchanges something with is "
                                 + "left out of the diagram so that it stays readable. The table below names "
                                 + "it.").formatted(counterparts),
                                ("%d of the %d components and systems this one exchanges something with are "
                                 + "left out of the diagram so that it stays readable. The table below lists "
                                 + "every one of them.")
                                        .formatted(componentContext.truncated(), counterparts)));
            }
            page.heading(2, "Relations");
            page.table(List.of("From", "To", "Kind", "What travels"), componentContext.edges().stream()
                    .map(edge -> List.of(
                            endLink(edge.from(), system, context),
                            endLink(edge.to(), system, context),
                            Md.text(edge.kind().verb()),
                            Md.joinWith(", ", edge.labels().stream().map(Md::code).toList())))
                    .toList());
        }
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, COMPONENT_CONTEXT_VIEW_PAGE + ".md", page);
    }

    /**
     * Chapter 5: the component's database schema, REST API and messages. Answers whether the chapter was
     * written at all.
     */
    private static boolean writeBuildingBlockView(Arc42Template template, DocumentedSystem system,
                                                  DocumentedComponent component, GenerationContext context,
                                                  DocumentationPaths.ComponentPaths paths, Path structure)
            throws IOException {
        List<DocumentedMessage> messages = messagesOf(system, component);
        boolean database = component.databaseSchema() != null || component.schema() != null;
        boolean restApi = component.hasRestApi();
        if (!database && !restApi && messages.isEmpty()) {
            return false;
        }
        Path directory = Arc42Pages.chapterDirectory(template, structure, BUILDING_BLOCK_VIEW);

        List<Markdown> contents = new ArrayList<>();
        if (database) {
            writeDatabaseSchema(component, context, directory);
            contents.add(Md.link(paths.page(BUILDING_BLOCK_VIEW, DATABASE_SCHEMA_PAGE), DATABASE_SCHEMA_LABEL));
        }
        if (restApi) {
            writeRestApi(component, context, directory);
            contents.add(Md.link(paths.page(BUILDING_BLOCK_VIEW, REST_API_PAGE), REST_API_LABEL));
        }
        if (!messages.isEmpty()) {
            writeMessages(system, component, messages, context, directory);
            contents.add(Md.link(paths.page(BUILDING_BLOCK_VIEW, MESSAGES_PAGE), MESSAGES_LABEL));
        }

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(BUILDING_BLOCK_VIEW.label(), 0, context))
                .heading(1, BUILDING_BLOCK_VIEW.label())
                .paragraph(Md.sentence("The data {} keeps and the interfaces it offers.",
                        Md.code(component.name())));
        index.bulletList(contents);
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);
        return true;
    }

    /**
     * The entity relationship diagram, and the list of tables with their columns below it.
     * <p>
     * <b>The diagram is bounded and the list is not</b>, so a schema too large to draw still gives a reader
     * a page they can use.
     * <p>
     * <b>Written as soon as the model says the component has a schema</b>, replicated or not, the way the
     * REST API page is. Between an architecture import and the replication - a new component, or one a run
     * missed at its deadline - there would otherwise be no entry in the chapter at all, and no way for a
     * reader to tell a schema that has not arrived yet from a component that keeps no data.
     */
    private static void writeDatabaseSchema(DocumentedComponent component, GenerationContext context,
                                            Path directory) throws IOException {
        DatabaseSchema schema = component.schema();
        // Worked out once for the whole page. Collapsing the partitions and sorting is what documentedTables()
        // does on every call, and the page needs it through a dozen paths: the count, the diagram, the notes
        // and the list of tables.
        DocumentedSchema documented = schema == null ? null
                : DocumentedSchema.of(schema, context.limits().maxSchemaTableDiagram(),
                        context.limits().maxSchemaTableList());
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(DATABASE_SCHEMA_LABEL, 1, context))
                .heading(1, DATABASE_SCHEMA_LABEL)
                .paragraph(Md.sentence("The database {} keeps its data in, as its build published it.",
                        Md.code(component.name())));

        List<List<Markdown>> facts = new ArrayList<>();
        // As code, like every other identifier on these pages: a database name and a version are things to
        // copy rather than to read as prose, and an underscore in one is not emphasis.
        facts.add(List.of(Md.text("Database"), codeOr(schema == null ? null : schema.name())));
        facts.add(List.of(Md.text("Schema version"), schemaVersion(component, schema)));
        if (component.databaseSchema() != null) {
            // Absolute, because a contentUrl is served relative to the architecture repository and a browser
            // has to be able to follow it - the paragraph below this table tells the reader to. Relative, it
            // was never a link at all: linkOrCode links only what it can, so the row read as plain code.
            //
            // linkOrCode and not link: the URL is whatever the architecture repository stored, and link throws
            // on a target it will not put on a page - which would end the generation of every system here.
            facts.add(List.of(Md.text("Published schema"),
                    Md.linkOrCode(context.archRepoLink(component.databaseSchema().contentUrl()),
                            "Open the schema")));
        }
        if (documented != null) {
            facts.add(List.of(Md.text("Tables"), tableCount(documented)));
        }
        page.table(List.of("", ""), facts);

        if (schema == null) {
            page.paragraph("The architecture repository knows that this component publishes a database "
                           + "schema and this service has not replicated it yet, so there is no diagram and "
                           + "no list of tables. Open the published schema itself to read them.");
            Arc42Pages.provenance(page, context);
            Arc42Pages.write(directory, DATABASE_SCHEMA_PAGE + ".md", page);
            return;
        }

        if (documented.isEmpty()) {
            page.paragraph("The published schema holds no table this documentation shows.");
        } else {
            page.fence(PlantUmlViews.LANGUAGE, PlantUmlViews.databaseSchema(documented).source());
            // Four reductions can apply to one page - partitions grouped, the diagram cut, the list cut, the
            // machinery hidden - and each of them says so in its own note. A reader who cannot tell them
            // apart cannot tell what is missing from what is merely summarised.
            writeDiagramNote(page, documented);
            writePartitionNote(page, documented);
            writeListNote(page, component, context, documented);
            writeTables(page, documented);
        }
        if (!documented.hiddenTables().isEmpty()) {
            // Named rather than dropped silently, so that nobody has to guess whether the schema or the
            // documentation is the incomplete one.
            page.admonition("info", "Some tables are left out on purpose", Md.sentence(
                    "The machinery of a schema is not the data of the component, so neither the diagram nor "
                    + "the list carries {}.",
                    Md.joinWith(", ", documented.hiddenTables().stream().map(Md::code).toList())));
        }
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, DATABASE_SCHEMA_PAGE + ".md", page);
    }

    private static Markdown codeOr(String value) {
        return value == null || value.isBlank() ? Md.text(NOT_KNOWN) : Md.code(value);
    }

    /** The version of the replicated schema where there is one, the model's otherwise. */
    private static Markdown schemaVersion(DocumentedComponent component, DatabaseSchema schema) {
        if (schema != null && schema.version() != null && !schema.version().isBlank()) {
            return Md.code(schema.version());
        }
        return component.databaseSchema() == null ? Md.text(NOT_KNOWN)
                : codeOr(component.databaseSchema().schemaVersion());
    }

    /** What the picture leaves out. About the picture only: the list below carries those entries. */
    private static void writeDiagramNote(MarkdownWriter page, DocumentedSchema documented) {
        int notDrawn = documented.notDrawn();
        if (notDrawn == 0) {
            return;
        }
        int entries = documented.documentedCount();
        page.admonition("note", "Not every table is drawn", Arc42Pages.leftOut(notDrawn,
                ("The diagram draws %d of the %d entries. It is bounded because the engine lays a picture "
                 + "out by recursion, so a larger one renders as nothing at all; the list below carries the "
                 + "one it leaves out.").formatted(entries - notDrawn, entries),
                ("The diagram draws %d of the %d entries. It is bounded because the engine lays a picture "
                 + "out by recursion, so a larger one renders as nothing at all; the list below carries "
                 + "every one it leaves out.").formatted(entries - notDrawn, entries)));
    }

    /**
     * That partitions were grouped, and what to read a {@code _*} as. <b>Named rather than left to be
     * guessed</b>: a reader who does not know the convention would read one entry as one table.
     */
    private static void writePartitionNote(MarkdownWriter page, DocumentedSchema documented) {
        int families = documented.collapsedFamilies();
        if (families == 0) {
            return;
        }
        // The example is one of this schema's own entries rather than an invented name: a made-up one reads
        // as a table the reader should be able to find on the page.
        String example = documented.documented().stream()
                .filter(table -> table.shards() != null)
                .findFirst().orElseThrow().name();
        page.admonition("info", "Partitions are grouped", Md.join(
                Arc42Pages.leftOut(families,
                        "One table of this schema is published as a partition each. It is documented once, "
                        + "under the name of the table it partitions with a ",
                        ("%d tables of this schema are published as a partition each. Each is documented "
                         + "once, under the name of the table it partitions with a ").formatted(families)),
                Md.code("_*"),
                Md.sentence(" postfix - {} is one. Every such entry says how many partitions it stands for, "
                            + "and their range.", Md.code(example))));
    }

    /**
     * That the list itself is bounded, with the link to the schema that carries all of it. <b>The only note
     * about content the page does not write at all</b>, so it has to say where the rest is.
     */
    private static void writeListNote(MarkdownWriter page, DocumentedComponent component,
                                      GenerationContext context, DocumentedSchema documented) {
        if (documented.notListed() == 0) {
            return;
        }
        Markdown where = component.databaseSchema() == null ? Md.text("the published schema")
                : Md.linkOrCode(context.archRepoLink(component.databaseSchema().contentUrl()),
                        "the published schema");
        page.admonition("note", "Not every table is listed", Md.sentence(
                "This page lists {} of the {} entries, by name. Open {} to read the rest.",
                Md.text(String.valueOf(documented.listed().size())),
                Md.text(String.valueOf(documented.documentedCount())), where));
    }

    /**
     * How many tables the page documents. Both numbers where they differ, because a reader has to be able to
     * see that the schema holds 6583 tables and that the page shows 260 of them - rather than be shown 260
     * and told nothing.
     */
    private static Markdown tableCount(DocumentedSchema documented) {
        if (documented.collapsedFamilies() == 0) {
            return Md.text(String.valueOf(documented.documentedCount()));
        }
        return Md.text("%d (%d after grouping partitions)"
                .formatted(documented.rawTableCount(), documented.documentedCount()));
    }

    /** Every entry with its columns, including the ones the diagram had no room for. */
    private static void writeTables(MarkdownWriter page, DocumentedSchema documented) {
        page.heading(2, "Tables");
        for (SchemaTable table : documented.listed()) {
            page.heading(3, Md.code(table.name()));
            if (table.shards() != null) {
                // Summarised, never hidden: the count and the range are what let a reader see that this one
                // entry stands for a hundred and twenty-five tables.
                page.paragraph(Md.sentence("{} partitions of one table, {} to {}.",
                        Md.text(String.valueOf(table.shards().count())),
                        Md.code(table.shards().firstSuffix()), Md.code(table.shards().lastSuffix())));
            }
            if (table.columns().isEmpty()) {
                page.paragraph("The published schema names no column of this table.");
                continue;
            }
            page.table(List.of("Column", "Type", "Nullable", "Key"), table.columns().stream()
                    .map(column -> List.of(
                            Md.code(column.name()),
                            Md.code(column.type()),
                            Md.text(column.nullable() ? "yes" : "no"),
                            keysOf(table, column)))
                    .toList());
        }
    }

    /** Which keys the column belongs to, and where a foreign key points. */
    private static Markdown keysOf(SchemaTable table, SchemaColumn column) {
        List<Markdown> keys = new ArrayList<>();
        if (table.isKeyColumn(column.name())) {
            keys.add(Md.text("PK"));
        }
        for (SchemaForeignKey key : table.foreignKeys()) {
            if (key.columnNames().stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))) {
                keys.add(Md.join(Md.text("FK to "), Md.code(key.referencedTableName())));
            }
        }
        return Md.joinWith(", ", keys);
    }

    /**
     * The overview of the REST API: a table per group, and the link to the architecture repository's Swagger
     * UI. An overview, not a rendering of the specification - there is a real Swagger UI to link to.
     * <p>
     * <b>Written even where no specification has been replicated.</b> The model knows the operations, so a
     * page listing them and saying that the grouping needs the specification is worth more than no page.
     */
    private static void writeRestApi(DocumentedComponent component, GenerationContext context, Path directory)
            throws IOException {
        RestApiOverview declared = component.api();
        // The operational endpoints of the platform are in a service's specification without being what a
        // reader came for - see DocumentedApiPaths. Applied here rather than when the specification was
        // replicated, so that changing the list takes effect on the next build.
        RestApiOverview api = context.apiPaths().documented(declared);
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(REST_API_LABEL, 2, context))
                .heading(1, REST_API_LABEL)
                .paragraph(Md.sentence("The resources {} offers over REST. It is an overview - the "
                                       + "specification itself is served by the architecture repository.",
                        Md.code(component.name())));

        List<List<Markdown>> facts = new ArrayList<>();
        facts.add(List.of(Md.text("Specification version"), version(component, api)));
        facts.add(List.of(Md.text("Served at"), serverUrl(component, api)));
        if (component.openApi() != null) {
            // linkOrCode, not link: the URL is whatever the architecture repository stored, and link throws
            // on a target it will not put on a page - which would end the generation of every system here.
            facts.add(List.of(Md.text("Swagger UI"),
                    Md.linkOrCode(component.openApi().swaggerUrl(), "Open the specification")));
        }
        facts.add(List.of(Md.text("Operations"),
                Md.text(String.valueOf(operationCountOf(component, api, context)))));
        page.table(List.of("", ""), facts);

        writeExcludedNote(page, declared, api);
        if (api == null || api.isEmpty()) {
            writeOperationsFromTheModel(page, component, context);
        } else {
            for (ApiGroup group : api.groups()) {
                page.heading(2, group.name());
                if (!Md.text(group.description()).isEmpty()) {
                    page.paragraph(Md.text(group.description()));
                }
                page.table(List.of("Method", "Path", "Summary"), group.operations().stream()
                        .map(operation -> List.of(
                                Md.code(operation.method()),
                                Md.code(operation.path()),
                                summaryOf(operation)))
                        .toList());
            }
        }
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, REST_API_PAGE + ".md", page);
    }

    /**
     * That some of the specification's operations are not described here, and how many.
     * <p>
     * Said rather than left to be noticed: the count in the facts table is what the page documents, so
     * without this a reader comparing it with the specification would find operations the documentation does
     * not mention and no reason why.
     */
    private static void writeExcludedNote(MarkdownWriter page, RestApiOverview declared,
                                          RestApiOverview documented) {
        if (declared == null || documented == null) {
            return;
        }
        int excluded = declared.operations().size() - documented.operations().size();
        if (excluded == 0) {
            return;
        }
        page.admonition("note", "Not every operation is documented", Arc42Pages.leftOut(excluded,
                ("One of the %d operations this specification declares is not described here. Open the "
                 + "specification itself to read it.").formatted(declared.operations().size()),
                ("%d of the %d operations this specification declares are not described here. Open the "
                 + "specification itself to read them.").formatted(excluded, declared.operations().size())));
    }

    /**
     * The operations as the model has them, for a component whose specification has not been replicated.
     * <p>
     * The architecture repository keeps no tag, so there is nothing to group by. The page says so rather than
     * inventing a group. The excluded paths apply here too: what a reader is not shown must not depend on
     * whether the specification happens to have been replicated yet.
     */
    private static void writeOperationsFromTheModel(MarkdownWriter page, DocumentedComponent component,
                                                    GenerationContext context) {
        page.heading(2, "Operations");
        List<RestApiOperation> documented = component.restApis().stream()
                .filter(operation -> context.apiPaths().documents(operation.path()))
                .toList();
        if (documented.isEmpty()) {
            page.paragraph("The architecture repository knows that this component publishes a specification "
                           + "and has told this service nothing about its operations. Open the specification "
                           + "itself to read them.");
            return;
        }
        page.paragraph("Grouping the operations needs the published specification, which has not been "
                       + "replicated for this component. Until it is, they are listed as the architecture "
                       + "model has them.");
        page.table(List.of("Method", "Path"), documented.stream()
                .map(operation -> List.of(Md.code(operation.method()), Md.code(operation.path())))
                .toList());
    }

    /**
     * What an operation is for, with a deprecated one saying so first.
     * <p>
     * Through {@code Md.sentence} and not {@code Md.join}, because {@code Md.text(" - ")} would lose its
     * leading space and the two fragments would run together.
     */
    private static Markdown summaryOf(ApiOperation operation) {
        Markdown summary = Md.text(operation.summary());
        if (!operation.deprecated()) {
            return summary;
        }
        return summary.isEmpty()
                ? Md.bold("Deprecated")
                : Md.sentence("{} - {}", Md.bold("Deprecated"), summary);
    }

    private static Markdown version(DocumentedComponent component, RestApiOverview api) {
        if (api != null && api.version() != null && !api.version().isBlank()) {
            return Md.code(api.version());
        }
        return component.openApi() == null ? Md.text(NOT_KNOWN)
                : Md.textOr(component.openApi().version(), NOT_KNOWN);
    }

    private static Markdown serverUrl(DocumentedComponent component, RestApiOverview api) {
        if (api != null && api.serverUrl() != null && !api.serverUrl().isBlank()) {
            return Md.code(api.serverUrl());
        }
        return component.openApi() == null ? Md.text(NOT_KNOWN)
                : Md.textOr(component.openApi().serverUrl(), NOT_KNOWN);
    }

    /** How many operations the page shows: the specification's where there is one, the model's otherwise. */
    private static int operationCountOf(DocumentedComponent component, RestApiOverview api,
                                        GenerationContext context) {
        if (api != null && !api.isEmpty()) {
            return api.operations().size();
        }
        // The model's own list, counted the way the fallback table below writes it.
        return (int) component.restApis().stream()
                .filter(operation -> context.apiPaths().documents(operation.path()))
                .count();
    }

    /**
     * The messages this component produces and consumes.
     * <p>
     * <b>Each is a link into the system's tree</b>, where the message is documented. A message belongs to the
     * system that defines it, not to the components that handle it, so it is not documented twice.
     */
    private static void writeMessages(DocumentedSystem system, DocumentedComponent component,
                                      List<DocumentedMessage> messages, GenerationContext context,
                                      Path directory) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(MESSAGES_LABEL, 3, context))
                .heading(1, MESSAGES_LABEL)
                .paragraph(Md.sentence("The events and commands {} has a contract for, and the topics they "
                                       + "travel on. Each of them is documented with {}, which defines it.",
                        Md.code(component.name()),
                        Md.link(DocumentationPaths.chapter(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW),
                                "the system's building block view")));

        writeContracts(page, system, component, messages, ContractRole.PRODUCES, "Produces");
        writeContracts(page, system, component, messages, ContractRole.CONSUMES, "Consumes");
        writeContracts(page, system, component, messages, ContractRole.UNKNOWN,
                "Contracts With An Unrecognised Role");
        Arc42Pages.provenance(page, context);
        Arc42Pages.write(directory, MESSAGES_PAGE + ".md", page);
    }

    /**
     * One table of contracts. An unrecognised role gets no heading when there are none of them.
     * <p>
     * A role this service does not know is shown rather than guessed at. Guessing a side would be a wrong
     * answer that looks right, and leaving the contract out would hide that the component is involved.
     */
    private static void writeContracts(MarkdownWriter page, DocumentedSystem system,
                                       DocumentedComponent component, List<DocumentedMessage> messages,
                                       ContractRole role, String heading) {
        List<List<Markdown>> rows = new ArrayList<>();
        for (DocumentedMessage message : messages) {
            for (MessageContract contract : contractsOf(message, component, role)) {
                rows.add(List.of(
                        messageLink(system, message),
                        Md.text(message.kind().label()),
                        Md.code(contract.topic()),
                        Md.joinWith(", ", contract.versions().stream().map(Md::code).toList())));
            }
        }
        if (rows.isEmpty() && role == ContractRole.UNKNOWN) {
            return;
        }
        page.heading(2, heading);
        if (rows.isEmpty()) {
            page.paragraph(NONE);
            return;
        }
        if (role == ContractRole.UNKNOWN) {
            page.paragraph("The architecture model names a role this service does not know for these "
                           + "contracts, so which side the component is on is not shown.");
        }
        page.table(MESSAGE_COLUMNS, rows);
    }

    /**
     * Where a message is documented. <b>A link, with no fallback</b>, unlike {@link #endLink} and
     * {@link #systemLink}.
     * <p>
     * It needs none: the rows come from {@link #messagesOf}, which filters {@code system.messages()}, and
     * {@code Arc42MessagePages} writes a page for every one of those in the same run. Widen the source and
     * this needs a fallback, because {@code Md.link} throws on a target it will not put on a page - which
     * would end the generation of every system.
     */
    private static Markdown messageLink(DocumentedSystem system, DocumentedMessage message) {
        String group = message.kind() == MessageKind.COMMAND
                ? Arc42MessagePages.COMMANDS : Arc42MessagePages.EVENTS;
        return Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW, group,
                message.slug()), Md.code(message.name()));
    }

    /**
     * The messages of the system that this component has a contract on. <b>Matched ignoring case</b>, like
     * every other name join here: the two names come from two exports of one upstream.
     */
    private static List<DocumentedMessage> messagesOf(DocumentedSystem system, DocumentedComponent component) {
        return system.messages().stream()
                .filter(message -> !contractsOf(message, component, null).isEmpty())
                .toList();
    }

    /** The contracts of this component on one message, of one role or of any. */
    private static List<MessageContract> contractsOf(DocumentedMessage message, DocumentedComponent component,
                                                     ContractRole role) {
        return message.contracts().stream()
                .filter(contract -> contract.component() != null
                                    && contract.component().equalsIgnoreCase(component.name()))
                .filter(contract -> role == null || contract.role() == role)
                .toList();
    }

    /**
     * Chapter 6: how the component behaves while it runs. The reactions that will fill it are imported
     * separately, so the page says what it is waiting for rather than disappearing and coming back.
     */
    private static void writeRuntimeView(Arc42Template template, DocumentedComponent component,
                                         GenerationContext context,
                                         DocumentationPaths.ComponentPaths paths, Path structure)
            throws IOException {
        Path directory = Arc42Pages.chapterDirectory(template, structure, RUNTIME_VIEW);

        MarkdownWriter index = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(RUNTIME_VIEW.label(), 0, context))
                .heading(1, RUNTIME_VIEW.label())
                .paragraph(Md.sentence("How {} behaves while it runs.", Md.code(component.name())))
                .bulletList(List.of(Md.link(paths.page(RUNTIME_VIEW, COMPONENT_REACTIONS_PAGE),
                        COMPONENT_REACTIONS_LABEL)));
        Arc42Pages.provenance(index, context);
        Arc42Pages.write(directory, Arc42Pages.INDEX, index);

        MarkdownWriter reactions = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(COMPONENT_REACTIONS_LABEL, 1, context))
                .heading(1, COMPONENT_REACTIONS_LABEL)
                .paragraph(Md.sentence("Which message makes {} react, and what it does in answer. The "
                                       + "reactions are observed at runtime and imported from the reaction "
                                       + "observer service; that import is not published yet, so this page "
                                       + "is empty.", Md.code(component.name())))
                .paragraph(Md.sentence("Until then, {} shows what this component exchanges with its "
                                       + "neighbours.",
                        Md.link(paths.page(CONTEXT_AND_SCOPE, COMPONENT_CONTEXT_VIEW_PAGE),
                                "the component context view")));
        Arc42Pages.provenance(reactions, context);
        Arc42Pages.write(directory, COMPONENT_REACTIONS_PAGE + ".md", reactions);
    }

    /**
     * Where an end of an arrow is documented: a component of this system, or another system as a whole.
     * <p>
     * Resolved through the model rather than lower-cased into a path. The ends come from relations and are
     * free text, so a name the model does not carry gets no link instead of a broken one.
     */
    private static Markdown endLink(String name, DocumentedSystem system, GenerationContext context) {
        return system.components().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                .findFirst()
                .map(candidate -> Md.link(DocumentationPaths.component(system.slug(), SYSTEM_SEGMENT,
                        BUILDING_BLOCK_VIEW, candidate.slug()), candidate.name()))
                .orElseGet(() -> systemLink(name, context));
    }

    /** A system name, linked when this run documents it. A link to a missing page fails the site build. */
    private static Markdown systemLink(String name, GenerationContext context) {
        return context.model().systems().stream()
                .filter(documented -> documented.name().equalsIgnoreCase(name))
                .findFirst()
                .map(documented -> Md.link(DocumentationPaths.system(documented.slug()), documented.name()))
                .orElseGet(() -> Md.code(name));
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
}
