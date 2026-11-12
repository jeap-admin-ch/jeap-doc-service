package ch.admin.bit.jeap.doc.domain.custom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One chapter can hold pages this template generates, pages and microsites a team uploaded, and a reader sees
 * one list.
 */
class ChapterOrderTest {

    private static CustomPage page(String fileName, String title, int position) {
        return new CustomPage("6-runtime-view", fileName, title, position, false);
    }

    private static CustomPage page(String fileName, String title, OrderHint hint) {
        return new CustomPage("6-runtime-view", fileName, title, 0, false, hint);
    }

    private static Microsite microsite(String label, String topic) {
        return new Microsite(label, "6-runtime-view", topic, "/microsites/orders/arc42/6-runtime-view/"
                                                             + topic + "/", null);
    }

    private static Microsite microsite(String label, String topic, OrderHint hint) {
        return new Microsite(label, "6-runtime-view", topic, "/microsites/orders/arc42/6-runtime-view/"
                                                             + topic + "/", null, hint);
    }

    private static OrderHint before(String reference) {
        return new OrderHint(null, OrderHint.Direction.BEFORE, reference);
    }

    private static OrderHint after(String reference) {
        return new OrderHint(null, OrderHint.Direction.AFTER, reference);
    }

    private static OrderHint at(double position) {
        return new OrderHint(position, null, null);
    }

    private static ChapterOrder.PresentSibling sibling(String name, int position) {
        return new ChapterOrder.PresentSibling(name, position);
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

    /** A declared position beats a title, and an item with none comes last whatever it is called. */
    @Test
    void freeItems_areOrderedByPositionThenTitle() {
        CustomPage second = page("second.md", "Zebra", at(2));
        CustomPage first = page("first.md", "Yak", at(1));
        CustomPage byTitle = page("third.md", "Alpha", OrderHint.NONE);

        ChapterOrder order = ChapterOrder.of(List.of(second, first, byTitle), List.of());

        assertThat(order.positionOf(first)).describedAs("position 1 first, despite its title").isEqualTo(1);
        assertThat(order.positionOf(second)).describedAs("position 2 next").isEqualTo(2);
        assertThat(order.positionOf(byTitle)).describedAs("no position: last, whatever its title").isEqualTo(3);
    }

    @Test
    void anItem_canPlaceItselfBeforeAGeneratedSibling() {
        CustomPage intro = page("intro.md", "Intro", before("system-context-view"));

        ChapterOrder order = ChapterOrder.of(List.of(sibling("system-context-view", 1_000_000)),
                List.of(intro), List.of());

        assertThat(order.positionOf(intro)).isLessThan(order.positionOf("system-context-view"));
    }

    @Test
    void anItem_canPlaceItselfAfterAGeneratedSibling() {
        CustomPage notes = page("notes.md", "Notes", after("system-context-view"));

        ChapterOrder order = ChapterOrder.of(List.of(sibling("system-context-view", 1_000_000)),
                List.of(notes), List.of());

        assertThat(order.positionOf(notes)).isGreaterThan(1_000_000);
    }

    @Test
    void anItem_canPlaceItselfBetweenTwoGeneratedSiblings() {
        CustomPage between = page("between.md", "Between", after("system-context-view"));

        ChapterOrder order = ChapterOrder.of(
                List.of(sibling("system-context-view", 1_000_000), sibling("whitebox-view", 2_000_000)),
                List.of(between), List.of());

        assertThat(order.positionOf(between)).isGreaterThan(1_000_000).isLessThan(2_000_000);
    }

    @Test
    void aMicrosite_canPlaceItselfRelativeToAGeneratedSibling() {
        Microsite referenceApp = microsite("Reference App", "reference", before("system-context-view"));

        ChapterOrder order = ChapterOrder.of(List.of(sibling("system-context-view", 1_000_000)),
                List.of(), List.of(referenceApp));

        assertThat(order.positionOf(referenceApp)).isLessThan(1_000_000);
    }

    /**
     * The exact number has to come from the caller: a second formula computing it here would be the one that
     * drifts from what the template actually wrote on the generated page.
     */
    @Test
    void aGeneratedSibling_keepsThePositionItWasGiven() {
        ChapterOrder order = ChapterOrder.of(List.of(sibling("system-context-view", 1_000_000)),
                List.of(), List.of());

        assertThat(order.positionOf("system-context-view")).isEqualTo(1_000_000);
    }

    /** A chain of references nests correctly, because expanding a group expands its own groups too. */
    @Test
    void aChainOfReferences_isKeptInOrder() {
        CustomPage third = page("third.md", "Third", after("second"));
        CustomPage second = page("second.md", "Second", after("first"));
        CustomPage first = page("first.md", "First", OrderHint.NONE);

        ChapterOrder order = ChapterOrder.of(List.of(third, second, first), List.of());

        assertThat(order.positionOf(first)).isLessThan(order.positionOf(second));
        assertThat(order.positionOf(second)).isLessThan(order.positionOf(third));
    }

    /** Only possible for a set stored before the validation that refuses it existed. */
    @Test
    void aMissingSibling_putsTheItemLastWithItsChain() {
        CustomPage trunk = page("trunk.md", "Trunk", before("ghost"));
        CustomPage leaf = page("leaf.md", "Leaf", after("trunk"));
        CustomPage normal = page("normal.md", "Normal", OrderHint.NONE);

        ChapterOrder order = ChapterOrder.of(List.of(trunk, leaf, normal), List.of());

        assertThat(order.positionOf(normal)).isLessThan(order.positionOf(trunk));
        assertThat(order.positionOf(trunk)).isLessThan(order.positionOf(leaf));
    }

    /** Only possible for a set stored before the validation that refuses it existed. */
    @Test
    void aStoredCycle_goesLast() {
        CustomPage a = page("a.md", "A", after("b"));
        CustomPage b = page("b.md", "B", after("a"));
        CustomPage normal = page("normal.md", "Normal", OrderHint.NONE);

        ChapterOrder order = ChapterOrder.of(List.of(a, b, normal), List.of());

        assertThat(order.positionOf(normal)).isLessThan(order.positionOf(a));
        assertThat(order.positionOf(normal)).isLessThan(order.positionOf(b));
    }

    @Test
    void aSelfReference_isACycleOfOne() {
        CustomPage selfReferencing = page("alone.md", "Alone", after("alone"));
        CustomPage normal = page("normal.md", "Normal", OrderHint.NONE);

        ChapterOrder order = ChapterOrder.of(List.of(selfReferencing, normal), List.of());

        assertThat(order.positionOf(normal)).isLessThan(order.positionOf(selfReferencing));
    }
}
