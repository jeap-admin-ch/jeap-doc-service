package ch.admin.bit.jeap.doc.domain.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureChapterTest {

    /**
     * The number orders, the slug addresses, the label reads - and the double-digit chapters are where a naive
     * prefix strip goes wrong.
     */
    @ParameterizedTest
    @CsvSource({
            "1,1-intro,Introduction and Goals,intro,1. Introduction and Goals",
            "5,5-building-block-view,Building Block View,building-block-view,5. Building Block View",
            "9,9-architecture-decision-records,Architecture Decisions,architecture-decision-records,9. Architecture Decisions",
            "12,12-glossary,Glossary,glossary,12. Glossary"})
    void aNumberedChapterAddressesWithoutItsNumberAndReadsWithIt(int number, String folder, String title,
                                                                 String segment, String label) {
        StructureChapter chapter = StructureChapter.numbered(number, folder, title);

        assertThat(chapter.isNumbered()).isTrue();
        assertThat(chapter.urlSegment()).isEqualTo(segment);
        assertThat(chapter.label()).isEqualTo(label);
    }

    /**
     * A methodology that does not number its chapters has nothing to put in the folder or in front of the
     * label: the folder is the URL segment, and the title is what the navigation shows.
     */
    @ParameterizedTest
    @CsvSource({
            "decisions,Decisions",
            "quality-goals,Quality Goals",
            "glossary,Glossary"})
    void anUnnumberedChapterIsItsFolderAndItsTitle(String folder, String title) {
        StructureChapter chapter = StructureChapter.unnumbered(folder, title);

        assertThat(chapter.isNumbered()).isFalse();
        assertThat(chapter.urlSegment()).isEqualTo(folder);
        assertThat(chapter.label()).isEqualTo(title);
    }

    @Test
    void aFolderThatDoesNotCarryItsNumber_isRefused() {
        assertThatThrownBy(() -> StructureChapter.numbered(5, "building-block-view", "Building Block View"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5-");
        assertThatThrownBy(() -> StructureChapter.numbered(5, "6-building-block-view", "Building Block View"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A folder that is nothing but its number names no chapter. */
    @Test
    void aFolderThatIsOnlyItsNumber_isRefused() {
        assertThatThrownBy(() -> StructureChapter.numbered(5, "5-", "Building Block View"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNumberBelowOne_isRefused() {
        assertThatThrownBy(() -> StructureChapter.numbered(0, "0-intro", "Introduction"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numbered from 1");
    }

    /**
     * The one thing an unnumbered chapter may not do. Docusaurus strips a leading number from a folder by
     * itself - it is what makes the numbered chapters work - so such a folder would be served where nothing
     * links to it, and no build would fail over it.
     */
    @Test
    void anUnnumberedFolderThatBeginsWithADigit_isRefused() {
        assertThatThrownBy(() -> StructureChapter.unnumbered("2024-decisions", "Decisions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisions");
    }

    @Test
    void aFolderThatIsNoSlug_isRefused() {
        assertThatThrownBy(() -> StructureChapter.unnumbered("Building Block View", "Building Block View"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slug");
    }

    @Test
    void aChapterWithoutATitle_isRefused() {
        assertThatThrownBy(() -> StructureChapter.unnumbered("decisions", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    /**
     * How a template's chapters are sorted, whichever kind they are: the numbers where there are numbers, the
     * alphabet where there are none - and the alphabet ignoring case, so that a title nobody capitalised does
     * not end up last.
     */
    @Test
    void order_thenNumbersWhereThereAreNumbersAndTheAlphabetWhereThereAreNone() {
        assertThat(sorted(
                StructureChapter.numbered(12, "12-glossary", "Glossary"),
                StructureChapter.numbered(2, "2-constraints", "Architecture Constraints"),
                StructureChapter.numbered(9, "9-adr", "Architecture Decisions")))
                .extracting(StructureChapter::number).containsExactly(2, 9, 12);

        assertThat(sorted(
                StructureChapter.unnumbered("quality-goals", "Quality Goals"),
                StructureChapter.unnumbered("decisions", "decisions"),
                StructureChapter.unnumbered("glossary", "Glossary")))
                .extracting(StructureChapter::folder)
                .containsExactly("decisions", "glossary", "quality-goals");
    }

    private static java.util.List<StructureChapter> sorted(StructureChapter... chapters) {
        return java.util.Arrays.stream(chapters).sorted(StructureChapter.ORDER).toList();
    }
}
