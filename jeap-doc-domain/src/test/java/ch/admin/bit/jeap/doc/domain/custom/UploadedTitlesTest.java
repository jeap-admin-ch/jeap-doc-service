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
}
