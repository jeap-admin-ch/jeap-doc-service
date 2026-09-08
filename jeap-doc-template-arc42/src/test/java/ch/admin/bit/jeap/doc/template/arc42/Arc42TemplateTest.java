package ch.admin.bit.jeap.doc.template.arc42;

import ch.admin.bit.jeap.doc.domain.template.StructureChapter;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twelve chapters, their folder names and their order.
 * <p>
 * It reads as a restatement of the class it tests, and that is the point: these names are what an upload's
 * archive carries, what the structural validation will check and what the generator writes, so changing one is
 * a change to a contract with every repository that publishes documentation. This is where that change has to
 * be deliberate.
 */
class Arc42TemplateTest {

    private final Arc42Template template = new Arc42Template();

    @Test
    void theTwelveChaptersOfArc42_inOrder() {
        assertThat(template.chapters()).extracting(StructureChapter::number, StructureChapter::folder,
                        StructureChapter::urlSegment, StructureChapter::label)
                .containsExactly(
                        tuple(1, "1-intro", "intro", "1. Introduction and Goals"),
                        tuple(2, "2-constraints", "constraints", "2. Architecture Constraints"),
                        tuple(3, "3-context-and-scope", "context-and-scope", "3. Context and Scope"),
                        tuple(4, "4-solution-strategy", "solution-strategy", "4. Solution Strategy"),
                        tuple(5, "5-building-block-view", "building-block-view", "5. Building Block View"),
                        tuple(6, "6-runtime-view", "runtime-view", "6. Runtime View"),
                        tuple(7, "7-deployment-view", "deployment-view", "7. Deployment View"),
                        tuple(8, "8-crosscutting-concepts", "crosscutting-concepts",
                                "8. Cross-cutting Concepts"),
                        tuple(9, "9-architecture-decision-records", "architecture-decision-records",
                                "9. Architecture Decisions"),
                        tuple(10, "10-quality-requirements", "quality-requirements", "10. Quality Requirements"),
                        tuple(11, "11-risks", "risks", "11. Risks and Technical Debt"),
                        tuple(12, "12-glossary", "glossary", "12. Glossary"));
    }

    /**
     * A template is named for the thing it describes: the same structure reads as <i>System Architecture</i>
     * under a system and as <i>Component Architecture</i> under a component, because repeating the system
     * wording inside a component would read as if the component documented the system.
     */
    @Test
    void theTemplateIsNamedForWhatItDescribes() {
        assertThat(template.id()).isEqualTo("arc42");
        assertThat(template.systemPathSegment()).isEqualTo("system-architecture");
        assertThat(template.systemLabel()).isEqualTo("System Architecture");
        assertThat(template.componentPathSegment()).isEqualTo("component-architecture");
        assertThat(template.componentLabel()).isEqualTo("Component Architecture");
    }

    @Test
    void aFolderResolvesToItsChapter_andAnUnknownOneToNothing() {
        assertThat(template.chapterOfFolder("5-building-block-view"))
                .contains(Arc42Chapters.BUILDING_BLOCK_VIEW);
        assertThat(template.chapterOfFolder("9-adr")).describedAs("the old spelling").isEmpty();
        assertThat(template.chapterOfFolder("13-appendix")).isEmpty();
    }

    /**
     * Only the four chapters with something to generate are created; the other eight are absent, and the gap in
     * the numbering is what tells a reader that they have not been written.
     */
    @Test
    void onlyTheChaptersWithSomethingInThemAreGenerated() {
        assertThat(Arc42Template.GENERATED_CHAPTERS).containsExactly(
                Arc42Chapters.INTRODUCTION, Arc42Chapters.CONTEXT_AND_SCOPE,
                Arc42Chapters.BUILDING_BLOCK_VIEW, Arc42Chapters.RUNTIME_VIEW);
    }

    @Test
    void everyChapterSaysWhatItAnswers() {
        assertThat(template.chapters()).allSatisfy(chapter ->
                assertThat(Arc42Chapters.summaryOf(chapter)).isNotBlank());
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    @Test
    void theChaptersAreTwelveAndNumberedOneToTwelve() {
        assertThat(template.chapters()).hasSize(12);
        assertThat(template.chapters()).extracting(StructureChapter::number)
                .isEqualTo(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    }

    /**
     * The extensions an upload may carry. Markdown and the pictures that have no source - a page that cannot
     * show a screenshot is a page a team keeps in Confluence.
     */
    @Test
    void allowedFileExtensions_areMarkdownAndTheImageFormats() {
        assertThat(template.allowedFileExtensions())
                .containsExactlyInAnyOrder("md", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg");
    }

    /**
     * <b>{@code mdx} is not on the list and must not be.</b> MDX is a programming language, and documentation
     * the doc service did not write itself is not trusted with one - decision 10.
     */
    @Test
    void allowedFileExtensions_doNotIncludeMdx() {
        assertThat(template.allowedFileExtensions()).doesNotContain("mdx", "html", "json", "js");
    }

    /** What the template writes for a system, chapter by chapter - the names an upload may not reuse. */
    @Test
    void generatedNames_forASystem_areThePagesAndGroupsItWrites() {
        assertThat(template.generatedNames(Arc42Chapters.CONTEXT_AND_SCOPE, SubjectKind.SYSTEM))
                .containsExactly("system-context-view");
        assertThat(template.generatedNames(Arc42Chapters.BUILDING_BLOCK_VIEW, SubjectKind.SYSTEM))
                .describedAs("the whitebox view, and the three folders beside it - a group and a page of one "
                             + "name are one URL")
                .containsExactlyInAnyOrder("whitebox-view", "components", "events", "commands");
        assertThat(template.generatedNames(Arc42Chapters.RUNTIME_VIEW, SubjectKind.SYSTEM))
                .containsExactly("system-reactions");
    }

    @Test
    void generatedNames_forAComponent_areItsOwnPages() {
        assertThat(template.generatedNames(Arc42Chapters.CONTEXT_AND_SCOPE, SubjectKind.COMPONENT))
                .containsExactly("context-view");
        assertThat(template.generatedNames(Arc42Chapters.BUILDING_BLOCK_VIEW, SubjectKind.COMPONENT))
                .containsExactlyInAnyOrder("database-schema", "rest-api", "messages");
        assertThat(template.generatedNames(Arc42Chapters.RUNTIME_VIEW, SubjectKind.COMPONENT))
                .containsExactly("component-reactions");
    }

    /**
     * <b>Nothing is generated for a library.</b> The upload API accepts library documentation and no template
     * writes a page for one, so a library upload is bounded by what the domain reserves everywhere and
     * nothing else.
     */
    @Test
    void generatedNames_forALibrary_areNone() {
        for (StructureChapter chapter : Arc42Chapters.ALL) {
            assertThat(template.generatedNames(chapter, SubjectKind.LIBRARY))
                    .describedAs("nothing is generated for a library, in %s", chapter.folder())
                    .isEmpty();
        }
    }

    /** A chapter this template writes nothing into occupies no name in it. */
    @Test
    void generatedNames_forAChapterNothingIsWrittenInto_areNone() {
        assertThat(template.generatedNames(Arc42Chapters.GLOSSARY, SubjectKind.SYSTEM)).isEmpty();
        assertThat(template.generatedNames(Arc42Chapters.RISKS, SubjectKind.COMPONENT)).isEmpty();
        assertThat(template.generatedNames(null, SubjectKind.SYSTEM)).isEmpty();
        assertThat(template.generatedNames(Arc42Chapters.INTRODUCTION, null)).isEmpty();
    }
}
