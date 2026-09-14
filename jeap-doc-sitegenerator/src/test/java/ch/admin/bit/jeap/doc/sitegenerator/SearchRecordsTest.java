package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What ends up in the search index, page by page.
 * <p>
 * The cases that matter are the ones a search result would get wrong without saying so: a url that does not
 * lead anywhere, a diagram indexed as if it were prose, and a page uploaded rather than generated being read
 * differently from one that was.
 */
class SearchRecordsTest {

    private static final SiteEnvironment DEV = new SiteEnvironment("dev", "DEV", "Development", 1, false, true);
    private static final SiteEnvironment PROD = new SiteEnvironment("prod", "PROD", "Production", 2, true, false);

    @TempDir
    Path content;

    @Test
    void of_thenEveryPageOfEveryEnvironmentTreeIsARecord() throws IOException {
        page("prod/systems/orders/index.md", "Orders", "The order system.");
        page("prod/systems/billing/index.md", "Billing", "The billing system.");
        page("dev/systems/orders/index.md", "Orders", "The order system.");

        assertThat(records()).extracting(SearchRecord::url)
                .containsExactlyInAnyOrder("/systems/billing/", "/systems/orders/", "/dev/systems/orders/");
    }

    /**
     * The main environment is served at the site root and every other below its own prefix, so a record's url
     * is what tells a hit in one tree from the same page in another. It is also the only thing a result can
     * offer a reader.
     * <p>
     * <b>And the chapter numbers are not in it.</b> The folders are numbered on disk and Docusaurus serves
     * them without the number, so a url taken from the file path names a page that does not exist - which is
     * how every generated chapter of a deployed site came to be a dead search result.
     */
    @Test
    void of_thenTheUrlIsWhereTheSiteServesThePage() throws IOException {
        page("prod/systems/orders/system-architecture/1-intro/index.md", "Introduction", "Why.");
        page("dev/systems/orders/system-architecture/1-intro/context.md", "Context", "What.");

        assertThat(urls()).containsExactly(
                "/dev/systems/orders/system-architecture/intro/context/",
                "/systems/orders/system-architecture/intro/");
    }

    /**
     * Every segment of the path, not only the last: a component's page sits below two numbered chapters, and
     * one number left in the middle of a url is as dead as one at its end.
     */
    @Test
    void of_whenSeveralSegmentsAreNumbered_thenNoneOfTheNumbersIsInTheUrl() throws IOException {
        page("prod/systems/orders/system-architecture/5-building-block-view/components/orders-intake/"
             + "component-architecture/3-context-and-scope/context-view.md", "Component Context View", "Talks.");
        page("prod/systems/orders/system-architecture/9-architecture-decision-records/01-use-kafka.md",
                "Use Kafka", "Because.");

        assertThat(urls()).containsExactly(
                "/systems/orders/system-architecture/architecture-decision-records/use-kafka/",
                "/systems/orders/system-architecture/building-block-view/components/orders-intake/"
                + "component-architecture/context-and-scope/context-view/");
    }

    /**
     * What only looks like a number prefix stays. A folder called {@code 2024-decisions} is served under that
     * whole name, because the generator reads a second number after the separator as a date or a version - so
     * a rule that stripped it here would invent a url again, the other way round.
     */
    @Test
    void of_whenASegmentLooksLikeADate_thenItIsNotStripped() throws IOException {
        page("prod/systems/orders/system-architecture/12-glossary/2024-11-terms.md", "Terms", "Words.");

        assertThat(urls()).containsExactly("/systems/orders/system-architecture/glossary/2024-11-terms/");
    }

    /** The root page of a tree carries `slug: /`, and Docusaurus honours it, so this has to as well. */
    @Test
    void of_whenTheFrontMatterCarriesASlug_thenItWins() throws IOException {
        write("prod/index.md", "---\ntitle: Documentation\nslug: /\n---\n\n# Documentation\n\nWelcome.\n");
        write("dev/index.md", "---\ntitle: Documentation\nslug: /\n---\n\n# Documentation\n\nWelcome.\n");

        assertThat(urls()).containsExactly("/", "/dev/");
    }

    @Test
    void of_whenThePageIsAnIndex_thenTheUrlIsItsDirectory() throws IOException {
        page("prod/systems/index.md", "Systems", "All of them.");

        assertThat(urls()).containsExactly("/systems/");
    }

    /**
     * The whole page is indexed, not its title and headings - a reader who remembers a word from a table cell
     * has to find the page it is on.
     */
    @Test
    void of_thenTheBodyIsIndexed() throws IOException {
        write("prod/systems/orders/index.md", """
                ---
                title: Orders
                ---

                # Orders

                | Column | Type |
                |--------|------|
                | tenant_reference | varchar(64) |
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.body()).contains("tenant_reference").contains("varchar(64)");
        assertThat(record.body()).doesNotContain("|").doesNotContain("---");
        assertThat(record.content()).contains("tenant_reference");
    }

    /**
     * <b>The underscore survives.</b> It is an emphasis marker in Markdown and it is also what every table and
     * column name on a database schema page is made of, and a reader searching for the identifier in front of
     * them finds nothing if it has been split in two.
     */
    @Test
    void of_thenASnakeCaseIdentifierStaysOneWord() throws IOException {
        page("prod/systems/orders/index.md", "Orders", "The table jme_aws_config_service holds it.");

        assertThat(records().getFirst().content()).contains("jme_aws_config_service");
    }

    /**
     * Nobody searches for {@code skinparam}. The plugin this replaces indexed every PlantUML, GraphViz,
     * Mermaid and Avro block on the site because it worked on rendered HTML and had to be told about them as
     * CSS selectors; reading Markdown makes a fence a block to skip.
     */
    @Test
    void of_thenAFencedBlockIsNotIndexed() throws IOException {
        write("prod/systems/orders/context.md", """
                ---
                title: Context View
                ---

                # Context View

                The orders system talks to billing.

                ```plantuml
                @startuml
                skinparam componentStyle rectangle
                component "orders" as ordersComponent
                @enduml
                ```
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.content()).contains("talks to billing");
        assertThat(record.content()).doesNotContain("skinparam").doesNotContain("startuml");
    }

    /**
     * <b>A fence closes on its own marker.</b> A block whose body holds a backtick run is opened with four or
     * more of them - {@code MarkdownWriter} writes it that way, and so does documentation showing a Markdown
     * example - and a rule that only ever closed on three ran past the end of the block: it swallowed the
     * prose after it and indexed the block it was meant to skip, all the way to the next three-backtick line
     * on the page.
     */
    @Test
    void of_whenAFenceIsLongerThanThreeBackticks_thenTheProseAfterItIsStillIndexed() throws IOException {
        write("prod/systems/orders/uploading.md", """
                ---
                title: Uploading
                ---

                # Uploading

                ````markdown
                ```plantuml
                skinparam componentStyle rectangle
                ```
                ````

                The upload is a ZIP file.

                ```bash
                echo hello
                ```

                And it is sent with PUT.
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.content()).contains("The upload is a ZIP file").contains("it is sent with PUT");
        assertThat(record.content()).doesNotContain("skinparam").doesNotContain("echo hello");
    }

    /** A fence indented inside a list item is a fence too, and its body is no more indexable for it. */
    @Test
    void of_whenAFenceIsIndentedInAListItem_thenItIsNotIndexed() throws IOException {
        write("prod/systems/orders/steps.md", """
                ---
                title: Steps
                ---

                1. Configure the workflow:

                   ```yaml
                   uses: jeap-doc-upload
                   ```

                2. Push the branch.
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.content()).contains("Configure the workflow").contains("Push the branch");
        assertThat(record.content()).doesNotContain("jeap-doc-upload");
    }

    /** A `#` inside a fence is a comment in some language, and never a heading of the page. */
    @Test
    void of_whenAFenceHoldsAHash_thenItIsNotAHeading() throws IOException {
        write("prod/systems/orders/index.md", """
                ---
                title: Orders
                ---

                ## Deployment

                ```bash
                # not a heading
                echo hello
                ```
                """);

        assertThat(records().getFirst().headings()).containsExactly("Deployment");
    }

    @Test
    void of_thenTheHeadingsAreCarriedApartFromTheBody() throws IOException {
        write("prod/systems/orders/index.md", """
                ---
                title: Orders
                ---

                # Orders

                ## Building Block View

                The parts of it.
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.headings()).containsExactly("Orders", "Building Block View");
        assertThat(record.body()).isEqualTo("The parts of it.");
        assertThat(record.content()).startsWith("Orders. Orders. Building Block View. The parts of it.");
    }

    /**
     * An uploaded page is written by whoever wrote the documentation, not by the generator: its front matter
     * is `title` and `description` and nothing else, and it carries none of the `doc_` provenance keys. It has
     * to be read exactly like a generated one - which is the whole reason this reads Markdown rather than the
     * generated HTML.
     */
    @Test
    void of_whenThePageWasUploadedRatherThanGenerated_thenItIsReadTheSameWay() throws IOException {
        write("prod/systems/orders/system-architecture/2-constraints/team-rules.md", """
                ---
                title: Team Rules
                description: How this team works
                ---

                # Team Rules

                We deploy on Fridays.
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.url()).isEqualTo("/systems/orders/system-architecture/constraints/team-rules/");
        assertThat(record.title()).isEqualTo("Team Rules");
        assertThat(record.content()).contains("We deploy on Fridays");
        assertThat(record.system()).isEqualTo("orders");
    }

    /** A page with no front matter at all is still a page, and still has to be findable. */
    @Test
    void of_whenThereIsNoFrontMatter_thenTheFileNameIsTheTitle() throws IOException {
        write("prod/systems/orders/notes.md", "# Notes\n\nSomething.\n");

        SearchRecord record = records().getFirst();
        assertThat(record.title()).isEqualTo("notes");
        assertThat(record.content()).contains("Something");
    }

    @Test
    void of_thenAPageOfTheSiteItselfBelongsToNoSystem() throws IOException {
        page("prod/about-this-documentation.md", "About This Documentation", "What this is.");

        assertThat(records().getFirst().system()).isNull();
        assertThat(records().getFirst().name()).isNull();
    }

    /**
     * <b>And a page inside a component's tree says which component.</b> A generated page's title is its
     * chapter - "Component Architecture" on every one of a system's fifty components - so the component is
     * what makes a result identifiable at all.
     */
    @Test
    void of_whenThePageIsInsideAComponentsTree_thenItNamesTheComponent() throws IOException {
        page("prod/systems/orders/system-architecture/5-building-block-view/components/orders-intake/"
             + "component-architecture/index.md", "Component Architecture", "How it is built.");

        SearchRecord record = records().getFirst();
        assertThat(record.system()).isEqualTo("orders");
        assertThat(record.name()).isEqualTo("orders-intake");
    }

    /** A page that merely mentions the components is not in one of them - the whitebox view is the system's. */
    @Test
    void of_whenThePageOnlyListsTheComponents_thenItIsNotInOne() throws IOException {
        page("prod/systems/orders/system-architecture/5-building-block-view/whitebox-view.md",
                "Level 1: Whitebox View", "The parts.");

        SearchRecord record = records().getFirst();
        assertThat(record.system()).isEqualTo("orders");
        assertThat(record.name()).isNull();
    }

    /** The generator escapes what would otherwise stop being text; a reader should see it as it was written. */
    @Test
    void of_whenTheTitleWasEscapedForMdx_thenTheTextIsReadable() throws IOException {
        write("prod/systems/orders/index.md", """
                ---
                title: "List&lt;Order&gt;"
                ---

                # List&lt;Order&gt;

                A generic type.
                """);

        SearchRecord record = records().getFirst();
        assertThat(record.title()).isEqualTo("List&lt;Order&gt;");
        assertThat(record.headings()).containsExactly("List<Order>");
    }

    /**
     * An environment that reads no architecture model has no tree at all, and a content directory holds the
     * template's two JSON files and the site's branding beside the trees. None of that is a page.
     */
    @Test
    void of_whenAnEnvironmentHasNoTree_thenItContributesNothingAndNothingFails() throws IOException {
        page("prod/index.md", "Documentation", "Welcome.");
        write("site.json", "{}");
        write("environments.json", "{}");
        write("static/branding/logo.svg", "<svg/>");

        assertThat(urls()).containsExactly("/");
    }

    /**
     * <b>What produced a page is what the page says about itself.</b> Every page the service writes carries
     * {@code doc_status}, which is also what the provenance block under it is built from - so the badge on a
     * search result and the line on the page can never say different things.
     */
    @Test
    void of_thenEveryRecordSaysWhatProducedIt() throws IOException {
        write("prod/systems/orders/index.md", frontMatter("Orders", "doc_status: generated"));
        write("prod/systems/orders/5-building-block-view/written.md",
                frontMatter("Written By The Team", "doc_status: custom", "doc_source: upload"));
        write("prod/systems/orders/5-building-block-view/reference-microsite.md",
                frontMatter("Configuration Reference", "doc_status: custom", "doc_source: upload",
                        "doc_microsite_url: /microsites/orders/arc42/5-building-block-view/reference/"));

        assertThat(records()).extracting(SearchRecord::title, SearchRecord::source)
                .containsExactlyInAnyOrder(
                        tuple("Orders", SearchRecord.GENERATED),
                        tuple("Written By The Team", SearchRecord.MARKDOWN),
                        tuple("Configuration Reference", SearchRecord.HTML));
    }

    /** And the page that frames a microsite carries where that microsite is served, for the records inside it. */
    @Test
    void of_whenThePageFramesAMicrosite_thenItSaysWhereThatMicrositeIs() throws IOException {
        write("prod/systems/orders/5-building-block-view/reference-microsite.md",
                frontMatter("Configuration Reference", "doc_status: custom",
                        "doc_microsite_url: /microsites/orders/arc42/5-building-block-view/reference/"));

        assertThat(records().getFirst().micrositeUrl())
                .isEqualTo("/microsites/orders/arc42/5-building-block-view/reference/");
        assertThat(records().getFirst().microsite())
                .describedAs("a page of the site, not a page inside a microsite").isNull();
    }

    /**
     * <b>A library is a subject of its own.</b> It publishes no artifact and is deployed nowhere, so no
     * architecture model holds one and every chapter of it was written by hand - and a reader narrowing a
     * search to components should not be shown one.
     */
    @Test
    void of_thenEveryRecordSaysWhatItDocuments() throws IOException {
        page("prod/index.md", "Documentation", "The site itself.");
        page("prod/systems/orders/index.md", "Orders", "The system.");
        page("prod/systems/orders/system-architecture/5-building-block-view/components/orders-intake/"
             + "index.md", "Orders Intake", "The component.");
        page("prod/systems/orders/system-architecture/5-building-block-view/libraries/orders-client/"
             + "index.md", "Orders Client", "The library.");

        assertThat(records()).extracting(SearchRecord::title, SearchRecord::subject, SearchRecord::name)
                .containsExactlyInAnyOrder(
                        tuple("Documentation", null, null),
                        tuple("Orders", SearchRecord.SYSTEM, null),
                        tuple("Orders Intake", SearchRecord.COMPONENT, "orders-intake"),
                        tuple("Orders Client", SearchRecord.LIBRARY, "orders-client"));
    }

    /**
     * <b>A slug is relative unless it starts with a slash</b>, which is what Docusaurus does with it. The
     * page that frames a microsite carries {@code microsites/<topic>} inside its chapter, and reading that as
     * a route from the root of the environment pointed every search hit inside a microsite at a page that
     * does not exist.
     */
    @Test
    void of_whenTheSlugIsRelative_thenItIsResolvedInsideTheFolderThePageIsIn() throws IOException {
        write("prod/systems/orders/system-architecture/2-constraints/reference-microsite.md",
                frontMatter("Configuration Reference", "slug: microsites/reference"));

        assertThat(records().getFirst().url())
                .isEqualTo("/systems/orders/system-architecture/constraints/microsites/reference/");
    }

    /** And one that starts with a slash is the route from the root of the environment, as it always was. */
    @Test
    void of_whenTheSlugIsAbsolute_thenItIsTheRouteItself() throws IOException {
        write("prod/systems/orders/system-architecture/2-constraints/elsewhere.md",
                frontMatter("Somewhere Else", "slug: /somewhere-else"));

        assertThat(records().getFirst().url()).isEqualTo("/somewhere-else/");
    }

    private static String frontMatter(String title, String... keys) {
        return "---\ntitle: %s\n%s\n---\n\n# %s\n\nWhat it says.\n"
                .formatted(title, String.join("\n", keys), title);
    }

    private List<SearchRecord> records() {
        return SearchRecords.of(content, site());
    }

    private List<String> urls() {
        return records().stream().map(SearchRecord::url).sorted().toList();
    }

    private static Site site() {
        return new Site("default", "Documentation", null, null, null, null, List.of(DEV, PROD), true, false);
    }

    private void page(String path, String title, String body) throws IOException {
        write(path, "---\ntitle: %s\n---\n\n# %s\n\n%s\n".formatted(title, title, body));
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
