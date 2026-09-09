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
import java.util.Comparator;
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
    private static final String OPERATIONS_LABEL = "Operations";
    private static final String TOPIC_LABEL = "Topic";
    private static final String VERSIONS_LABEL = "Versions";
    private static final String NONE = "None.";

    /** The columns of the two contract tables on the messages page. */
    /**
     * <b>The defining system is a column of its own.</b> A component contracts on the messages of other
     * systems as readily as on its own - consuming another system's event is the ordinary case - and the two
     * are indistinguishable in a table that names only the message.
     */
    private static final List<String> MESSAGE_COLUMNS =
            List.of("Message", "Kind", "Defined by", TOPIC_LABEL, VERSIONS_LABEL);

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
                                       + "components of the systems around it, and what travels between "
                                       + "them. Each is drawn inside the system that owns it, and only the "
                                       + "ones this component exchanges something with are named - a box for "
                                       + "another system is not that system's decomposition. A solid arrow "
                                       + "is a message, a dotted one a REST call.",
                        Md.code(component.name()),
                        Md.link(DocumentationPaths.system(system.slug()), system.name())));

        if (componentContext.isEmpty()) {
            page.paragraph("The architecture model records no relation between this component and anything "
                           + "else. It exchanges nothing that any importer has seen.");
        } else {
            PlantUmlViews.Diagram diagram = PlantUmlViews.componentContextView(componentContext, context);
            page.fence(PlantUmlViews.LANGUAGE, diagram.source());
            int counterparts = componentContext.counterparts().size();
            if (componentContext.truncated() > 0) {
                page.admonition("note", "Not every counterpart is drawn",
                        Arc42Pages.leftOut(componentContext.truncated(),
                                ("One of the %d counterparts this component exchanges something with is left "
                                 + "out of the diagram so that it stays readable. The table below names it.")
                                        .formatted(counterparts),
                                ("%d of the %d counterparts this component exchanges something with are left "
                                 + "out of the diagram so that it stays readable. The table below lists every "
                                 + "one of them.")
                                        .formatted(componentContext.truncated(), counterparts)));
            }
            writeUnplacedNote(page, componentContext);
            page.heading(2, "Relations");
            page.table(List.of("From", "To", "Kind", "What travels"), componentContext.edges().stream()
                    .map(edge -> List.of(
                            endLink(edge.from()),
                            endLink(edge.to()),
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
        List<ContractedMessage> messages = contractedMessagesOf(system, component, context);
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
     * <b>Both are bounded, and each bound says so on the page.</b> The list too, because one component's
     * schema of 6583 tables gave the page 33 527 rows of columns and an hour and a half of build time. Tables
     * that share a name pattern and a shape are collapsed into one entry before either bound applies, which
     * is what keeps the bound on the list from ever reaching almost every schema. The diagram is drawn from
     * the entries the list carries.
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
        if (documented != null) {
            facts.add(List.of(Md.text("Tables"), tableCount(documented)));
        }
        page.table(List.of("", ""), facts);

        if (schema == null) {
            page.paragraph("The architecture repository knows that this component publishes a database "
                           + "schema and this service has not replicated it yet, so there is no diagram and "
                           + "no list of tables. The next import brings them.");
            Arc42Pages.provenance(page, context);
            Arc42Pages.write(directory, DATABASE_SCHEMA_PAGE + ".md", page);
            return;
        }

        if (documented.isEmpty()) {
            page.paragraph("The published schema holds no table this documentation shows.");
            writeHiddenNote(page, documented);
        } else {
            page.fence(PlantUmlViews.LANGUAGE, PlantUmlViews.databaseSchema(documented).source());
            // Four reductions can apply to one page - partitions grouped, the diagram cut, the list cut, the
            // machinery hidden - and each of them says so in its own note. They stand together above the
            // list, because a reader who cannot tell them apart cannot tell what is missing from what is
            // merely summarised - and under two hundred table sections the last of them reads as belonging
            // to the last table.
            writeDiagramNote(page, documented);
            writePartitionNote(page, documented);
            writeListNote(page, documented);
            writeHiddenNote(page, documented);
            writeTables(page, documented);
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

    /**
     * What the picture leaves out. About the picture only: the list below carries those entries.
     * <p>
     * <b>Unless the list is bounded as well.</b> Then it does not carry all of them either, and the note
     * saying so is {@link #writeListNote}'s - so this one stops promising rather than contradicting it.
     * <p>
     * <b>And nothing at all where the diagram drew every entry the page lists.</b> The diagram is drawn out of
     * the listed entries, so a bounded list makes it smaller without its own bound being anywhere near - and
     * this note would tell the reader the renderer was the constraint when it was not. What the list left out
     * is the list note's to say.
     */
    private static void writeDiagramNote(MarkdownWriter page, DocumentedSchema documented) {
        int notDrawn = documented.notDrawn();
        if (notDrawn == 0) {
            return;
        }
        int entries = documented.listed().size();
        String bounded = ("The diagram draws %d of the %d listed entries. It is bounded because the engine "
                          + "lays a picture out by recursion, so a larger one renders as nothing at all; ")
                .formatted(entries - notDrawn, entries);
        if (documented.notListed() > 0) {
            page.admonition("note", "Not every table is drawn",
                    Md.text(bounded + "the list below is bounded as well, and says where the rest are."));
            return;
        }
        page.admonition("note", "Not every table is drawn", Arc42Pages.leftOut(notDrawn,
                bounded + "the list below carries the one it leaves out.",
                bounded + "the list below carries every one it leaves out."));
    }

    /**
     * Which machinery tables the page left out, named rather than dropped silently - so that nobody has to
     * guess whether the schema or the documentation is the incomplete one.
     */
    private static void writeHiddenNote(MarkdownWriter page, DocumentedSchema documented) {
        if (documented.hiddenTables().isEmpty()) {
            return;
        }
        page.admonition("info", "Some tables are left out on purpose", Md.sentence(
                "The machinery of a schema is not the data of the component, so neither the diagram nor "
                + "the list carries {}.",
                Md.joinWith(", ", documented.hiddenTables().stream().map(Md::code).toList())));
    }

    /**
     * That tables sharing a name pattern and a shape were grouped, and what to read a {@code _*} as.
     * <b>Named rather than left to be guessed</b>: a reader who does not know the convention would read one
     * entry as one table.
     * <p>
     * <b>Worded for what is known, which is less than "these are partitions".</b> The grouping reads a name
     * pattern and compares columns and primary keys - see {@code ShardFamilies} - and a published schema says
     * nothing about what a table is for. Tables kept deliberately apart, one per year or per version, look
     * exactly like partitions to it. So the page says what it saw and lets the reader check it against the
     * count and the range.
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
        page.admonition("info", "Tables of one name pattern are grouped", Md.join(
                Arc42Pages.leftOut(families,
                        "One group of tables of this schema shares a name pattern and a shape - the same "
                        + "columns and the same primary key, which is what a partitioned table looks like. It "
                        + "is documented as one entry, under the shared part of the name with a ",
                        ("%d groups of tables of this schema each share a name pattern and a shape - the same "
                         + "columns and the same primary key, which is what a partitioned table looks like. "
                         + "Each is documented as one entry, under the shared part of the name with a ")
                                .formatted(families)),
                Md.code("_*"),
                Md.sentence(" postfix - {} is one. Every such entry says how many tables it stands for, and "
                            + "their range.", Md.code(example))));
    }

    /**
     * That the list itself is bounded. <b>The only note about content the page does not write at all</b>, so
     * it says how much, and it says it in the entries a reader can count on the page.
     * <p>
     * <b>And it links nothing.</b> The only other place that carries every entry is the architecture
     * repository's own API, which is not a page: it is an internal address a reader of a published site
     * cannot follow, and a link to it read as an offer that answered nothing. The diagram and the entries
     * that are here are what the page has.
     */
    private static void writeListNote(MarkdownWriter page, DocumentedSchema documented) {
        if (documented.notListed() == 0) {
            return;
        }
        page.admonition("note", "Not every table is listed", Md.sentence(
                "This page lists {} of the {} entries, by name, and the rest are named nowhere on it.",
                Md.text(String.valueOf(documented.listed().size())),
                Md.text(String.valueOf(documented.documentedCount()))));
    }

    /**
     * How many tables the page documents. Both numbers where they differ, because a reader has to be able to
     * see that the schema holds 6583 tables and that the page shows 260 of them - rather than be shown 260
     * and told nothing.
     * <p>
     * <b>The second number is not named after one of the two reductions.</b> Machinery tables are left out and
     * partitions are grouped, and a cell reading "after grouping partitions" would put the hidden tables down
     * to the grouping. Which reduction did what is what the two notes below the diagram say.
     */
    private static Markdown tableCount(DocumentedSchema documented) {
        if (documented.rawTableCount() == documented.documentedCount()) {
            return Md.text(String.valueOf(documented.documentedCount()));
        }
        return Md.text("%d (%d documented entries)"
                .formatted(documented.rawTableCount(), documented.documentedCount()));
    }

    /** Every entry with its columns, including the ones the diagram had no room for. */
    private static void writeTables(MarkdownWriter page, DocumentedSchema documented) {
        page.heading(2, "Tables");
        for (SchemaTable table : documented.listed()) {
            page.heading(3, Md.code(table.name()));
            if (table.shards() != null) {
                // Summarised, never hidden: the count and the range are what let a reader see that this one
                // entry stands for a hundred and twenty-five tables. And said as what was observed - the
                // grouping reads a name pattern and a shape, and cannot know that they are partitions.
                page.paragraph(Md.sentence("{} tables share this name pattern and this shape, {} to {}. They "
                                           + "are documented as one entry.",
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
        facts.add(List.of(Md.text(OPERATIONS_LABEL),
                Md.text(String.valueOf(operationCountOf(component, api, context)))));
        page.table(List.of("", ""), facts);

        writeExcludedNote(page, declared, api);
        if (declared == null || api == null) {
            writeOperationsFromTheModel(page, component, context);
        } else if (api.isEmpty()) {
            writeEveryOperationExcluded(page, component, declared);
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
     * That the specification is replicated and describes nothing the documentation shows, because every
     * operation it declares is a path the run leaves out.
     * <p>
     * <b>Both other branches would be false here.</b> Listing the model's operations says the specification
     * has not been replicated, or that the architecture repository knows of no operation - and the
     * specification is there, with operations in it.
     */
    private static void writeEveryOperationExcluded(MarkdownWriter page, DocumentedComponent component,
                                                    RestApiOverview declared) {
        page.heading(2, OPERATIONS_LABEL);
        if (declared.isEmpty()) {
            page.paragraph("The published specification declares no operation.");
            return;
        }
        page.paragraph(Md.sentence("Every operation this specification declares is one this documentation "
                                   + "leaves out, so there is nothing to group here. Open {} to read them.",
                specification(component)));
    }

    /** The specification, as a link where the model knows a Swagger UI for it. */
    private static Markdown specification(DocumentedComponent component) {
        return component.openApi() == null ? Md.text("the specification itself")
                : Md.linkOrCode(component.openApi().swaggerUrl(), "the specification itself");
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
        page.heading(2, OPERATIONS_LABEL);
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
     * <b>Each is a link into the tree of the system that defines it</b>, which is not always this component's
     * own: a message belongs to the system that defines it rather than to the components that handle it, so it
     * is documented once and linked from everywhere it is contracted on. Where that system is another one the
     * link leaves this part of the site, and {@code CrossPartLinks} rewrites it.
     */
    private static void writeMessages(DocumentedSystem system, DocumentedComponent component,
                                      List<ContractedMessage> messages, GenerationContext context,
                                      Path directory) throws IOException {
        MarkdownWriter page = new MarkdownWriter()
                .frontMatter(Arc42Pages.generated(MESSAGES_LABEL, 3, context))
                .heading(1, MESSAGES_LABEL)
                .paragraph(Md.sentence("The events and commands {} has a contract for, and the topics they "
                                       + "travel on. Each of them is documented with the system that defines "
                                       + "it, which is {} for the messages of its own and the defining system "
                                       + "itself for the rest.",
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
                                       DocumentedComponent component, List<ContractedMessage> messages,
                                       ContractRole role, String heading) {
        List<List<Markdown>> rows = new ArrayList<>();
        for (ContractedMessage contracted : messages) {
            DocumentedMessage message = contracted.message();
            for (MessageContract contract : contractsOf(message, component, system, role)) {
                rows.add(List.of(
                        messageLink(contracted.definedBy(), message),
                        Md.text(message.kind().label()),
                        systemLink(contracted.definedBy(), system),
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
     * Where a message is documented. <b>A link, with no fallback</b>, unlike {@link #endLink}.
     * <p>
     * It needs none, and still does not now that the source is the whole landscape: the systems come from
     * {@link #contractedMessagesOf}, which walks {@code context.model().systems()} and takes each message from
     * the system whose {@code messages()} holds it - and {@code Arc42MessagePages} writes a page for every
     * message of every one of those systems in the same run. A message therefore always has a page, and the
     * slug it is built from always exists. What a widening here would have to keep is that pairing: build the
     * link from anything other than the system that owns the message and it can name a page nobody writes,
     * because {@code Md.link} throws on a target it will not put on a page - which would end the generation of
     * every system.
     */
    private static Markdown messageLink(DocumentedSystem system, DocumentedMessage message) {
        String group = message.kind() == MessageKind.COMMAND
                ? Arc42MessagePages.COMMANDS : Arc42MessagePages.EVENTS;
        return Md.link(DocumentationPaths.page(system.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW, group,
                message.slug()), Md.code(message.name()));
    }

    /** The system that defines a message, named plainly for the component's own and linked for another's. */
    private static Markdown systemLink(DocumentedSystem definedBy, DocumentedSystem own) {
        if (definedBy.slug().equals(own.slug())) {
            return Md.text("this system");
        }
        return Md.link(DocumentationPaths.chapter(definedBy.slug(), SYSTEM_SEGMENT, BUILDING_BLOCK_VIEW),
                Md.code(definedBy.name()));
    }

    /** One message this component has a contract on, and the system that defines it. */
    private record ContractedMessage(DocumentedSystem definedBy, DocumentedMessage message) {
    }

    /**
     * Every message this component has a contract on, <b>wherever in the landscape it is defined</b>, each
     * with the system that defines it.
     * <p>
     * <b>The whole model and not this component's own system.</b> A message belongs to the system that defines
     * it, and a component consuming another system's event is the ordinary case rather than an edge - the
     * architecture repository records the contract on the message, so the contract of an {@code orders}
     * component on an event of {@code payments} is on that event and on nothing of {@code orders}. Filtering
     * {@code system.messages()} therefore dropped every such contract, and a component that only consumes
     * other systems' messages lost the page altogether.
     * <p>
     * <b>The component's own system first</b>, then the rest by slug, so that the ordinary reading is the
     * unsurprising one and two runs over one model produce the same page.
     */
    private static List<ContractedMessage> contractedMessagesOf(DocumentedSystem system,
                                                                DocumentedComponent component,
                                                                GenerationContext context) {
        List<DocumentedSystem> landscape = new ArrayList<>();
        context.model().systems().stream()
                .filter(each -> each.slug().equals(system.slug()))
                .forEach(landscape::add);
        if (landscape.isEmpty()) {
            // The system being written is not in the model of this run - which the tests do, and which a
            // generation over a partial landscape would. Its own messages are still its own.
            landscape.add(system);
        }
        context.model().systems().stream()
                .filter(each -> !each.slug().equals(system.slug()))
                .sorted(Comparator.comparing(DocumentedSystem::slug))
                .forEach(landscape::add);

        List<ContractedMessage> contracted = new ArrayList<>();
        for (DocumentedSystem defining : landscape) {
            for (DocumentedMessage message : defining.messages()) {
                if (!contractsOf(message, component, system, null).isEmpty()) {
                    contracted.add(new ContractedMessage(defining, message));
                }
            }
        }
        return List.copyOf(contracted);
    }

    /**
     * The contracts of this component on one message, of one role or of any. <b>Matched ignoring case</b>, like
     * every other name join here: the two names come from two exports of one upstream.
     * <p>
     * <b>The system is part of the match.</b> A component name is unique within its system and nowhere else -
     * two systems each having a {@code gateway} is ordinary - so matching the name alone attributed one
     * system's contracts to the other's component of that name. Where the model does not say which system a
     * contract's component belongs to the name is all there is, and it is matched on its own.
     *
     * @param system the system the documented component belongs to
     */
    private static List<MessageContract> contractsOf(DocumentedMessage message, DocumentedComponent component,
                                                     DocumentedSystem system, ContractRole role) {
        return message.contracts().stream()
                .filter(contract -> contract.component() != null
                                    && contract.component().equalsIgnoreCase(component.name()))
                .filter(contract -> contract.system() == null
                                    || contract.system().equalsIgnoreCase(system.name()))
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
     * One end of a relation, linked to its own page where this run writes one.
     * <p>
     * A counterpart of another system is named with the system it belongs to, because two systems may each
     * have a component of one name and the table would otherwise say which of them nothing at all. The
     * slugs come from the view, which resolved them from the model.
     */
    private static Markdown endLink(ComponentContext.Node node) {
        if (node.isSystem()) {
            return node.systemSlug() == null ? Md.code(node.label())
                    : Md.link(DocumentationPaths.system(node.systemSlug()), node.label());
        }
        Markdown component = node.componentSlug() == null || node.systemSlug() == null
                ? Md.code(node.label())
                : Md.link(DocumentationPaths.component(node.systemSlug(), SYSTEM_SEGMENT,
                        BUILDING_BLOCK_VIEW, node.componentSlug()), node.label());
        if (node.kind() != ComponentContext.NodeKind.NEIGHBOUR_COMPONENT) {
            return component;
        }
        Markdown owner = node.systemSlug() == null ? Md.code(node.systemName())
                : Md.link(DocumentationPaths.system(node.systemSlug()), node.systemName());
        // Md.sentence and not Md.join with a " (" fragment: Md.text drops leading whitespace, so the name
        // and its system would run together.
        return Md.sentence("{} ({})", component, owner);
    }

    /**
     * The counterparts the model names without the system that owns them. They are on the table like every
     * other relation and on the diagram nowhere, so the page says that rather than leaving a reader to
     * count the boxes.
     */
    private static void writeUnplacedNote(MarkdownWriter page, ComponentContext context) {
        int unplaced = context.unplaced().size();
        if (unplaced == 0) {
            return;
        }
        String pattern = unplaced == 1
                ? "One counterpart is named without the system that owns it, so the diagram has no box for "
                  + "it: {}. The table below carries its relations."
                : ("%d counterparts are named without the system that owns them, so the diagram has no box "
                   + "for them: {}. The table below carries their relations.").formatted(unplaced);
        page.admonition("note", "Not every counterpart can be placed", Md.sentence(pattern,
                Md.joinWith(", ", context.unplaced().stream().map(node -> Md.code(node.label())).toList())));
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
