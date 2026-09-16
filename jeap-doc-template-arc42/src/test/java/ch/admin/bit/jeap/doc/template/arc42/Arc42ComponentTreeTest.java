package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ApiGroup;
import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchemaReference;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.ObservedReactions;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.OpenApiReference;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.architecture.view.ReactionView;
import ch.admin.bit.jeap.doc.domain.template.DiagramLimits;
import ch.admin.bit.jeap.doc.domain.template.DocumentedApiPaths;
import ch.admin.bit.jeap.doc.domain.template.ReactionViews;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import ch.admin.bit.jeap.doc.markdown.CategoryFile;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole tree of one component, written to disk and read back.
 * <p>
 * This is where the layout of a component's documentation is reviewed: the assertions describe what a reader
 * sees. It writes real files rather than strings in memory, because half of what can go wrong is which file
 * goes in which folder under which name.
 */
class Arc42ComponentTreeTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-28T06:05:02Z");
    private static final Instant MODEL_IMPORTED_AT = Instant.parse("2026-08-28T05:50:00Z");

    /** The bounds the shipped defaults set. A case that is about a bound overrides the one it is about. */
    private static final DiagramLimits LIMITS = new DiagramLimits(100, 4, 40, 100, 200, 40, 20);

    /** Where a component's tree is served, which every link on these pages has to agree with. */
    private static final String STRUCTURE_URL =
            "/systems/orders/system-architecture/building-block-view/components/orders-intake/"
            + "component-architecture/";

    @TempDir
    Path content;

    private Arc42Template template;
    private DocumentedSystem orders;
    private GenerationContext context;
    private Path componentDirectory;

    @BeforeEach
    void setUp() {
        template = new Arc42Template();
        orders = orders(intake());
        context = contextOf(orders, LIMITS);
        componentDirectory = content.resolve("components").resolve("orders-intake");
    }

    private GenerationContext contextOf(DocumentedSystem system, DiagramLimits limits) {
        // A context path and an environment prefix, as a deployment has. A fenced link has to carry both
        // itself; a Markdown link gets them added.
        return new GenerationContext(ArchitectureModel.of(List.of(system, shipping())), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, limits,
                "/docs/dev/");
    }

    /** A landscape of this system alone, so that no neighbour's relation reaches into it. */
    private GenerationContext alone(DocumentedSystem system) {
        return new GenerationContext(ArchitectureModel.of(List.of(system)), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, LIMITS,
                "/docs/dev/");
    }

    private void generate() throws IOException {
        generate(componentOf(orders, "orders-intake"));
    }

    /** When the graphs of these tests were imported, which the pages name. */
    private static final Instant REACTIONS_IMPORTED_AT = Instant.parse("2026-09-09T06:00:00Z");

    /** One message triggering one reaction of this component, as its own graph. */
    private ReactionViews reactionsOfIntake() {
        ObservedReactions observed = new ObservedReactions(
                List.of(new ObservedReactions.ObservedMessage(1, "OrdersPaymentAcceptedEvent", null)),
                List.of(new ObservedReactions.ObservedReaction(2, "orders-intake", null)),
                List.of(new ObservedReactions.ObservedTrigger(1, 2, 12)), List.of());
        return ReactionViews.of(REACTIONS_IMPORTED_AT, ReactionView.empty(),
                Map.of("orders-intake", ReactionView.of(observed, context.model(), orders)), Map.of(),
                Set.of("orders-intake"));
    }

    private void generate(DocumentedComponent component) throws IOException {
        Arc42ComponentPages.write(template, Documented.of(orders), orders, component, context,
                componentDirectory);
    }

    /** A component with everything the architecture repository can know about one. */
    @Test
    void theTreeIsTheOneTheUrlLayoutPromises() throws IOException {
        // With reactions, because chapter 6 is part of the layout wherever something was observed.
        context = context.withReactions(reactionsOfIntake());

        generate();

        assertThat(filesUnder(componentDirectory)).containsExactlyInAnyOrder(
                "component-architecture/_category_.json",
                "component-architecture/index.md",
                "component-architecture/1-intro/_category_.json",
                "component-architecture/1-intro/index.md",
                "component-architecture/3-context-and-scope/_category_.json",
                "component-architecture/3-context-and-scope/index.md",
                "component-architecture/3-context-and-scope/context-view.md",
                "component-architecture/5-building-block-view/_category_.json",
                "component-architecture/5-building-block-view/index.md",
                "component-architecture/5-building-block-view/database-schema.md",
                "component-architecture/5-building-block-view/rest-api.md",
                "component-architecture/5-building-block-view/messages.md",
                "component-architecture/6-runtime-view/_category_.json",
                "component-architecture/6-runtime-view/index.md",
                "component-architecture/6-runtime-view/component-reactions.md");
    }

    /**
     * A component the architecture repository knows nothing else about. It gets chapter 1, no empty chapter
     * and no link to a page that was not written - which is what would fail the site build.
     */
    @Test
    void aComponentWithNothingButANameGetsNoEmptyChapterAndNoDeadLink() throws IOException {
        DocumentedComponent bare = new DocumentedComponent("orders-intake", "orders-intake", null,
                ComponentType.UNKNOWN, null, null, null, List.of(), null, null, null);
        orders = new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(bare), List.of(),
                List.of());
        context = alone(orders);

        generate(bare);

        assertThat(filesUnder(componentDirectory)).containsExactlyInAnyOrder(
                "component-architecture/_category_.json",
                "component-architecture/index.md",
                "component-architecture/1-intro/_category_.json",
                "component-architecture/1-intro/index.md");
        assertThat(read("component-architecture/index.md"))
                .describedAs("the landing page lists the one chapter that exists and no more")
                .contains("1. Introduction and Goals")
                .doesNotContain("3. Context and Scope")
                .doesNotContain("5. Building Block View")
                .describedAs("nothing was observed reacting to it either")
                .doesNotContain("6. Runtime View");
        assertThat(everyPage())
                .describedAs("and no page of the tree links into a chapter that was not written")
                .allSatisfy(page -> assertThat(page).doesNotContain(STRUCTURE_URL + "context-and-scope/")
                        .doesNotContain(STRUCTURE_URL + "building-block-view/")
                        .doesNotContain(STRUCTURE_URL + "runtime-view/"));
    }

    /**
     * The eight chapters with nothing to generate are not created, as in a system's tree. The gap in the
     * numbering is what tells a reader a chapter is unwritten.
     */
    @Test
    void theChaptersWithNothingInThemAreNotCreated() throws IOException {
        generate();

        Path structure = componentDirectory.resolve("component-architecture");
        assertThat(structure.resolve("2-constraints")).doesNotExist();
        assertThat(structure.resolve("4-solution-strategy")).doesNotExist();
        assertThat(structure.resolve("7-deployment-view")).doesNotExist();
        assertThat(structure.resolve("12-glossary")).doesNotExist();
    }

    @Test
    void everyChapterCarriesItsArc42NumberInTheSidebar() throws IOException {
        generate();

        assertThat(read("component-architecture/_category_.json"))
                .contains("\"label\": \"Component Architecture\"");
        assertThat(read("component-architecture/5-building-block-view/_category_.json"))
                .isEqualTo("""
                        {
                          "label": "5. Building Block View",
                          "position": 5
                        }
                        """);
    }

    /**
     * The landing page says what the tree is and which chapters exist. It is where the link on the
     * component's page in the system's tree leads.
     */
    @Test
    void theLandingPageNamesTheComponentTheSystemAndTheChaptersThatExist() throws IOException {
        context = context.withReactions(reactionsOfIntake());

        generate();

        assertThat(read("component-architecture/index.md"))
                .contains("# Component Architecture - orders-intake")
                .contains("/systems/orders/")
                .contains("https://arc42.org/overview/")
                .contains(STRUCTURE_URL + "intro/")
                .contains(STRUCTURE_URL + "context-and-scope/")
                .contains(STRUCTURE_URL + "building-block-view/")
                .contains(STRUCTURE_URL + "runtime-view/");
    }

    /**
     * The arc42 credit sits once per system tree, at the foot of the system's chapter 1. A component is
     * inside that tree, so no page of it repeats it.
     */
    @Test
    void noPageOfAComponentRepeatsTheArc42Attribution() throws IOException {
        generate();

        assertThat(everyPage()).allSatisfy(page ->
                assertThat(page).doesNotContain("Gernot Starke").doesNotContain("CC BY-SA"));
    }

    @Test
    void chapterOne_saysWhatTheComponentIsAndWhoOwnsIt() throws IOException {
        generate();

        assertThat(read("component-architecture/1-intro/index.md"))
                .contains("# 1. Introduction and Goals")
                .contains("Takes payments in")
                .contains("`orders-intake`")
                .contains("Backend Service")
                .describedAs("the owning team with its contact address, which the root page's table has not")
                .contains("mailto:blue@example.com")
                .contains("Team Blue")
                .contains("/systems/orders/")
                .contains("DEPLOYMENT\\_LOG");
    }

    /** A component nothing has seen for a fortnight says so where a reader of its documentation will see it. */
    @Test
    void chapterOne_whenTheComponentHasNotBeenSeenForAFortnight_thenItSaysSo() throws IOException {
        generate();

        assertThat(read("component-architecture/1-intro/index.md"))
                .contains(":::warning[Not seen recently]")
                .contains("may describe something that no longer exists");
    }

    /**
     * The context view: the component in a package with the siblings it exchanges something with, every
     * other system as a single box, and a table of every relation under it.
     */
    @Test
    void theContextView_drawsTheSiblingsAndTheSystemsAndListsEveryRelation() throws IOException {
        generate();

        String page = read("component-architecture/3-context-and-scope/context-view.md");
        assertThat(page).contains("# Component Context View")
                .describedAs("a fenced diagram, never an image")
                .contains("```plantuml")
                .doesNotContain(".png")
                .doesNotContain(".svg")
                .describedAs("the component in the middle, inside its system's package")
                .contains("package \"orders\"")
                .contains("component \"orders-intake\"")
                .describedAs("the sibling it exchanges something with")
                .contains("component \"orders-risk\"")
                .describedAs("and the counterpart of the other system, named and inside a package "
                                     + "for that system - what this component talks to is a component, and "
                                     + "the model knows which")
                .contains("package \"shipping\"")
                .contains("component \"shipping-gateway\"")
                .describedAs("the table of relations below it").contains("## Relations")
                .contains("| From | To | Type | Interaction |")
                .contains("| Event |")
                .contains("OrdersPaymentAcceptedEvent")
                .contains("ShippingArrangedEvent")
                .describedAs("and the table names a foreign counterpart with the system that owns "
                                     + "it, because two systems may each have a component of one name")
                .contains("[shipping-gateway](/systems/shipping/system-architecture/building-block-view/"
                          + "components/shipping-gateway/) ([shipping](/systems/shipping/))");
    }

    /**
     * A link inside a fence carries the base URL and the environment prefix already. Nothing rewrites what
     * is inside a fence, so a root-relative one would point at the service's own root.
     */
    @Test
    void theContextView_carriesTheLinkPrefixInsideTheFence() throws IOException {
        generate();

        assertThat(read("component-architecture/3-context-and-scope/context-view.md"))
                .contains("[[/docs/dev/systems/orders/system-architecture/building-block-view/components/"
                          + "orders-risk/]]")
                .describedAs("a counterpart of another system, under that system's slug")
                .contains("[[/docs/dev/systems/shipping/system-architecture/building-block-view/components/"
                          + "shipping-gateway/]]")
                .describedAs("and the package of that system, so the way into its documentation is kept")
                .contains("[[/docs/dev/systems/shipping/]]");
    }

    /** No content, no page: a component that exchanges nothing gets no chapter 3. */
    @Test
    void theContextView_whenTheComponentExchangesNothing_thenThereIsNoChapterThree() throws IOException {
        DocumentedComponent lonely = componentOf(orders, "orders-intake");
        orders = new DocumentedSystem("orders", "orders", null, List.of(), null, List.of(lonely), List.of(),
                List.of());
        context = alone(orders);

        generate(lonely);

        assertThat(componentDirectory.resolve("component-architecture/3-context-and-scope")).doesNotExist();
        assertThat(read("component-architecture/index.md")).doesNotContain("3. Context and Scope");
    }

    /** The picture is cut, the facts are not: the page says how many counterparts it left out. */
    @Test
    void theContextView_saysWhenItLeavesACounterpartOut() throws IOException {
        context = contextOf(orders, new DiagramLimits(100, 4, 0, 100, 200, 40, 20));

        generate();

        String page = read("component-architecture/3-context-and-scope/context-view.md");
        assertThat(page).contains(":::note[Not every counterpart is drawn")
                .describedAs("how many were left out, agreeing with the count, and no format specifier")
                .contains("One of the 2 counterparts this component exchanges something with is left out")
                .doesNotContain("%d")
                .describedAs("and the table still carries the relation of the sibling it left out")
                .contains("orders-risk")
                .describedAs("the counterpart of the other system is not counted as left out: with "
                                     + "no room to open that system it is drawn whole, and the relation is "
                                     + "on that box")
                .contains("component \"shipping\"");
    }

    /** Chapter 5 lists the three pages that exist, and links to each of them. */
    @Test
    void chapterFive_listsTheDataAndTheInterfaces() throws IOException {
        generate();

        assertThat(read("component-architecture/5-building-block-view/index.md"))
                .contains("# 5. Building Block View")
                .contains(STRUCTURE_URL + "building-block-view/database-schema/")
                .contains(STRUCTURE_URL + "building-block-view/rest-api/")
                .contains(STRUCTURE_URL + "building-block-view/messages/");
    }

    /**
     * The entity relationship diagram: an entity per table, the primary key above the separator, one arrow
     * per foreign key, and the full list of tables with their columns below it.
     */
    @Test
    void theDatabaseSchemaPage_drawsTheDiagramAndListsEveryTable() throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).contains("# Database Schema").contains("`orders_db`").contains("`1.2.3`")
                .contains("```plantuml")
                .contains("entity \"orders_order\"")
                .contains("* id : uuid <<PK>>")
                .contains("  --")
                .contains("party_id : uuid <<FK>>")
                .contains("\"orders_order\" }o--|| \"orders_party\" : party_id")
                .describedAs("the tables with their columns, whatever the diagram had room for")
                .contains("## Tables")
                .contains("### `orders_order`")
                .contains("### `orders_party`")
                .contains("FK to `orders_party`")
                .describedAs("and it says which two tables it left out on purpose")
                .contains("`flyway_schema_history`")
                .contains("The following technical table(s) are not shown in the diagram and the list below");
        assertThat(page.indexOf("Some tables are left out on purpose"))
                .describedAs("above the list with the other reduction notes, rather than under the last of "
                             + "two hundred table sections where it reads as belonging to that table")
                .isLessThan(page.indexOf("## Tables"));
    }

    /**
     * <b>A partitioned schema is documented as the tables it logically has, and says so.</b> The facts row
     * keeps both numbers: a reader has to be able to see that the schema holds a hundred and eight tables
     * and that the page shows three of them, rather than be shown three and told nothing.
     */
    @Test
    void theDatabaseSchemaPage_whenTablesArePartitioned_thenEachFamilyIsOneEntryAndBothCountsAreShown()
            throws IOException {
        DocumentedComponent partitioned = componentOf(orders, "orders-intake")
                .withArtifacts(partitionedSchema(), componentOf(orders, "orders-intake").api());
        orders = orders(partitioned);
        context = contextOf(orders, LIMITS);

        generate(partitioned);

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).describedAs("both numbers, and the second not put down to one of the two reductions "
                                     + "- three of the hundred and five tables the schema hides are machinery")
                .contains("| Tables | 108 (3 documented entries) |")
                .doesNotContain("after grouping partitions")
                .describedAs("one heading per family rather than one per partition")
                .contains("### `doc_meta_*`")
                .contains("### `orders_order`")
                .doesNotContain("### `doc_meta_7`")
                .describedAs("and the arrow reaches the family, not a partition")
                .contains("\"orders_order\" }o--|| \"doc_meta_*\"")
                .describedAs("the convention is named, with one of this schema's own entries as the "
                                     + "example rather than an invented name")
                .contains(":::info[Tables of one name pattern are grouped]")
                .contains("One group of tables of this schema shares a name pattern and a shape")
                .contains("`_*`")
                .contains("`doc_meta_*` is one")
                .describedAs("the entry says what it stands for, so nothing is merely hidden - and "
                                     + "says it as what was observed rather than as a partitioning nothing "
                                     + "here can read")
                .contains("105 tables share this name pattern and this shape, `_1` to `_105`. They are "
                          + "documented as one entry.")
                .doesNotContain("partitions of one table")
                .describedAs("nothing was cut, so neither cutting note is there")
                .doesNotContain("Not every table is drawn")
                .doesNotContain("Not every table is listed");
    }

    /**
     * <b>The one bound on the facts rather than on a picture</b>, so the page has to say how much it did not
     * write and where the rest is. An unbounded list is what cost one component's page an hour and a half.
     */
    @Test
    void theDatabaseSchemaPage_whenThereAreMoreTablesThanTheListMayCarry_thenItSaysHowMany()
            throws IOException {
        context = contextOf(orders, new DiagramLimits(100, 4, 40, 100, 1, 40, 20));

        generate();

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).contains(":::note[Not every table is listed]")
                .contains("This page lists 1 of the 2 entries, by name, and the rest are named nowhere on it.")
                .describedAs("and it offers no way out of the site: the only thing that carries every "
                                     + "entry is the architecture repository's API, which is an internal "
                                     + "address no reader of a published site can follow. The provenance in "
                                     + "the front matter names that upstream, and is not a link")
                .doesNotContain("](https://archrepo")
                .doesNotContain("docs-api")
                .describedAs("the first entry by name is written and the second is not")
                .contains("### `orders_order`")
                .doesNotContain("### `orders_party`")
                .describedAs("and the diagram is not blamed for it: it draws out of the listed "
                                     + "entries, and its own bound of a hundred was nowhere near")
                .doesNotContain("Not every table is drawn");
    }

    /**
     * <b>A cost test rather than a text one</b>, and the one that fails if either bound is lost again. The
     * heading count catches an unbounded list on its own, because the grouping alone would still write 343
     * entries here; the byte ceiling catches the cost, long before Rspack is involved and a build takes an
     * hour.
     */
    @Test
    void theDatabaseSchemaPage_ofASchemaOfThousandsOfPartitions_staysBounded() throws IOException {
        DocumentedComponent huge = componentOf(orders, "orders-intake")
                .withArtifacts(hugePartitionedSchema(), componentOf(orders, "orders-intake").api());
        orders = orders(huge);
        context = contextOf(orders, LIMITS);

        generate(huge);

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page.lines().filter(line -> line.startsWith("### ")).count())
                .describedAs("one heading per listed entry, and 6583 tables are 260 entries of which 200 "
                             + "are listed")
                .isEqualTo(200);
        long bytes = Files.size(componentDirectory
                .resolve("component-architecture/5-building-block-view/database-schema.md"));
        assertThat(bytes).describedAs("200 entries of five columns, which measures 80 kB. One entry per "
                                      + "table and no bound at all, the same page is megabytes")
                .isLessThan(150_000);
    }

    /**
     * The diagram is bounded and the table list is not, so a schema too large to draw still gives a reader a
     * page they can use.
     */
    @Test
    void theDatabaseSchemaPage_whenTheSchemaIsTooLargeToDraw_thenTheListIsStillComplete() throws IOException {
        context = contextOf(orders, new DiagramLimits(100, 4, 40, 1, 200, 40, 20));

        generate();

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).contains(":::note[Not every table is drawn")
                .describedAs("what the picture shows, agreeing with the list it draws from, and no format "
                             + "specifiers")
                .contains("The diagram draws 1 of the 2 listed entries")
                .doesNotContain("%d")
                .describedAs("the list carries both all the same")
                .contains("### `orders_order`")
                .contains("### `orders_party`")
                .describedAs("nothing claims the list is short, because it is not")
                .doesNotContain("Not every table is listed")
                .describedAs("and no arrow points at a table the diagram does not have")
                .doesNotContain("}o--||");
    }

    /**
     * <b>Both bounds at once.</b> The diagram's note promises that the list below carries what the picture
     * left out, which holds only while the list is complete - so where it is not, it stops promising and
     * leaves saying where the rest is to the note that owns that sentence.
     */
    @Test
    void theDatabaseSchemaPage_whenNeitherTheDiagramNorTheListFits_thenTheDiagramPromisesNothing()
            throws IOException {
        // Three entries, of which the list may carry two and the diagram may draw one: both bounds have to
        // bite for this case to exist at all. A list bounded to one would leave the diagram with one entry
        // to draw and one drawn, which is no reduction of the diagram's.
        DocumentedComponent partitioned = componentOf(orders, "orders-intake")
                .withArtifacts(partitionedSchema(), componentOf(orders, "orders-intake").api());
        orders = orders(partitioned);
        context = contextOf(orders, new DiagramLimits(100, 4, 40, 1, 2, 40, 20));

        generate(partitioned);

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).contains(":::note[Not every table is drawn")
                .contains("The diagram draws 1 of the 2 listed entries")
                .contains("the list below is bounded as well, and says where the rest are")
                .describedAs("and it claims nothing about a list that is bounded itself")
                .doesNotContain("carries the one it leaves out")
                .doesNotContain("carries every one it leaves out")
                .describedAs("the note that does say how much is missing")
                .contains(":::note[Not every table is listed]")
                .contains("This page lists 2 of the 3 entries, by name, and the rest are named nowhere on it.");
    }

    /**
     * <b>The page is written as soon as the model says there is a schema</b>, the way the REST API page is.
     * Between an architecture import and the replication of the schema there would otherwise be no entry in
     * the chapter at all, and a reader would have no way to tell a schema that has not arrived from a
     * component that keeps no data.
     */
    @Test
    void theDatabaseSchemaPage_whenNoSchemaWasReplicated_thenItSaysSoAndWhatFixesIt()
            throws IOException {
        DocumentedComponent withoutSchema = componentOf(orders, "orders-intake")
                .withArtifacts(null, componentOf(orders, "orders-intake").api());
        orders = orders(withoutSchema);
        context = contextOf(orders, LIMITS);

        generate(withoutSchema);

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).contains("# Database Schema")
                .contains("has not replicated it yet")
                .describedAs("and it says what fixes that rather than sending the reader anywhere")
                .contains("The next import brings them.")
                .describedAs("the version the model knows is on the page all the same")
                .contains("`1.2.3`")
                .describedAs("and no link into the architecture repository: it is an internal address, "
                                     + "and a reader of a published site cannot follow it. The provenance in "
                                     + "the front matter names that upstream, and is not a link")
                .doesNotContain("](https://archrepo")
                .doesNotContain("docs-api")
                .describedAs("and nothing that would need the replicated copy")
                .doesNotContain("```plantuml")
                .doesNotContain("## Tables");
        assertThat(read("component-architecture/5-building-block-view/index.md"))
                .describedAs("the chapter still lists it, so nothing goes missing silently")
                .contains(STRUCTURE_URL + "building-block-view/database-schema/");
    }

    /** A component the model gives no schema at all gets no page and no entry in the chapter. */
    @Test
    void theDatabaseSchemaPage_isNotWrittenForAComponentWithNoSchema() throws IOException {
        generate(componentOf(orders, "orders-risk"));

        assertThat(componentDirectory.resolve(
                "component-architecture/5-building-block-view/database-schema.md")).doesNotExist();
    }

    /**
     * The REST API page: a table per group with its operations, and the link to the real Swagger UI.
     */
    @Test
    void theRestApiPage_showsAGroupPerTagAndTheSwaggerLink() throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).contains("# REST API").contains("2.4.0")
                .contains("https://orders.example.ch/api")
                .describedAs("the deep link into the architecture repository's Swagger UI")
                .contains("https://archrepo.example.com/archrepo/swagger-ui/index.html")
                .contains("## Orders").contains("Everything about an order")
                .contains("`GET`").contains("`/api/orders`").contains("List the orders")
                .describedAs("a deprecated operation is shown and marked, and reads as a sentence")
                .contains("| **Deprecated** - One order |");
    }

    /**
     * The architecture repository keeps no tag, so a component whose specification has not been replicated
     * cannot be grouped. <b>The page is written all the same</b>, from what the model knows.
     */
    @Test
    void theRestApiPage_whenNoSpecificationWasReplicated_thenTheOperationsComeFromTheModel()
            throws IOException {
        DocumentedComponent withoutSpec = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), null);
        orders = orders(withoutSpec);
        context = contextOf(orders, LIMITS);

        generate(withoutSpec);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).contains("## Operations")
                .contains("Grouping the operations needs the published specification")
                .contains("`/api/orders`")
                .describedAs("and the link to the specification itself is still there")
                .contains("https://archrepo.example.com/archrepo/swagger-ui/index.html");
    }

    /**
     * <b>Who calls an operation is on the page again.</b> It is what the Confluence pages had and what the
     * reader asked for, and it is joined from the relations by method and path - the specification writes a
     * trailing slash the Pact importer does not.
     */
    @Test
    void theRestApiPage_namesTheCallersOfEachOperationAndLinksThem() throws IOException {
        context = contextWithCallers();

        generate();

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page)
                .contains("| Method | Path | Summary | Callers |")
                .describedAs("both callers of the operation, sorted, each linked to its component page")
                .contains("[billing-dunning](/systems/billing/system-architecture/building-block-view/"
                          + "components/billing-dunning/)")
                .contains("[billing-invoices](/systems/billing/system-architecture/building-block-view/"
                          + "components/billing-invoices/)")
                .describedAs("and the Pact contract rides with the caller that has one, above the line")
                .contains(":sup[[pact](https://pacts.example.ch/orders)]")
                .describedAs("an operation nobody is known to call says so the way an empty cell does")
                .contains("| `GET` | `/api/orders/{id}` | **Deprecated** - One order | - |");
    }

    /**
     * <b>A caller of an operation the specification does not declare is named all the same.</b> The
     * architecture model knows concrete paths the published specification has no operation for, and those
     * callers would otherwise be on no row of the page at all.
     */
    @Test
    void theRestApiPage_whenACalledOperationIsNotDeclared_thenItIsNamedWithItsCallers() throws IOException {
        context = contextWithCallers();

        generate();

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page)
                .contains("These operations are called but not declared by the specification:")
                .contains("| Operation | Callers |")
                .contains("| `GET /api/vats/1` |")
                .describedAs("and an operation the specification does declare is not in that note")
                .doesNotContain("`GET /api/orders` |");
    }

    /**
     * What a reader is shown must not depend on whether the specification happens to have been replicated,
     * which is already the rule of the branch that lists the model's operations.
     */
    @Test
    void theRestApiPage_whenNoSpecificationWasReplicated_thenTheCallersAreStillNamed() throws IOException {
        DocumentedComponent withoutSpec = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), null);
        orders = orders(withoutSpec);
        context = contextWithCallers();

        generate(withoutSpec);

        assertThat(read("component-architecture/5-building-block-view/rest-api.md"))
                .contains("| Method | Path | Callers |")
                .contains("billing-invoices");
    }

    /**
     * <b>The actuator is in every jEAP service's specification and is not what a reader came for.</b> On a
     * small service the platform's operational endpoints outnumber the operations that are.
     */
    @Test
    void theRestApiPage_whenPathsAreExcluded_thenTheyAreNeitherListedNorCountedAndTheirAbsenceIsSaid()
            throws IOException {
        DocumentedComponent withActuator = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), apiWithActuator());
        orders = orders(withActuator);
        context = contextWithout(orders, List.of("/actuator(/.*)?"));

        generate(withActuator);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).describedAs("the component's own operations are documented")
                .contains("## Orders").contains("| `GET` | `/api/orders` |")
                .describedAs("and the platform's are not, nor is the group that held only them")
                .doesNotContain("| `GET` | `/actuator")
                .doesNotContain("## Actuator")
                .describedAs("the count is what the page documents")
                .contains("| Operations | 2 |")
                .describedAs("and the page names what it left out and why, so the count can be "
                                     + "compared with the specification")
                .contains("The following technical endpoints are not shown below: `GET /actuator/health`, "
                          + "`GET /actuator/info`, `GET /actuator/metrics`.")
                .describedAs("leaving them out is intended, so it is no warning")
                .doesNotContain(":::note");
    }

    /** A broad exclusion can leave out many operations. The note names twenty and counts the rest. */
    @Test
    void theRestApiPage_whenManyOperationsAreLeftOut_thenTheNoteNamesTwentyAndCountsTheRest() throws IOException {
        List<ApiOperation> internal = new ArrayList<>();
        for (int i = 10; i < 35; i++) {
            internal.add(new ApiOperation("GET", "/internal/" + i, "", false, List.of("Internal")));
        }
        RestApiOverview broad = new RestApiOverview("2.4.0", null, List.of(new ApiGroup("Internal", null,
                internal)));
        DocumentedComponent withInternal = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), broad);
        orders = orders(withInternal);
        context = contextWithout(orders, List.of("/internal/.*"));

        generate(withInternal);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).contains("`GET /internal/10`").contains("`GET /internal/29` and 5 more.")
                .doesNotContain("`GET /internal/30`");
    }

    /** Excluding nothing documents everything, which is what an instance that configures no list gets. */
    @Test
    void theRestApiPage_whenNothingIsExcluded_thenEveryOperationIsDocumentedAndNoNoteIsWritten()
            throws IOException {
        DocumentedComponent withActuator = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), apiWithActuator());
        orders = orders(withActuator);
        context = contextWithout(orders, List.of());

        generate(withActuator);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).contains("`/actuator/health`").contains("| Operations | 5 |")
                .doesNotContain("Left out because");
    }

    /**
     * <b>A specification of nothing but paths the run leaves out.</b> It was replicated and it does declare
     * operations, so neither "not replicated yet" nor "the architecture repository told us nothing" is true -
     * and either would contradict the note directly above it.
     */
    @Test
    void theRestApiPage_whenEveryOperationIsExcluded_thenItSaysSoRatherThanBlamingTheReplication()
            throws IOException {
        DocumentedComponent withActuator = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), apiWithActuator());
        orders = orders(withActuator);
        context = contextWithout(orders, List.of("/api(/.*)?", "/actuator(/.*)?"));

        generate(withActuator);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).describedAs("the count and the note agree that nothing is described here")
                .contains("| Operations | 0 |")
                .contains("The following technical endpoints are not shown below: `GET /actuator/health`, "
                          + "`GET /actuator/info`, `GET /actuator/metrics`, `GET /api/orders`, "
                          + "`GET /api/orders/{id}`.")
                .describedAs("and the page says what is true, with the specification to open")
                .contains("Every operation this specification declares is one this documentation leaves out")
                .contains("[the specification itself](https://archrepo.example.com/archrepo/swagger-ui/"
                          + "index.html")
                .describedAs("neither of the two sentences that would be false")
                .doesNotContain("has not been replicated")
                .doesNotContain("told this service nothing about its operations");
    }

    /**
     * The exclusions reach the fallback too. What a reader is not shown must not depend on whether the
     * specification happens to have been replicated yet.
     */
    @Test
    void theRestApiPage_whenNoSpecificationWasReplicated_thenTheExcludedPathsAreStillLeftOut()
            throws IOException {
        DocumentedComponent withoutSpec = componentOf(orders, "orders-intake")
                .withArtifacts(componentOf(orders, "orders-intake").schema(), null);
        orders = orders(withoutSpec);
        context = contextWithout(orders, List.of("/actuator(/.*)?"));

        generate(withoutSpec);

        String page = read("component-architecture/5-building-block-view/rest-api.md");
        assertThat(page).contains("`/api/orders`").doesNotContain("/actuator");
    }

    /**
     * The messages a component handles, each linked into the system's tree where the message is documented.
     * A message belongs to the system, and is not documented twice.
     */
    @Test
    void theMessagesPage_separatesWhatItProducesFromWhatItConsumesAndLinksBothIntoTheSystem()
            throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/messages.md");
        assertThat(page).contains("# Messages").contains("## Produces").contains("## Consumes")
                .contains("/systems/orders/system-architecture/building-block-view/events/"
                                  + "orders-payment-accepted-event/")
                .contains("/systems/orders/system-architecture/building-block-view/commands/"
                          + "orders-ship-the-order-command/")
                .describedAs("the topic and the versions under contract")
                .contains("`orders-payment`").contains("`1.0.0`");
    }

    /**
     * <b>The other side of a contract is a column.</b> Who consumes what this component publishes, and who
     * publishes what it consumes - read off the message's own contracts, so this page and the message's page
     * cannot disagree.
     */
    @Test
    void theMessagesPage_namesTheConsumersOfWhatItProducesAndThePublishersOfWhatItConsumes()
            throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/messages.md");
        String produces = page.substring(page.indexOf("## Produces"), page.indexOf("## Consumes"));
        String consumes = page.substring(page.indexOf("## Consumes"));
        assertThat(produces)
                .contains("| Message | Kind | Defined by | Topic | Versions | Consumers |")
                .describedAs("the consumer of the event, linked into its own system")
                .contains("[shipping-gateway](/systems/shipping/system-architecture/building-block-view/"
                          + "components/shipping-gateway/)");
        assertThat(consumes)
                .contains("| Message | Kind | Defined by | Topic | Versions | Publishers |")
                .describedAs("the publisher of the command this component consumes, another system's")
                .contains("[shipping-gateway](/systems/shipping/system-architecture/building-block-view/"
                          + "components/shipping-gateway/)");
    }

    /** A role this service does not know has no other side to name, and that table keeps its columns. */
    @Test
    void theMessagesPage_whenARoleIsNotRecognised_thenThatTableGetsNoCounterpartColumn() throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/messages.md");
        String unknown = page.substring(page.indexOf("## Contracts With An Unrecognised Role"));
        assertThat(unknown)
                .contains("| Message | Kind | Defined by | Topic | Versions |")
                .doesNotContain("Consumers")
                .doesNotContain("Publishers");
    }

    /**
     * <b>A message another system defines belongs on the page too.</b> A contract is recorded on the message,
     * and a message belongs to the system that defines it - so an {@code orders} component consuming an event
     * of {@code shipping} has its contract on nothing of {@code orders} at all. Reading only the component's
     * own system dropped every such contract, which is the ordinary case rather than an edge.
     */
    @Test
    void theMessagesPage_showsAMessageDefinedByAnotherSystem() throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/messages.md");
        assertThat(page).describedAs("the event, linked into the tree of the system that defines it")
                .contains("/systems/shipping/system-architecture/building-block-view/events/"
                          + "shipping-dispatched-event/")
                .describedAs("named as another system's, and with its own topic and version")
                .contains("`shipping`").contains("`shipping-dispatch`").contains("`3.1.0`");
    }

    /**
     * <b>And a contract of a same-named component of another system is not this component's.</b> A component
     * name is unique within its system and nowhere else, so matching the name alone attributed
     * {@code shipping}'s {@code orders-intake} to {@code orders}' one - here it would have shown the event as
     * produced as well as consumed.
     */
    @Test
    void theMessagesPage_doesNotShowAContractOfASameNamedComponentOfAnotherSystem() throws IOException {
        generate();

        String page = read("component-architecture/5-building-block-view/messages.md");
        int dispatched = page.split("shipping-dispatched-event", -1).length - 1;
        assertThat(dispatched).describedAs("once, as what this component consumes, and not also as produced")
                .isEqualTo(1);
    }

    /**
     * A component whose every contract is on another system's messages still gets its page. It used to lose
     * the page altogether, and with it chapter 5's link to it.
     */
    @Test
    void theMessagesPage_isWrittenForAComponentThatOnlyContractsOnAnotherSystemsMessages() throws IOException {
        DocumentedSystem withoutMessages = new DocumentedSystem(orders.name(), orders.slug(),
                orders.description(), orders.aliases(), orders.team(), orders.components(),
                orders.relations(), List.of());

        Arc42ComponentPages.write(template, Documented.of(withoutMessages), withoutMessages,
                componentOf(withoutMessages, "orders-intake"),
                contextOf(withoutMessages, LIMITS), componentDirectory);

        assertThat(read("component-architecture/5-building-block-view/messages.md"))
                .contains("## Consumes")
                .contains("/systems/shipping/system-architecture/building-block-view/events/"
                          + "shipping-dispatched-event/");
    }

    /**
     * A role this service does not know is shown rather than guessed at. A wrong side would look right, and
     * leaving the contract out would hide that the component is involved.
     */
    @Test
    void theMessagesPage_showsAContractWhoseRoleIsNotRecognised() throws IOException {
        generate();

        assertThat(read("component-architecture/5-building-block-view/messages.md"))
                .contains("## Contracts With An Unrecognised Role")
                .contains("names a role this service does not know");
    }

    /** A component with no contract at all gets no messages page, and chapter 5 does not link to one. */
    @Test
    void theMessagesPage_isNotWrittenForAComponentWithNoContract() throws IOException {
        generate(componentOf(orders, "orders-risk"));

        assertThat(componentDirectory.resolve(
                "component-architecture/5-building-block-view/messages.md")).doesNotExist();
    }

    /**
     * <b>No reactions, no chapter 6.</b> A component nothing has been observed reacting to gets no page, and
     * the landing page does not offer one.
     */
    @Test
    void theRuntimeView_whenNothingWasObserved_thenTheChapterIsNotWritten() throws IOException {
        generate();

        assertThat(Files.exists(componentDirectory.resolve("component-architecture/6-runtime-view"))).isFalse();
        assertThat(read("component-architecture/index.md")).doesNotContain("Runtime View");
    }

    @Test
    void theRuntimeView_whenReactionsWereObserved_thenTheyAreDrawnAndListed() throws IOException {
        context = context.withReactions(reactionsOfIntake());

        generate();

        assertThat(read("component-architecture/6-runtime-view/component-reactions.md"))
                .contains("# Component Reactions")
                .contains("```dot")
                .contains("digraph \"reactions\"")
                .contains("\"REACTION-2\" [id=\"REACTION-2\"")
                .contains("| Trigger | Component | Action | Median per day |")
                .contains("Observed at runtime by the reaction observer");
        assertThat(read("component-architecture/index.md")).contains("Runtime View");
    }

    /**
     * The page a deep link from a system's graph lands on: the same reaction, addressed by the same id, so
     * the plugin can highlight it.
     */
    @Test
    void theRuntimeView_thenTheReactionCarriesTheIdASystemsGraphLinksTo() throws IOException {
        context = context.withReactions(reactionsOfIntake());

        generate();

        assertThat(read("component-architecture/6-runtime-view/component-reactions.md"))
                .contains("id=\"REACTION-2\"");
    }

    /** Every generated page says where it came from and when, and carries the machine-readable status. */
    @Test
    void everyPageCarriesItsProvenance() throws IOException {
        generate();

        assertThat(everyPage()).allSatisfy(page -> assertThat(page)
                .contains("doc_status: \"generated\"")
                .contains("doc_source: \"archrepo\"")
                .contains("doc_environment: \"dev\"")
                .describedAs("in the front matter, which is where the site template reads it from - a "
                             + "page's own body says nothing about where it came from, because an uploaded "
                             + "page's body is copied byte for byte and could not")
                .contains("doc_status: \"generated\"")
                .doesNotContain(":::info[Generated page]"));
    }

    /**
     * Every description on these pages is free text out of the architecture repository, and has to reach the
     * page through {@code Md}. A template writing one in as a raw string would pass every test of the
     * escaping module itself.
     */
    @Test
    void everyDescriptionFromTheModel_reachesThePageEscaped() throws IOException {
        String hostile = ":::danger\nA <script>alert(1)</script> and *stars* & [brackets]";
        DocumentedComponent nasty = new DocumentedComponent("orders-intake", "orders-intake", hostile,
                ComponentType.BACKEND_SERVICE, new Team("Team Blue", "blue@example.com", null, null),
                "DEPLOYMENT_LOG", ZonedDateTime.parse("2026-01-01T00:00:00Z"), List.of(), null, null, null);
        orders = orders(nasty);
        context = contextOf(orders, LIMITS);

        generate(nasty);

        assertThat(read("component-architecture/1-intro/index.md"))
                .contains("\\:::danger A \\<script\\>alert(1)\\</script\\> and \\*stars\\* &amp; \\[brackets\\]")
                .doesNotContain(hostile);
        assertThat(everyPage())
                .describedAs("and no page has a line of its body opening an admonition of its own")
                .allSatisfy(page -> assertThat(bodyOf(page)).doesNotContainPattern("(?m)^:::danger"));
    }

    /**
     * A name inside a fence has to escape itself: nothing about Markdown reaches into one, and PlantUML
     * reads {@code [[...]]} as a link inside a label too. A table named with brackets would otherwise put a
     * link of somebody else's choosing on the diagram.
     */
    @Test
    void aTableNameInsideTheFenceIsEscapedByTheDiagramItself() throws IOException {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", List.of(new SchemaTable(
                "orders[[https://evil.example/x]]", List.of(new SchemaColumn("id\"", "uuid\"", false)),
                List.of("id\""), List.of())));
        DocumentedComponent component = componentOf(orders, "orders-intake").withArtifacts(schema, null);
        orders = orders(component);
        context = contextOf(orders, LIMITS);

        generate(component);

        String page = read("component-architecture/5-building-block-view/database-schema.md");
        assertThat(page).describedAs("the fence body carries neither the brackets nor a quote")
                .contains("entity \"orders((https://evil.example/x))\"")
                .contains("* id\u2019 : uuid\u2019");
    }

    /** The page without its front matter, which is YAML: it quotes what it carries rather than escaping it. */
    private static String bodyOf(String page) {
        return page.replaceFirst("(?s)^---\n.*?\n---\n", "");
    }

    private String read(String relative) throws IOException {
        return Files.readString(componentDirectory.resolve(relative), StandardCharsets.UTF_8);
    }

    private List<String> everyPage() throws IOException {
        try (Stream<Path> files = Files.walk(componentDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".md"))
                    .map(file -> {
                        try {
                            return Files.readString(file, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }

    private List<String> filesUnder(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static DocumentedComponent componentOf(DocumentedSystem system, String name) {
        return system.components().stream()
                .filter(component -> component.name().equals(name))
                .findFirst().orElseThrow();
    }

    /**
     * The component this test is about. It publishes a specification and a schema, both replicated, and has
     * a contract on an event and on a command.
     */
    private static DocumentedComponent intake() {
        DocumentedComponent component = new DocumentedComponent("orders-intake", "orders-intake",
                "Takes payments in", ComponentType.BACKEND_SERVICE,
                new Team("Team Blue", "blue@example.com", null, null), "DEPLOYMENT_LOG",
                ZonedDateTime.parse("2026-01-01T00:00:00Z"),
                List.of(new RestApiOperation("GET", "/api/orders")),
                new OpenApiReference("2.4.0", "https://orders.example.ch/api",
                        "/archrepo/docs-api/systems/orders/components/orders-intake/openapi",
                        "https://archrepo.example.com/archrepo/swagger-ui/index.html?url=/api/openapi/orders"),
                new DatabaseSchemaReference("1.2.3",
                        "/archrepo/docs-api/systems/orders/components/orders-intake/database-schema"),
                null);
        return component.withArtifacts(schema(), api());
    }

    /** What the replication of the database schema left for this run to render. */
    private static DatabaseSchema schema() {
        SchemaTable order = new SchemaTable("orders_order",
                List.of(new SchemaColumn("id", "uuid", false),
                        new SchemaColumn("party_id", "uuid", true),
                        new SchemaColumn("total", "numeric(12,2)", false)),
                List.of("id"),
                List.of(new SchemaForeignKey("fk_order_party", List.of("party_id"), "orders_party",
                        List.of("id"))));
        SchemaTable party = new SchemaTable("orders_party",
                List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of());
        SchemaTable flyway = new SchemaTable("flyway_schema_history",
                List.of(new SchemaColumn("installed_rank", "integer", false)), List.of(), List.of());
        return new DatabaseSchema("orders_db", "1.2.3", List.of(order, party, flyway));
    }

    /**
     * A hundred and five partitions of one table, two ordinary tables and the Flyway history: a hundred and
     * eight tables that are three entries.
     */
    private static DatabaseSchema partitionedSchema() {
        List<SchemaTable> tables = new ArrayList<>();
        tables.add(new SchemaTable("orders_order",
                List.of(new SchemaColumn("id", "uuid", false), new SchemaColumn("meta_id", "uuid", true)),
                List.of("id"),
                List.of(new SchemaForeignKey("fk_order_meta", List.of("meta_id"), "doc_meta_7",
                        List.of("id")))));
        tables.add(new SchemaTable("orders_party", List.of(new SchemaColumn("id", "uuid", false)),
                List.of("id"), List.of()));
        for (int suffix = 1; suffix <= 105; suffix++) {
            tables.add(new SchemaTable("doc_meta_" + suffix,
                    List.of(new SchemaColumn("id", "uuid", false)), List.of("id"), List.of()));
        }
        tables.add(new SchemaTable("flyway_schema_history",
                List.of(new SchemaColumn("installed_rank", "integer", false)), List.of(), List.of()));
        return new DatabaseSchema("orders_db", "1.2.3", tables);
    }

    /** The measured worst case: 6583 tables that are 260 families of 25 partitions each, five columns apiece. */
    private static DatabaseSchema hugePartitionedSchema() {
        List<SchemaColumn> columns = List.of(new SchemaColumn("id", "uuid", false),
                new SchemaColumn("tenant_id", "uuid", false), new SchemaColumn("created_at", "timestamptz",
                        false), new SchemaColumn("payload", "jsonb", true),
                new SchemaColumn("total", "numeric(12,2)", true));
        List<SchemaTable> tables = new ArrayList<>();
        for (int family = 0; family < 260; family++) {
            for (int shard = 1; shard <= 25; shard++) {
                tables.add(new SchemaTable("doc_part_%03d_table_%d".formatted(family, shard), columns,
                        List.of("id"), List.of()));
            }
        }
        for (int extra = 0; extra < 83; extra++) {
            tables.add(new SchemaTable("plain_%02d_table".formatted(extra), columns, List.of("id"),
                    List.of()));
        }
        return new DatabaseSchema("orders_db", "1.2.3", tables);
    }

    /** A context whose run leaves the given path patterns out of the REST API pages. */
    private GenerationContext contextWithout(DocumentedSystem system, List<String> excluded) {
        return new GenerationContext(ArchitectureModel.of(List.of(system, shipping())), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, LIMITS,
                "/docs/dev/", DocumentedApiPaths.excluding(excluded));
    }

    /**
     * A specification as a jEAP service really publishes one: two operations of its own and three of the
     * platform's, one of them filed under a tag of its own.
     */
    private static RestApiOverview apiWithActuator() {
        return new RestApiOverview("2.4.0", "https://orders.example.ch/api", List.of(
                new ApiGroup("Orders", "Everything about an order", List.of(
                        new ApiOperation("GET", "/api/orders", "List the orders", false, List.of("Orders")),
                        new ApiOperation("GET", "/api/orders/{id}", "One order", false, List.of("Orders")),
                        new ApiOperation("GET", "/actuator/info", "Build information", false,
                                List.of("Orders")))),
                new ApiGroup("Actuator", "The platform's own", List.of(
                        new ApiOperation("GET", "/actuator/health", "Health", false, List.of("Actuator")),
                        new ApiOperation("GET", "/actuator/metrics", "Metrics", false,
                                List.of("Actuator"))))));
    }

    /** And what the replication of the specification left. */
    private static RestApiOverview api() {
        return new RestApiOverview("2.4.0", "https://orders.example.ch/api", List.of(
                new ApiGroup("Orders", "Everything about an order", List.of(
                        new ApiOperation("GET", "/api/orders", "List the orders", false, List.of("Orders")),
                        new ApiOperation("GET", "/api/orders/{id}", "One order", true, List.of("Orders"))))));
    }

    /**
     * The same landscape with a system whose components call the REST API of {@code orders-intake}: one of
     * them through a relation carrying a Pact contract, and both on the same operation.
     */
    private GenerationContext contextWithCallers() {
        return new GenerationContext(ArchitectureModel.of(List.of(orders, shipping(), billing())), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, LIMITS,
                "/docs/dev/");
    }

    /**
     * The caller, which writes its path without the trailing slash the specification has - which is the join
     * this has to survive.
     */
    private static DocumentedSystem billing() {
        return new DocumentedSystem("billing", "billing", "Bills what was ordered", List.of(), null,
                List.of(plainComponent("billing-invoices"), plainComponent("billing-dunning")),
                List.of(new SystemRelation(RelationKind.REST_API, "billing", "billing-invoices", "orders",
                                "orders-intake", null, "GET", "/api/orders",
                                "https://pacts.example.ch/orders"),
                        new SystemRelation(RelationKind.REST_API, "billing", "billing-dunning", "orders",
                                "orders-intake", null, "get", "/api/orders/", null),
                        // A concrete path the specification declares nothing for, which no normalisation
                        // joins onto an operation.
                        new SystemRelation(RelationKind.REST_API, "billing", "billing-invoices", "orders",
                                "orders-intake", null, "GET", "/api/vats/1", null)),
                List.of());
    }

    private static DocumentedComponent plainComponent(String name) {
        return new DocumentedComponent(name, name, null, ComponentType.BACKEND_SERVICE, null, null, null,
                List.of(), null, null, null);
    }

    /**
     * The system the component belongs to: a sibling it exchanges an event with, a relation to another
     * system, and the two messages it has a contract on.
     */
    private static DocumentedSystem orders(DocumentedComponent intake) {
        DocumentedComponent risk = new DocumentedComponent("orders-risk", "orders-risk", "Scores an order",
                ComponentType.BACKEND_SERVICE, new Team("Team Blue", null, null, null), "DEPLOYMENT_LOG",
                ZonedDateTime.parse("2026-08-27T04:00:00Z"), List.of(), null, null, null);
        List<SystemRelation> relations = new ArrayList<>();
        relations.add(new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders",
                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null));
        relations.add(new SystemRelation(RelationKind.EVENT, "shipping", "shipping-gateway", "orders",
                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null));
        return new DocumentedSystem("orders", "orders", "Takes orders and follows them through", List.of(),
                new Team("Team Blue", "blue@example.com", null, null), List.of(intake, risk), relations,
                List.of(
                        new DocumentedMessage("OrdersPaymentAcceptedEvent", "orders-payment-accepted-event",
                                MessageKind.EVENT, "internal", "orders-payment", "The payment was accepted.",
                                null, null, List.of(DocumentedMessageVersion.of("1.0.0")),
                                List.of(new MessageContract(ContractRole.PRODUCES, "ORDERS-INTAKE", "orders",
                                                "orders-payment", List.of("1.0.0")),
                                        // The other side of what this component publishes, which its page
                                        // names in the Consumers column.
                                        new MessageContract(ContractRole.CONSUMES, "shipping-gateway",
                                                "shipping", "orders-payment", List.of("1.0.0")),
                                        new MessageContract(ContractRole.UNKNOWN, "orders-intake", "orders",
                                                "orders-payment", List.of("1.0.0")))),
                        new DocumentedMessage("OrdersShipTheOrderCommand", "orders-ship-the-order-command",
                                MessageKind.COMMAND, "internal", "orders-shipping", "Ship it.", null, null,
                                List.of(DocumentedMessageVersion.of("2.0.0")),
                                List.of(new MessageContract(ContractRole.CONSUMES, "orders-intake", "orders",
                                                "orders-shipping", List.of("2.0.0")),
                                        // And the other side of what it consumes, which is the Publishers
                                        // column.
                                        new MessageContract(ContractRole.PRODUCES, "shipping-gateway",
                                                "shipping", "orders-shipping", List.of("2.0.0"))))));
    }

    /**
     * The neighbour, which defines a message of its own that this component consumes. A relation belongs to
     * the system that defines it, so an inbound edge of {@code orders} is declared here.
     */
    private static DocumentedSystem shipping() {
        return new DocumentedSystem("shipping", "shipping", "Sends the goods out", List.of(), null,
                List.of(new DocumentedComponent("shipping-gateway", "shipping-gateway", null,
                                ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null, null),
                        // A component of another system that happens to carry the same name as the one being
                        // documented. A contract of this one must not be read as a contract of that one.
                        new DocumentedComponent("orders-intake", "orders-intake", null,
                                ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null, null)),
                List.of(new SystemRelation(RelationKind.EVENT, "orders", "orders-intake", "shipping",
                        "shipping-gateway", "ShippingArrangedEvent", null, null, null)),
                List.of(new DocumentedMessage("ShippingDispatchedEvent", "shipping-dispatched-event",
                        MessageKind.EVENT, "internal", "shipping-dispatch", "The goods went out.", null, null,
                        List.of(DocumentedMessageVersion.of("3.1.0")),
                        List.of(
                                // The case the component page used to drop: an orders component contracted on
                                // an event that shipping defines.
                                new MessageContract(ContractRole.CONSUMES, "orders-intake", "orders",
                                        "shipping-dispatch", List.of("3.1.0")),
                                // And shipping's own component of the same name, which is a different one.
                                new MessageContract(ContractRole.PRODUCES, "orders-intake", "shipping",
                                        "shipping-dispatch", List.of("3.1.0"))))));
    }

    /**
     * <b>The anti-drift test, for a component.</b> The same rule as
     * {@code Arc42SystemTreeTest.everyFileWrittenIntoAChapterIsAReservedName}: everything the template writes
     * into a chapter is a name an upload may not reuse, and {@code generatedNames} is where that is declared.
     * A page added here without being declared fails in this module rather than as a duplicate route in a
     * site build.
     */
    @Test
    void everyFileWrittenIntoAChapterIsAReservedName() throws IOException {
        context = context.withReactions(reactionsOfIntake());

        generate();

        Path structure = componentDirectory.resolve("component-architecture");
        assertThat(structure).isDirectory();
        int chaptersChecked = 0;
        try (Stream<Path> chapters = Files.list(structure)) {
            for (Path chapter : chapters.filter(Files::isDirectory).toList()) {
                chaptersChecked++;
                assertChapterHoldsOnlyReservedNames(chapter);
            }
        }
        assertThat(chaptersChecked).describedAs("the chapters this template generates into for a component")
                .isEqualTo(4);
    }

    private void assertChapterHoldsOnlyReservedNames(Path chapter) throws IOException {
        StructureChapter declared = template.chapterOfFolder(chapter.getFileName().toString()).orElseThrow(
                () -> new AssertionError("The generator wrote a folder that is no chapter of the template: "
                                         + chapter.getFileName()));
        Set<String> generated = template.generatedNames(declared, SubjectKind.COMPONENT);
        try (Stream<Path> entries = Files.list(chapter)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (CategoryFile.NAME.equals(name) || "index.md".equals(name)) {
                    continue;
                }
                String withoutExtension = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
                assertThat(generated)
                        .describedAs("%s/%s is written by the generator and has to be declared in "
                                     + "Arc42Template.generatedNames(%s, COMPONENT), or an upload carrying a "
                                     + "page of that name would be accepted and then fail this part's build",
                                chapter.getFileName(), name, declared.folder())
                        .contains(withoutExtension);
            }
        }
    }
}
