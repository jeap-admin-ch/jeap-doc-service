package ch.admin.bit.jeap.doc.sitegenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTextTest {

    @Test
    void escaped_thenMarkupCharactersBecomeText() {
        assertThat(MarkdownText.escaped("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    /**
     * A {@code .md} page is MDX, so braces are an expression rather than punctuation - and an unbalanced one is
     * a build that fails rather than a page that looks odd.
     */
    @Test
    void escaped_thenBracesBecomeText() {
        assertThat(MarkdownText.escaped("Documentation {of} everything"))
                .isEqualTo("Documentation &#123;of&#125; everything");
    }

    /**
     * The ampersand is escaped first, or every reference written by this method would be escaped a second time
     * and the reader would see the reference instead of the character.
     */
    @Test
    void escaped_thenTheReferencesItWritesAreNotEscapedAgain() {
        assertThat(MarkdownText.escaped("Fish & Chips <b>")).isEqualTo("Fish &amp; Chips &lt;b&gt;");
        assertThat(MarkdownText.escaped("&lt;")).isEqualTo("&amp;lt;");
    }

    @Test
    void escaped_thenOrdinaryTextAndMarkdownEmphasisAreLeftAlone() {
        assertThat(MarkdownText.escaped("jEAP: Documentation - *the* platform"))
                .isEqualTo("jEAP: Documentation - *the* platform");
    }

    /**
     * A site configures no tagline far more often than it configures one, and the template has a line for it
     * either way.
     */
    @Test
    void escaped_whenThereIsNoValue_thenEmpty() {
        assertThat(MarkdownText.escaped(null)).isEmpty();
    }
}
