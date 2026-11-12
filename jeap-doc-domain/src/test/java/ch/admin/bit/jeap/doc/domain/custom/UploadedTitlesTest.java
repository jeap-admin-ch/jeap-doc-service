package ch.admin.bit.jeap.doc.domain.custom;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** What a page says its title is, out of its front matter. */
class UploadedTitlesTest {

    private static String titleOf(String page) {
        return UploadedTitles.titleOf(page.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPlainTitle_isRead() {
        assertThat(titleOf("""
                ---
                title: Quality Requirements
                description: what we measure
                ---

                # Quality Requirements
                """)).isEqualTo("Quality Requirements");
    }

    @Test
    void aQuotedTitle_losesItsQuotes() {
        assertThat(titleOf("---\ntitle: \"Orders: the intake\"\n---\n")).isEqualTo("Orders: the intake");
        assertThat(titleOf("---\ntitle: 'A team''s view'\n---\n")).isEqualTo("A team's view");
    }

    @Test
    void aPageWithNoFrontMatter_hasNoTitle() {
        assertThat(titleOf("# Just a heading\n\nAnd some text.\n")).isNull();
    }

    @Test
    void aFrontMatterWithoutATitle_hasNoTitle() {
        assertThat(titleOf("---\ndescription: only this\n---\n\n# Heading\n")).isNull();
    }

    @Test
    void aTitleAfterTheBlock_isNotATitle() {
        assertThat(titleOf("""
                ---
                description: only this
                ---

                title: not front matter at all
                """)).isNull();
    }

    @Test
    void aBlockThatIsNotTerminatedWithinWhatWasRead_hasNoTitle() {
        assertThat(titleOf("---\ndescription: a block that goes on\n")).isNull();
    }

    @Test
    void anEmptyTitle_isNoTitle() {
        assertThat(titleOf("---\ntitle: \nname: x\n---\n")).isNull();
        assertThat(titleOf("---\ntitle: \"\"\n---\n")).isNull();
    }

    @Test
    void blankLinesAndAByteOrderMarkAboveTheBlock_areSkipped() {
        assertThat(titleOf("\n\n---\ntitle: Constraints\n---\n")).isEqualTo("Constraints");
        assertThat(titleOf("﻿---\ntitle: Constraints\n---\n")).isEqualTo("Constraints");
    }

    @Test
    void nothingAtAll_hasNoTitle() {
        assertThat(UploadedTitles.titleOf(new byte[0])).isNull();
        assertThat(UploadedTitles.titleOf(null)).isNull();
    }

    private static OrderHint hintOf(String page) {
        return UploadedTitles.hintOf(page.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPageWithNoPlacement_hasNoHint() {
        assertThat(hintOf("---\ntitle: Constraints\n---\n")).isEqualTo(OrderHint.NONE);
        assertThat(UploadedTitles.hintOf(new byte[0])).isEqualTo(OrderHint.NONE);
        assertThat(UploadedTitles.hintOf(null)).isEqualTo(OrderHint.NONE);
    }

    @Test
    void aPosition_isReadAsANumber() {
        assertThat(hintOf("---\nsidebar_position: 3\n---\n"))
                .isEqualTo(new OrderHint(3.0, null, null));
        assertThat(hintOf("---\nsidebar_position: 1.5\n---\n").position()).isEqualTo(1.5);
    }

    @Test
    void aPositionThatIsNotANumber_readsAsNone() {
        assertThat(hintOf("---\nsidebar_position: not-a-number\n---\n").position()).isNull();
    }

    @Test
    void anAfterReference_isReadWithItsDirection() {
        assertThat(hintOf("---\nsidebar_after: system-context-view\n---\n"))
                .isEqualTo(new OrderHint(null, OrderHint.Direction.AFTER, "system-context-view"));
    }

    @Test
    void aBeforeReference_isReadWithItsDirection() {
        assertThat(hintOf("---\nsidebar_before: system-context-view\n---\n"))
                .isEqualTo(new OrderHint(null, OrderHint.Direction.BEFORE, "system-context-view"));
    }

    @Test
    void aPositionAndAReference_areBothRead() {
        assertThat(hintOf("---\nsidebar_position: 2\nsidebar_after: intro\n---\n"))
                .isEqualTo(new OrderHint(2.0, OrderHint.Direction.AFTER, "intro"));
    }

    /** Both at once is refused by the validation; here it reads as neither, like a page that gave none. */
    @Test
    void bothAfterAndBefore_readAsNoReference() {
        OrderHint hint = hintOf("---\nsidebar_after: a\nsidebar_before: b\n---\n");

        assertThat(hint.direction()).isNull();
        assertThat(hint.reference()).isNull();
    }

    private static UploadedPlacement rawPlacementOf(String page) {
        return UploadedTitles.rawPlacementOf(page.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Unlike {@link #hintOf}, this does not read a value it cannot make sense of as none - which is exactly
     * what the validation needs to tell "banana" apart from a page that said nothing at all.
     */
    @Test
    void rawPlacementOf_withNoKeyAtAll_namesNothing() {
        UploadedPlacement raw = rawPlacementOf("---\ntitle: Constraints\n---\n");

        assertThat(raw.position()).isNull();
        assertThat(raw.positionIsInvalid()).isFalse();
        assertThat(raw.namesAReference()).isFalse();
    }

    @Test
    void rawPlacementOf_withAValidPosition_isNotInvalid() {
        assertThat(rawPlacementOf("---\nsidebar_position: 3\n---\n").positionIsInvalid()).isFalse();
    }

    @Test
    void rawPlacementOf_withAPositionThatIsNotANumber_isInvalid() {
        UploadedPlacement raw = rawPlacementOf("---\nsidebar_position: banana\n---\n");

        assertThat(raw.position()).isEqualTo("banana");
        assertThat(raw.positionIsInvalid()).isTrue();
    }

    @Test
    void rawPlacementOf_withBothDirections_namesBoth() {
        UploadedPlacement raw = rawPlacementOf("---\nsidebar_after: a\nsidebar_before: b\n---\n");

        assertThat(raw.namesBothDirections()).isTrue();
    }

    @Test
    void rawPlacementOf_withABlankReference_isInvalid() {
        assertThat(rawPlacementOf("---\nsidebar_after: \"\"\n---\n").afterIsInvalid()).isTrue();
    }

    @Test
    void rawPlacementOf_withAListAsAReference_isInvalid() {
        assertThat(rawPlacementOf("---\nsidebar_before:\n  - one\n  - two\n---\n").beforeIsInvalid()).isTrue();
    }

    @Test
    void rawPlacementOf_withAValidReference_isNotInvalid() {
        UploadedPlacement raw = rawPlacementOf("---\nsidebar_after: intro\n---\n");

        assertThat(raw.after()).isEqualTo("intro");
        assertThat(raw.afterIsInvalid()).isFalse();
        assertThat(raw.namesAReference()).isTrue();
    }
}
