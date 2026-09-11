package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a build asks about the documentation of one system. */
class CustomDocumentationTest {

    private static final CustomSubject SYSTEM =
            new CustomSubject("default", SubjectKind.SYSTEM, "orders", null);
    private static final CustomSubject COMPONENT =
            new CustomSubject("default", SubjectKind.COMPONENT, "orders", "orders-intake");
    private static final CustomSubject LIBRARY =
            new CustomSubject("default", SubjectKind.LIBRARY, "orders", "orders-client");

    private static CustomSet setOf(CustomSubject subject, String template, CustomPage... pages) {
        return setOf(subject, template, SourceFormat.MARKDOWN, pages);
    }

    private static CustomSet setOf(CustomSubject subject, String template, SourceFormat format,
                                   CustomPage... pages) {
        CustomSetKey key = new CustomSetKey(subject.site(), subject.kind(), subject.system(), subject.name(),
                format, template,
                format == SourceFormat.HTML ? "6-runtime-view" : null,
                format == SourceFormat.HTML ? "reference" : null);
        return new CustomSet(1L, key, 7L, "current/whatever", "abc", 10,
                new CustomProvenance("orders-docs", "main", "cafe", Instant.EPOCH, null, Instant.EPOCH),
                List.of(pages));
    }

    private static CustomPage page(String chapter, String fileName, String title, int position) {
        return new CustomPage(chapter, fileName, title, position, false);
    }

    @Test
    void nothing_answersEveryQuestionEmpty() {
        CustomDocumentation nothing = CustomDocumentation.nothing();

        assertThat(nothing.isEmpty()).isTrue();
        assertThat(nothing.setOf(SYSTEM)).isEmpty();
        assertThat(nothing.chapterFoldersOf(SYSTEM)).isEmpty();
        assertThat(nothing.documentedComponents()).isEmpty();
        assertThat(nothing.libraries()).isEmpty();
        assertThat(nothing.documentsTheSystem()).isFalse();
    }

    @Test
    void chapterFolders_areTheOnesWithPages_sortedAndWithoutDuplicates() {
        CustomDocumentation docs = new CustomDocumentation(List.of(setOf(SYSTEM, "arc42",
                page("2-constraints", "a.md", "A", 1),
                page("12-glossary", "z.md", "Z", 1),
                page("2-constraints", "b.md", "B", 2))));

        assertThat(docs.chapterFoldersOf(SYSTEM)).containsExactly("12-glossary", "2-constraints");
    }

    @Test
    void anAsset_doesNotMakeAChapterCarryPages() {
        CustomDocumentation docs = new CustomDocumentation(List.of(setOf(SYSTEM, "arc42",
                new CustomPage("2-constraints", "diagram.png", null, 0, true))));

        assertThat(docs.chapterFoldersOf(SYSTEM))
                .describedAs("an image with no page beside it publishes nothing")
                .isEmpty();
    }

    /**
     * <b>What a build asks first.</b> A subject carries as many sets as it has methodologies and formats, and
     * every question below is about the pages of one tree - so the sets of one tree are picked out before any
     * of them is asked. Asked over all of them, the set of a subject was whichever one came back first.
     */
    @Test
    void publishedBy_thenOnlyTheMarkdownSetsOfThatTemplateAreLeft() {
        CustomSet arc42 = setOf(COMPONENT, "arc42", page("1-intro", "why.md", "Why", 1));
        CustomSet microsite = setOf(COMPONENT, "arc42", SourceFormat.HTML,
                new CustomPage("6-runtime-view", "index.html", null, 0, true));
        CustomSet another = setOf(COMPONENT, "something-else", page("1-intro", "why.md", "Why", 1));
        CustomDocumentation docs = new CustomDocumentation(List.of(microsite, another, arc42));

        assertThat(docs.publishedBy("arc42").sets())
                .describedAs("the microsite carries the same template and is not a set of pages")
                .containsExactly(arc42);
        assertThat(docs.publishedBy("something-else").sets()).containsExactly(another);
        assertThat(docs.publishedBy("no-such-template").sets()).isEmpty();
    }

    /**
     * The one that was broken: a component with an HTML microsite beside its Markdown, which is the shape of
     * the workspace's own example repository. Narrowed first, the set of that subject is its Markdown - not
     * whichever of the two the database handed back.
     */
    @Test
    void setOf_whenTheSubjectAlsoHasAMicrosite_thenItIsTheMarkdownSet() {
        CustomSet microsite = setOf(COMPONENT, "arc42", SourceFormat.HTML,
                new CustomPage("6-runtime-view", "index.html", null, 0, true));
        CustomSet markdown = setOf(COMPONENT, "arc42", page("1-intro", "why.md", "Why", 1));
        CustomDocumentation docs = new CustomDocumentation(List.of(microsite, markdown));

        assertThat(docs.publishedBy("arc42").setOf(COMPONENT)).contains(markdown);
        assertThat(docs.publishedBy("arc42").chapterFoldersOf(COMPONENT))
                .describedAs("and the chapters are the ones its pages are in")
                .containsExactly("1-intro");
    }

    /**
     * A microsite carries no pages, so a subject that has one and nothing else has no chapter to create -
     * a chapter folder with a generated landing page and nothing under it.
     */
    @Test
    void publishedBy_whenTheSubjectOnlyHasAMicrosite_thenItHasNoChapters() {
        CustomDocumentation docs = new CustomDocumentation(List.of(setOf(COMPONENT, "arc42", SourceFormat.HTML,
                new CustomPage("6-runtime-view", "index.html", null, 0, true))));

        assertThat(docs.publishedBy("arc42").chapterFoldersOf(COMPONENT)).isEmpty();
        assertThat(docs.publishedBy("arc42").documentedComponents()).isEmpty();
    }

    @Test
    void aSubjectWithTwoTemplates_isOneComponent() {
        CustomDocumentation docs = new CustomDocumentation(List.of(
                setOf(COMPONENT, "arc42", page("1-intro", "why.md", "Why", 1)),
                setOf(COMPONENT, "something-else", page("1-intro", "why.md", "Why", 1))));

        assertThat(docs.documentedComponents()).containsExactly(COMPONENT);
    }

    @Test
    void componentsAndLibraries_areToldApart() {
        CustomDocumentation docs = new CustomDocumentation(List.of(
                setOf(SYSTEM, "arc42", page("1-intro", "why.md", "Why", 1)),
                setOf(COMPONENT, "arc42", page("1-intro", "why.md", "Why", 1)),
                setOf(LIBRARY, "arc42", page("1-intro", "why.md", "Why", 1))));

        assertThat(docs.documentedComponents()).containsExactly(COMPONENT);
        assertThat(docs.libraries()).containsExactly(LIBRARY);
        assertThat(docs.documentsTheSystem()).isTrue();
    }

    @Test
    void pagesOfAChapter_areInTheirAssignedOrder() {
        CustomSet set = setOf(SYSTEM, "arc42",
                page("2-constraints", "z.md", "Alpha", 1),
                page("2-constraints", "a.md", "Zulu", 2),
                new CustomPage("2-constraints", "picture.png", null, 0, true));

        assertThat(set.pagesOf("2-constraints"))
                .describedAs("the position decides, not the file name, and an asset is not a page")
                .extracting(CustomPage::fileName).containsExactly("z.md", "a.md");
    }

    @Test
    void aPagePath_isItsChapterAndItsName() {
        assertThat(page("9-architecture-decision-records", "adr-1.md", "ADR 1", 1).path())
                .isEqualTo("9-architecture-decision-records/adr-1.md");
    }
}
