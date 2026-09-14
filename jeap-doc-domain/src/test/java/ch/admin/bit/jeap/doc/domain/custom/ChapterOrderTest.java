package ch.admin.bit.jeap.doc.domain.custom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One chapter can hold uploaded pages and microsites, and a reader sees one list. */
class ChapterOrderTest {

    private static CustomPage page(String fileName, String title, int position) {
        return new CustomPage("6-runtime-view", fileName, title, position, false);
    }

    private static Microsite microsite(String label, String topic) {
        return new Microsite(label, "6-runtime-view", topic, "/microsites/orders/arc42/6-runtime-view/"
                                                             + topic + "/", null);
    }

    /**
     * <b>The two are sorted as one list.</b> Ordered separately, every microsite would come after every page
     * whatever it is called - so a reader looking for <i>Configuration</i> would find the pages under C and
     * the microsites somewhere else entirely.
     */
    @Test
    void aPageAndAMicrosite_areOrderedByWhatEachOfThemShows() {
        ChapterOrder order = ChapterOrder.of(
                List.of(page("alpha.md", "Alpha", 1), page("zulu.md", "Zulu", 2)),
                List.of(microsite("Mike", "mike")));

        assertThat(order.positionOf(page("alpha.md", "Alpha", 1))).isEqualTo(1);
        assertThat(order.positionOf(microsite("Mike", "mike")))
                .describedAs("between them, by its label").isEqualTo(2);
        assertThat(order.positionOf(page("zulu.md", "Zulu", 2))).isEqualTo(3);
    }

    /** A chapter with no microsite comes out exactly as the upload numbered it: nothing about markdown moves. */
    @Test
    void aChapterWithoutAMicrosite_keepsTheOrderTheUploadAssigned() {
        List<CustomPage> pages = List.of(page("aaa.md", "Zulu", 2), page("zzz.md", "Alpha", 1));

        ChapterOrder order = ChapterOrder.of(pages, List.of());

        assertThat(order.positionOf(pages.get(1))).describedAs("titled Alpha").isEqualTo(1);
        assertThat(order.positionOf(pages.get(0))).describedAs("titled Zulu").isEqualTo(2);
    }

    @Test
    void theCaseOfALabel_doesNotDecideTheOrder() {
        ChapterOrder order = ChapterOrder.of(List.of(page("b.md", "beta", 1)),
                List.of(microsite("Alpha", "alpha")));

        assertThat(order.positionOf(microsite("Alpha", "alpha"))).isEqualTo(1);
        assertThat(order.positionOf(page("b.md", "beta", 1))).isEqualTo(2);
    }

    /** Two things a team called the same are ordered by their file names, so the order never wobbles. */
    @Test
    void twoWithOneName_areOrderedByTheirFileNames() {
        ChapterOrder order = ChapterOrder.of(List.of(page("zzz.md", "Reference", 1)),
                List.of(microsite("Reference", "aaa")));

        assertThat(order.positionOf(microsite("Reference", "aaa")))
                .describedAs("aaa-microsite.md before zzz.md").isEqualTo(1);
        assertThat(order.positionOf(page("zzz.md", "Reference", 1))).isEqualTo(2);
    }

    @Test
    void aMicrositeAlone_isTheWholeChapter() {
        ChapterOrder order = ChapterOrder.of(List.of(), List.of(microsite("Only one", "only")));

        assertThat(order.positionOf(microsite("Only one", "only"))).isEqualTo(1);
    }
}
