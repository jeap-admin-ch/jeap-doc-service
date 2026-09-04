package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.architecture.ArchitectureModel;
import ch.admin.bit.jeap.doc.domain.architecture.ComponentType;
import ch.admin.bit.jeap.doc.domain.architecture.ContractRole;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedComponent;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessage;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedMessageVersion;
import ch.admin.bit.jeap.doc.domain.architecture.MessageSchema;
import ch.admin.bit.jeap.doc.domain.architecture.DocumentedSystem;
import ch.admin.bit.jeap.doc.domain.architecture.MessageContract;
import ch.admin.bit.jeap.doc.domain.architecture.MessageKind;
import ch.admin.bit.jeap.doc.domain.architecture.RelationKind;
import ch.admin.bit.jeap.doc.domain.architecture.SystemRelation;
import ch.admin.bit.jeap.doc.domain.architecture.Team;
import ch.admin.bit.jeap.doc.domain.template.GenerationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole tree of one system, written to disk and read back.
 * <p>
 * This is where the layout of the documentation is reviewed: what the assertions describe is what a reader
 * sees. It writes real files rather than asserting on strings in memory, because half of what can go wrong is
 * <i>which file, in which folder, under which name</i>.
 */
class Arc42SystemTreeTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-28T06:05:02Z");
    private static final Instant MODEL_IMPORTED_AT = Instant.parse("2026-08-28T05:50:00Z");

    @TempDir
    Path content;

    private Arc42Template template;
    private DocumentedSystem orders;
    private GenerationContext context;
    private Path systemDirectory;

    @BeforeEach
    void setUp() {
        template = new Arc42Template();
        orders = orders();
        ArchitectureModel model = ArchitectureModel.of(List.of(orders, shipping()));
        // A context path and an environment prefix, because that is what a deployment looks like and what a
        // diagram's own links have to carry - a Markdown link gets both added for it, a fenced one does not.
        context = new GenerationContext(model, "dev", "https://archrepo.example.com/archrepo",
                MODEL_IMPORTED_AT, GENERATED_AT, 100, 4, "/docs/dev/");
        systemDirectory = content.resolve("systems").resolve("orders");
    }

    private void generate() throws IOException {
        template.writeSystem(orders, context, systemDirectory);
    }

    @Test
    void theTreeIsTheOneTheUrlLayoutPromises() throws IOException {
        generate();

        assertThat(filesUnder(systemDirectory)).containsExactlyInAnyOrder(
                "system-architecture/_category_.json",
                "system-architecture/index.md",
                "system-architecture/1-intro/_category_.json",
                "system-architecture/1-intro/index.md",
                "system-architecture/3-context-and-scope/_category_.json",
                "system-architecture/3-context-and-scope/index.md",
                "system-architecture/3-context-and-scope/system-context-view.md",
                "system-architecture/5-building-block-view/_category_.json",
                "system-architecture/5-building-block-view/index.md",
                "system-architecture/5-building-block-view/whitebox-view.md",
                "system-architecture/5-building-block-view/components/_category_.json",
                "system-architecture/5-building-block-view/components/index.md",
                "system-architecture/5-building-block-view/components/orders-intake/_category_.json",
                "system-architecture/5-building-block-view/components/orders-intake/index.md",
                "system-architecture/5-building-block-view/components/orders-risk/_category_.json",
                "system-architecture/5-building-block-view/components/orders-risk/index.md",
                "system-architecture/5-building-block-view/events/_category_.json",
                "system-architecture/5-building-block-view/events/index.md",
                "system-architecture/5-building-block-view/events/orders-payment-accepted-event.md",
                "system-architecture/5-building-block-view/commands/_category_.json",
                "system-architecture/5-building-block-view/commands/index.md",
                "system-architecture/5-building-block-view/commands/orders-check-erp-availability-v2-command.md",
                "system-architecture/6-runtime-view/_category_.json",
                "system-architecture/6-runtime-view/index.md",
                "system-architecture/6-runtime-view/system-reactions.md");
    }

    /**
     * The eight chapters with nothing to generate are not created at all. An empty chapter claims there is
     * content when there is none, and the gap in the numbering is what tells a reader it is unwritten.
     */
    @Test
    void theChaptersWithNothingInThemAreNotCreated() throws IOException {
        generate();

        Path structure = systemDirectory.resolve("system-architecture");
        assertThat(structure.resolve("2-constraints")).doesNotExist();
        assertThat(structure.resolve("7-deployment-view")).doesNotExist();
        assertThat(structure.resolve("9-architecture-decision-records")).doesNotExist();
        assertThat(structure.resolve("12-glossary")).doesNotExist();
    }

    /**
     * The number is stripped from the URL by the site generator, so the sidebar has to put it back - which is
     * what the category file is for.
     */
    @Test
    void everyChapterCarriesItsArc42NumberInTheSidebar() throws IOException {
        generate();

        assertThat(read("system-architecture/5-building-block-view/_category_.json"))
                .isEqualTo("""
                        {
                          "label": "5. Building Block View",
                          "position": 5
                        }
                        """);
        assertThat(read("system-architecture/_category_.json")).contains("\"label\": \"System Architecture\"");
        assertThat(read("system-architecture/5-building-block-view/events/_category_.json"))
                .contains("\"label\": \"Events\"", "\"position\": 3");
    }

    @Test
    void chapterOne_carriesTheBasicInformationFromTheArchitectureModel() throws IOException {
        generate();

        assertThat(read("system-architecture/1-intro/index.md"))
                .contains("# 1. Introduction and Goals")
                .contains("Takes orders and follows them through")
                .contains("| Responsible team | [Team Blue](mailto:blue@example.com) |")
                .contains("| Also known as | `ORDERS` |")
                .contains("doc_status: \"generated\"");
    }

    /**
     * arc42 is CC BY-SA, so it is credited - once, where a reader arrives to find out what this documentation
     * is, and on no other page of the site.
     */
    @Test
    void arc42IsCreditedOnChapterOneAndNowhereElse() throws IOException {
        generate();

        assertThat(read("system-architecture/1-intro/index.md"))
                .contains("Gernot Starke and Peter Hruschka")
                .contains("CC BY-SA 4.0");
        assertThat(everyPageBut("system-architecture/1-intro/index.md"))
                .noneMatch(page -> page.contains("CC BY-SA"));
    }

    /**
     * The source has to be recognizable on every page.
     */
    @Test
    void everyGeneratedPageSaysWhereItCameFrom() throws IOException {
        generate();

        assertThat(everyPageBut()).allSatisfy(page -> assertThat(page)
                .contains("doc_source: \"archrepo\"")
                .contains("doc_source_url: \"https://archrepo.example.com/archrepo\"")
                .contains("doc_environment: \"dev\"")
                .contains(":::info[Generated page]"));
    }

    @Test
    void theContextViewIsAFencedDiagramAndATableOfNeighbours() throws IOException {
        generate();

        String page = read("system-architecture/3-context-and-scope/system-context-view.md");
        assertThat(page)
                .contains("```plantuml")
                .contains("@startuml")
                .contains("left to right direction")
                .contains("@enduml")
                .doesNotContain(".png")
                .doesNotContain(".svg");
        assertThat(page).contains("| From | To | Kind | What travels |");
        assertThat(page).contains("[shipping](/systems/shipping/)");
        // A Markdown link is rewritten on its way to the reader; a link inside a fence is not, so it has to
        // carry the base URL and the environment prefix already.
        assertThat(page).contains("[[/docs/dev/systems/shipping/]]");
    }

    @Test
    void theWhiteboxViewDrawsTheComponentsAndListsThem() throws IOException {
        generate();

        String page = read("system-architecture/5-building-block-view/whitebox-view.md");
        assertThat(page)
                .contains("# Level 1: Whitebox View orders")
                .contains("```plantuml")
                .contains("package \"orders\"")
                .contains("| Component | Type | Owner | Description |")
                .contains("[orders-intake](/systems/orders/system-architecture/building-block-view/components/orders-intake/)")
                .contains("[[/docs/dev/systems/orders/system-architecture/building-block-view/components/orders-intake/]]")
                .contains("Backend Service");
        assertThat(page).describedAs("a whitebox view is a deep graph and belongs top to bottom")
                .doesNotContain("left to right direction");
    }

    /**
     * Every relation the diagrams draw as an arrow, in full. It is what makes a summarized label honest: an
     * arrow reading <i>5 Events</i> hides five names, and criterion S-050 asks for them.
     */
    @Test
    void theWhiteboxViewListsEveryRelationAndLinksWhatTravels() throws IOException {
        generate();

        String page = read("system-architecture/5-building-block-view/whitebox-view.md");
        assertThat(page)
                .contains("## Relations")
                .contains("| From | To | Kind | What travels |")
                .contains("[orders-intake](/systems/orders/system-architecture/building-block-view/components/orders-intake/)")
                .contains("[shipping](/systems/shipping/)")
                .contains("publishes")
                // The system defines this message, so the label is a link to its page in this very tree.
                .contains("[`OrdersPaymentAcceptedEvent`](/systems/orders/system-architecture/"
                          + "building-block-view/events/orders-payment-accepted-event/)");
    }

    /**
     * The label of a message another system defines stays plain code: this change adds no link of a kind the
     * pages did not have.
     */
    @Test
    void aRelationCarryingAMessageThisSystemDoesNotDefine_isNotLinked() throws IOException {
        generate();

        assertThat(read("system-architecture/5-building-block-view/whitebox-view.md"))
                .contains("| `ShippingArrangedEvent` |");
    }

    /**
     * Two diagrams of the same system: the decomposition on its own, which is what a reader of a large system
     * can take in, and the same components with the systems around them.
     */
    @Test
    void theWhiteboxViewDrawsTheSystemOnItsOwnAndWithItsNeighbours() throws IOException {
        generate();

        String page = read("system-architecture/5-building-block-view/whitebox-view.md");
        assertThat(page).contains("## Inside the system").contains("## With the neighbouring systems");
        assertThat(page.split("```plantuml", -1).length - 1).isEqualTo(2);
        // The first diagram has no box for a neighbour; the second has one.
        String inside = page.substring(page.indexOf("## Inside the system"),
                page.indexOf("## With the neighbouring systems"));
        assertThat(inside).contains("component \"orders-risk\"").doesNotContain("component \"shipping\"");
    }

    /**
     * A system whose components exchange nothing would get two diagrams of the same boxes, and the second of
     * them would say nothing the table does not.
     */
    @Test
    void whenNothingFlowsInsideTheSystem_thenOnlyTheDiagramWithTheNeighboursIsDrawn() throws IOException {
        DocumentedSystem lonely = new DocumentedSystem("lonely", "lonely", null, List.of(), null,
                List.of(new DocumentedComponent("lonely-service", "lonely-service", null,
                        ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null)),
                List.of(new SystemRelation(RelationKind.EVENT, "shipping", "shipping-gateway", "lonely",
                        "lonely-service", "LonelyEvent", null, null, null)),
                List.of());
        GenerationContext landscape = new GenerationContext(
                ArchitectureModel.of(List.of(lonely, shipping())), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4,
                "/docs/dev/");

        template.writeSystem(lonely, landscape, content.resolve("systems").resolve("lonely"));

        String page = Files.readString(content.resolve(
                "systems/lonely/system-architecture/5-building-block-view/whitebox-view.md"));
        assertThat(page).doesNotContain("## Inside the system").contains("## With the neighbouring systems");
        assertThat(page.split("```plantuml", -1).length - 1).isEqualTo(1);
    }

    /**
     * An arrow that carries more names than the diagram can label reads as a count of what travels along it,
     * so that the diagram renders at all. Nothing is lost by it: the table of relations below names every one
     * of them, which is what criterion S-050 asks for.
     */
    @Test
    void aDiagramShowsACountWhereAnArrowCarriesMoreNamesThanItCanLabel() throws IOException {
        GenerationContext capped = new GenerationContext(context.model(), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 0,
                "/docs/dev/");

        template.writeSystem(orders, capped, systemDirectory);

        assertThat(read("system-architecture/5-building-block-view/whitebox-view.md"))
                .contains(" : 1 Event");
    }

    /** The picture is cut, the facts are not: the page says how many neighbours it left out. */
    @Test
    void theWhiteboxViewSaysWhenItLeavesANeighbourOut() throws IOException {
        GenerationContext narrow = new GenerationContext(context.model(), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 1, 4, "/docs/dev/");
        DocumentedSystem crowded = new DocumentedSystem("orders", "orders", null, List.of(), null,
                orders.components(),
                List.of(new SystemRelation(RelationKind.EVENT, "shipping", "shipping-gateway", "orders",
                                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null),
                        new SystemRelation(RelationKind.EVENT, "zulu", "zulu-service", "orders",
                                "orders-intake", "OrdersOtherEvent", null, null, null)),
                List.of());
        GenerationContext landscape = new GenerationContext(
                ArchitectureModel.of(List.of(crowded, shipping(), new DocumentedSystem("zulu", "zulu", null,
                        List.of(), null, List.of(), List.of(), List.of()))),
                "dev", narrow.archRepoUrl(), MODEL_IMPORTED_AT, GENERATED_AT, 1, 4, "/docs/dev/");

        template.writeSystem(crowded, landscape, systemDirectory);

        String page = read("system-architecture/5-building-block-view/whitebox-view.md");
        assertThat(page).contains(":::note[Not every neighbour is drawn]");
        assertThat(page).describedAs("the relation of the neighbour left out is still in the table")
                .contains("`OrdersOtherEvent`");
    }

    @Test
    void aMessagePageCarriesItsMetadataItsVersionsAndItsContracts() throws IOException {
        generate();

        String page = read("system-architecture/5-building-block-view/commands/"
                           + "orders-check-erp-availability-v2-command.md");
        assertThat(page)
                .contains("# OrdersCheckErpAvailabilityV2Command")
                .contains("| Scope | internal |")
                .contains("| Topic | `orders-erp-command` |")
                .contains("## Versions")
                // A table now, not a bullet list: the version, the schema names where they are replicated,
                // and what the version is compatible with.
                .contains("| Version | Key schema | Value schema | Compatibility |")
                .contains("| `1.0.0` |")
                .contains("## Sender Contracts")
                .contains("## Receiver Contracts")
                .contains("## Reactions");
    }

    /**
     * What the schemas look like on a page once a run has joined them in: the table names them and links them
     * into the registry, and a section per version carries the rendering.
     * <p>
     * Fenced as {@code java} on purpose - the rendering is not valid Avro IDL, and there is no language for
     * what it is. It reads well enough highlighted as Java and wrongly enough that nobody takes it for the
     * file, which the link beside it points at.
     */
    @Test
    void aMessagePageCarriesTheSchemasOfEachVersionWhereTheyWereReplicated() throws IOException {
        DocumentedSystem withSchemas = orders.withMessages(orders.messages().stream()
                .map(message -> message.name().equals("OrdersCheckErpAvailabilityV2Command")
                        ? message.withVersions(List.of(replicated("1.0.0"), replicated("2.0.0")))
                        : message)
                .toList());

        template.writeSystem(withSchemas, context, systemDirectory);

        String page = read("system-architecture/5-building-block-view/commands/"
                           + "orders-check-erp-availability-v2-command.md");
        assertThat(page)
                .contains("| `1.0.0` | [Key1.0.0.avdl](https://registry/Key1.0.0.avdl) "
                          + "| [Value1.0.0.avdl](https://registry/Value1.0.0.avdl) | BACKWARD with 0.9.0 |")
                .contains("| `2.0.0` | [Key2.0.0.avdl](https://registry/Key2.0.0.avdl) "
                          + "| [Value2.0.0.avdl](https://registry/Value2.0.0.avdl) | BACKWARD with 0.9.0 |")
                .contains("### OrdersCheckErpAvailabilityV2Command 1.0.0")
                .contains("### OrdersCheckErpAvailabilityV2Command 2.0.0")
                .contains("**Key schema**: [Key1.0.0.avdl](https://registry/Key1.0.0.avdl)")
                .contains("**Value schema**: [Value1.0.0.avdl](https://registry/Value1.0.0.avdl)")
                .contains("// key of 2.0.0")
                .contains("// value of 2.0.0");
        assertThat(page.split("```java", -1).length - 1)
                .describedAs("one fence per schema of each of the two versions").isEqualTo(4);
    }

    /**
     * <b>A schema URL is whatever the architecture repository stores.</b> One that cannot be a link - a space
     * in it, a scheme nobody follows - is shown rather than thrown on: {@code Md.link} refuses such a target by
     * throwing, and a throw here ends the generation of every system of the environment, not just this page.
     */
    @Test
    void aMessagePage_whenASchemaUrlCannotBeALink_thenItIsShownAsCodeRatherThanFailingTheRun() throws IOException {
        DocumentedMessageVersion malformed = new DocumentedMessageVersion("1.0.0", null, null,
                new MessageSchema("Key.avdl", "https://registry/Key of the order.avdl", "string orderId;"),
                new MessageSchema("Value.avdl", "javascript:alert(1)", "string orderId;"));
        DocumentedSystem withABadUrl = orders.withMessages(orders.messages().stream()
                .map(message -> message.name().equals("OrdersCheckErpAvailabilityV2Command")
                        ? message.withVersions(List.of(malformed))
                        : message)
                .toList());

        template.writeSystem(withABadUrl, context, systemDirectory);

        String page = read("system-architecture/5-building-block-view/commands/"
                           + "orders-check-erp-availability-v2-command.md");
        assertThat(page)
                .describedAs("the file name is what the reader wants from the cell, so it survives the bad URL")
                .contains("Key.avdl `https://registry/Key of the order.avdl`")
                .contains("Value.avdl `javascript:alert(1)`")
                .doesNotContain("](javascript:");
    }

    /** A version nothing was replicated for keeps its row and simply has no section under it. */
    @Test
    void aMessagePageWithoutReplicatedSchemas_showsItsVersionsAndNoSchemaSection() throws IOException {
        generate();

        String page = read("system-architecture/5-building-block-view/commands/"
                           + "orders-check-erp-availability-v2-command.md");
        assertThat(page)
                .contains("| `1.0.0` |")
                .doesNotContain("```java")
                .doesNotContain("### OrdersCheckErpAvailabilityV2Command");
    }

    /**
     * Guessing which side a component is on would be a wrong answer that looks right, and dropping the
     * contract would hide that the component is involved at all.
     */
    @Test
    void aContractWhoseRoleIsNotKnown_isShownRatherThanDropped() throws IOException {
        generate();

        assertThat(read("system-architecture/5-building-block-view/commands/"
                        + "orders-check-erp-availability-v2-command.md"))
                .contains("## Contracts With An Unrecognised Role")
                .contains("orders-audit");
    }

    /**
     * Two systems may each have a component of the same name. A contract that names its system links into
     * that system, whichever comes first in the landscape; one that names no system is not guessed at.
     */
    @Test
    void aContractLinksTheComponentOfTheSystemItNames_andIsNotGuessedAtWithoutOne() throws IOException {
        DocumentedComponent alphaWorker = new DocumentedComponent("shared-worker", "shared-worker", null,
                ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null);
        DocumentedComponent betaWorker = new DocumentedComponent("shared-worker", "shared-worker", null,
                ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null);
        DocumentedSystem alpha = new DocumentedSystem("alpha", "alpha", null, List.of(), null,
                List.of(alphaWorker), List.of(),
                List.of(new DocumentedMessage("AlphaThingDoneEvent", "alpha-thing-done-event",
                        MessageKind.EVENT, "internal", "alpha-topic", null, null, null, List.of(DocumentedMessageVersion.of("1.0.0")),
                        List.of(new MessageContract(ContractRole.PRODUCES, "shared-worker", "alpha",
                                        "alpha-topic", List.of("1.0.0")),
                                new MessageContract(ContractRole.CONSUMES, "shared-worker", "Beta-Alias",
                                        "alpha-topic", List.of("1.0.0")),
                                new MessageContract(ContractRole.CONSUMES, "shared-worker", null,
                                        "alpha-topic", List.of("1.0.0")),
                                new MessageContract(ContractRole.CONSUMES, "lonely-worker", null,
                                        "alpha-topic", List.of("1.0.0"))))));
        DocumentedSystem beta = new DocumentedSystem("beta", "beta", null, List.of("Beta-Alias"), null,
                List.of(betaWorker, new DocumentedComponent("lonely-worker", "lonely-worker", null,
                        ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null)),
                List.of(), List.of());
        GenerationContext twoSystems = new GenerationContext(ArchitectureModel.of(List.of(alpha, beta)),
                "dev", "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4,
                "/docs/dev/");

        template.writeSystem(alpha, twoSystems, content.resolve("systems").resolve("alpha"));

        String page = Files.readString(content.resolve(
                "systems/alpha/system-architecture/5-building-block-view/events/alpha-thing-done-event.md"));
        assertThat(page)
                .contains("[shared-worker](/systems/alpha/system-architecture/building-block-view/components/shared-worker/)")
                .contains("[shared-worker](/systems/beta/system-architecture/building-block-view/components/shared-worker/)")
                .contains("| `shared-worker` |")
                .contains("[lonely-worker](/systems/beta/system-architecture/building-block-view/components/lonely-worker/)");
    }

    /** The versions of a message are listed; the schemas behind them will come later. */
    @Test
    void aMessagePageListsItsVersions() throws IOException {
        generate();

        assertThat(read("system-architecture/5-building-block-view/events/orders-payment-accepted-event.md"))
                .contains("## Versions")
                .contains("`1.0.0`");
    }

    @Test
    void theRuntimeViewSaysWhatItIsWaitingFor() throws IOException {
        generate();

        assertThat(read("system-architecture/6-runtime-view/system-reactions.md"))
                .contains("# System Reactions")
                .contains("reaction observer")
                .contains("not published yet");
    }

    /**
     * A run over an unchanged model produces an unchanged tree, or every build is a diff of nothing.
     */
    @Test
    void twoRunsOverOneModelProduceTheSameBytes() throws IOException {
        generate();
        String first = read("system-architecture/5-building-block-view/whitebox-view.md");
        generate();

        assertThat(read("system-architecture/5-building-block-view/whitebox-view.md")).isEqualTo(first);
    }

    /**
     * A system with no components, no messages and no neighbours still gets a tree that says so, rather than
     * pages with holes in them.
     */
    @Test
    void anEmptySystem_getsPagesThatSayThereIsNothing() throws IOException {
        DocumentedSystem empty = new DocumentedSystem("lonely", "lonely", null, List.of(), null,
                List.of(), List.of(), List.of());
        GenerationContext emptyContext = new GenerationContext(ArchitectureModel.of(List.of(empty)), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4,
                "/docs/dev/");

        template.writeSystem(empty, emptyContext, content.resolve("systems").resolve("lonely"));

        Path structure = content.resolve("systems/lonely/system-architecture");
        assertThat(Files.readString(structure.resolve("3-context-and-scope/system-context-view.md")))
                .contains("records no relation");
        assertThat(Files.readString(structure.resolve("5-building-block-view/whitebox-view.md")))
                .contains("knows no component");
        assertThat(structure.resolve("5-building-block-view/events")).doesNotExist();
        assertThat(structure.resolve("5-building-block-view/components")).doesNotExist();
    }

    /**
     * The link to a message group and the group itself are one decision, not two.
     * <p>
     * A system that defines no message of a kind gets no group for it, and no link to one: a link into a
     * directory nothing wrote fails the build of every site of the environment, not just this page. Which
     * messages get a page is not decided here - every message the model holds does, because the importer has
     * already refused every name that could not be one.
     */
    @Test
    void aSystemWithoutEvents_getsNeitherTheGroupNorALinkToIt() throws IOException {
        DocumentedSystem quiet = new DocumentedSystem("quiet", "quiet", null, List.of(), null,
                List.of(), List.of(),
                List.of(new DocumentedMessage("QuietPingCommand", "quiet-ping-command", MessageKind.COMMAND,
                        "internal", "quiet-topic", "A command, and no event.", null, null, List.of(DocumentedMessageVersion.of("1.0.0")),
                        List.of())));
        GenerationContext quietContext = new GenerationContext(ArchitectureModel.of(List.of(quiet)),
                "dev", "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4,
                "/docs/dev/");

        template.writeSystem(quiet, quietContext, content.resolve("systems").resolve("quiet"));

        Path view = content.resolve("systems/quiet/system-architecture/5-building-block-view");
        assertThat(view.resolve("events")).doesNotExist();
        assertThat(view.resolve("commands/quiet-ping-command.md")).exists();
        assertThat(Files.readString(view.resolve("index.md"))).doesNotContain("Events").contains("Commands");
    }

    /** The page is written under the slug the importer handed out, and nothing here derives one. */
    @Test
    void aMessagePageIsWrittenUnderTheSlugTheImporterHandedOut() throws IOException {
        DocumentedSystem named = new DocumentedSystem("named", "named", null, List.of(), null,
                List.of(), List.of(),
                List.of(new DocumentedMessage("NamedThingHappenedEvent", "named-thing-happened", MessageKind.EVENT,
                        "internal", "named-topic", null, null, null, List.of(), List.of())));
        GenerationContext namedContext = new GenerationContext(ArchitectureModel.of(List.of(named)),
                "dev", "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4,
                "/docs/dev/");

        template.writeSystem(named, namedContext, content.resolve("systems").resolve("named"));

        Path events = content.resolve("systems/named/system-architecture/5-building-block-view/events");
        assertThat(events.resolve("named-thing-happened.md")).exists();
        assertThat(Files.readString(events.resolve("index.md")))
                .contains("/systems/named/system-architecture/building-block-view/events/named-thing-happened/");
    }

    /**
     * A component nothing has seen for a fortnight may describe something that no longer exists, and a reader
     * has to be told.
     */
    @Test
    void aStaleComponentIsMarkedAsStale() throws IOException {
        generate();

        assertThat(read("system-architecture/5-building-block-view/components/orders-intake/index.md"))
                .contains(":::warning[Not seen recently]");
    }

    /**
     * One version as a run joins it in, with both schemas and a compatibility statement.
     * <p>
     * Everything about it carries the version, so two of them are told apart on the page: a test that asserted
     * one version's row and section would otherwise pass on a page that wrote one section, or the same one
     * twice.
     */
    /**
     * <b>What jeap-doc-markdown exists for, asserted on a written page.</b>
     * <p>
     * Every description on these pages is free text somebody typed into the architecture repository, and every
     * one of them reaches the page through {@code Md}. The unit tests of that module pin the escaping; nothing
     * pinned that the escaping is actually on the path from the model to a file - a template writing a
     * description into a {@code MarkdownWriter} as a raw string would pass every one of them.
     * <p>
     * The value here opens a Docusaurus admonition, raw HTML, emphasis, a link and a character reference, and
     * it carries a line break: {@code :::} at the start of a line would swallow the rest of the page into an
     * admonition, and a blank line inside a paragraph would start a new block in the middle of one.
     */
    @Test
    void everyDescriptionFromTheModel_reachesThePageEscaped() throws IOException {
        String hostile = ":::danger\nA <script>alert(1)</script> and *stars* & [brackets]";
        String escaped = "\\:::danger A \\<script\\>alert(1)\\</script\\> and \\*stars\\* &amp; \\[brackets\\]";
        orders = withDescriptions(orders, hostile);
        context = new GenerationContext(ArchitectureModel.of(List.of(orders, shipping())), "dev",
                "https://archrepo.example.com/archrepo", MODEL_IMPORTED_AT, GENERATED_AT, 100, 4, "/docs/dev/");

        generate();

        assertThat(read("system-architecture/1-intro/index.md"))
                .describedAs("the system's description")
                .contains(escaped)
                .doesNotContain(hostile);
        assertThat(read("system-architecture/5-building-block-view/components/orders-intake/index.md"))
                .describedAs("a component's description")
                .contains(escaped)
                .doesNotContain(hostile);
        assertThat(read("system-architecture/5-building-block-view/events/orders-payment-accepted-event.md"))
                .describedAs("a message's description")
                .contains(escaped)
                .doesNotContain(hostile);
        // The front matter carries the value as it came, and that is right: it is YAML rather than Markdown,
        // and the writer quotes it there. What may not happen is a line of the body starting with the
        // directive, which is what would swallow the rest of the page into an admonition.
        assertThat(everyPageBut())
                .describedAs("and no page of the tree has a line of its body opening an admonition of its own")
                .allSatisfy(page -> assertThat(bodyOf(page)).doesNotContainPattern("(?m)^:::danger"));
    }

    /** The page without its front matter, which is YAML: it quotes what it carries rather than escaping it. */
    private static String bodyOf(String page) {
        return page.replaceFirst("(?s)^---\n.*?\n---\n", "");
    }

    /** The same system, with the given text as the description of it, of every component and of every message. */
    private static DocumentedSystem withDescriptions(DocumentedSystem system, String description) {
        return new DocumentedSystem(system.name(), system.slug(), description, system.aliases(), system.team(),
                system.components().stream()
                        .map(component -> new DocumentedComponent(component.name(), component.slug(),
                                description, component.type(), component.team(), component.importer(),
                                component.lastSeen(), component.restApis(), component.openApi(),
                                component.databaseSchema()))
                        .toList(),
                system.relations(),
                system.messages().stream()
                        .map(message -> new DocumentedMessage(message.name(), message.slug(), message.kind(),
                                message.scope(), message.topic(), description, message.descriptorUrl(),
                                message.documentationUrl(), message.versions(), message.contracts()))
                        .toList());
    }

    private static DocumentedMessageVersion replicated(String version) {
        return new DocumentedMessageVersion(version, "BACKWARD", "0.9.0",
                new MessageSchema("Key" + version + ".avdl", "https://registry/Key" + version + ".avdl",
                        "string orderId; // key of " + version),
                new MessageSchema("Value" + version + ".avdl", "https://registry/Value" + version + ".avdl",
                        "string orderId;\nint total; // value of " + version));
    }

    private String read(String relative) throws IOException {
        return Files.readString(systemDirectory.resolve(relative), StandardCharsets.UTF_8);
    }

    private List<String> everyPageBut(String... excluded) throws IOException {
        List<String> excludedPaths = List.of(excluded);
        try (Stream<Path> files = Files.walk(systemDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".md"))
                    .filter(file -> !excludedPaths.contains(
                            systemDirectory.relativize(file).toString().replace('\\', '/')))
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

    private static DocumentedSystem orders() {
        DocumentedComponent intake = new DocumentedComponent("orders-intake", "orders-intake",
                "Takes payments in", ComponentType.BACKEND_SERVICE, new Team("Team Blue", "blue@example.com", null, null),
                "DEPLOYMENT_LOG", ZonedDateTime.parse("2026-01-01T00:00:00Z"), List.of(), null, null);
        // A second component, and a relation between the two: the whitebox page draws the decomposition on
        // its own as well as with the systems around it, and one component cannot show that.
        DocumentedComponent risk = new DocumentedComponent("orders-risk", "orders-risk", "Scores an order",
                ComponentType.BACKEND_SERVICE, new Team("Team Blue", "blue@example.com", null, null),
                "DEPLOYMENT_LOG", ZonedDateTime.parse("2026-08-27T04:00:00Z"), List.of(), null, null);
        SystemRelation event = new SystemRelation(RelationKind.EVENT, "shipping", "shipping-gateway", "orders",
                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null);
        SystemRelation internal = new SystemRelation(RelationKind.EVENT, "orders", "orders-risk", "orders",
                "orders-intake", "OrdersPaymentAcceptedEvent", null, null, null);
        return new DocumentedSystem("orders", "orders", "Takes orders and follows them through", List.of("ORDERS"),
                new Team("Team Blue", "blue@example.com", "https://jira/orders", "https://confluence/orders"),
                List.of(intake, risk), List.of(event, internal),
                List.of(
                        new DocumentedMessage("OrdersPaymentAcceptedEvent", "orders-payment-accepted-event",
                                MessageKind.EVENT, "internal",
                                "orders-payment", "The payment was accepted.", "https://descriptor/1",
                                null, List.of(DocumentedMessageVersion.of("1.0.0")),
                                List.of(new MessageContract(ContractRole.PRODUCES, "orders-intake", "orders",
                                        "orders-payment", List.of("1.0.0")))),
                        new DocumentedMessage("OrdersCheckErpAvailabilityV2Command",
                                "orders-check-erp-availability-v2-command", MessageKind.COMMAND,
                                "internal", "orders-erp-command", "Checks whether an order can be filled.",
                                "https://descriptor/2", null, List.of(DocumentedMessageVersion.of("1.0.0"), DocumentedMessageVersion.of("2.0.0")),
                                List.of(new MessageContract(ContractRole.PRODUCES, "orders-intake", "orders",
                                                "orders-erp-command", List.of("2.0.0")),
                                        new MessageContract(ContractRole.CONSUMES, "shipping-gateway", "shipping",
                                                "orders-erp-command", List.of("1.0.0")),
                                        new MessageContract(ContractRole.UNKNOWN, "orders-audit", "orders",
                                                "orders-erp-command", List.of("1.0.0"))))));
    }

    /**
     * The neighbour, which defines a message of its own that a component of {@code orders} consumes. A
     * relation belongs to the system that defines it, so an inbound edge of {@code orders} is declared here.
     */
    private static DocumentedSystem shipping() {
        return new DocumentedSystem("shipping", "shipping", "Sends the goods out", List.of(), null,
                List.of(new DocumentedComponent("shipping-gateway", "shipping-gateway", null,
                        ComponentType.BACKEND_SERVICE, null, null, null, List.of(), null, null)),
                List.of(new SystemRelation(RelationKind.EVENT, "orders", "orders-intake", "shipping",
                        "shipping-gateway", "ShippingArrangedEvent", null, null, null)),
                List.of());
    }
}
