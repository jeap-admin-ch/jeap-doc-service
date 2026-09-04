package ch.admin.bit.jeap.doc.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The escaping table. It is the one test in this module that stops a bad page reaching every template at once:
 * everything a generated page says about a system it did not author goes through here first.
 */
class MdTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
            'plain text'                  | 'plain text'
            'a < b'                       | 'a \\< b'
            'a & b'                       | 'a &amp; b'
            'a > b'                       | 'a \\> b'
            '# not a heading'             | '\\# not a heading'
            '*not emphasis*'              | '\\*not emphasis\\*'
            '_not emphasis_'              | '\\_not emphasis\\_'
            '[not a link](x)'             | '\\[not a link\\](x)'
            'back\\slash'                 | 'back\\\\slash'
            'a `code` span'               | 'a \\`code\\` span'
            '~~not struck through~~'      | '\\~\\~not struck through\\~\\~'
            """)
    void text_escapesWhatWouldChangeTheStructureOfTheLine(String value, String expected) {
        assertThat(Md.text(value).value()).isEqualTo(expected);
    }

    /**
     * A pipe is deliberately left alone here: it means something inside a table row and nowhere else, and
     * {@link MarkdownWriter} escapes it when a fragment becomes a cell. Escaping it twice would put a backslash
     * on the page.
     */
    @Test
    void text_leavesThePipeToTheTable() {
        assertThat(Md.text("a | b").value()).isEqualTo("a | b");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void text_whenThereIsNothingToSay_thenEmpty(String value) {
        assertThat(Md.text(value)).isEqualTo(Markdown.EMPTY);
    }

    /**
     * A line break becomes one space, however many there were. Kept, a blank line in an imported description
     * would open a list, a table or an admonition inside a paragraph the writer meant as one block.
     */
    @Test
    void text_turnsLineBreaksIntoOneSpace() {
        assertThat(Md.text("first\r\nsecond").value()).isEqualTo("first second");
        assertThat(Md.text("first\n\n:::danger[boom]\n:::").value())
                .isEqualTo("first \\:::danger\\[boom\\] \\:::");
        assertThat(Md.text("first\n\n- an item").value()).isEqualTo("first - an item");
    }

    /**
     * {@code :::} at the start of a block opens a container directive, which this module writes itself and
     * does not accept from outside: a description of {@code :::danger} would otherwise swallow the rest of
     * the page into an admonition nobody closed.
     */
    @Test
    void text_whenItOpensAContainerDirective_thenTheMarkerIsEscaped() {
        assertThat(Md.text(":::danger").value()).isEqualTo("\\:::danger");
        assertThat(Md.text(":::").value()).isEqualTo("\\:::");
        assertThat(Md.text("a : b").value()).describedAs("a single colon is ordinary text").isEqualTo("a : b");
        assertThat(Md.text("12:30:00").value()).describedAs("so is a time").isEqualTo("12:30:00");
    }

    @Test
    void textOr_whenThereIsNothing_thenTheFallback() {
        assertThat(Md.textOr(null, "unknown").value()).isEqualTo("unknown");
        assertThat(Md.textOr("Team Blue", "unknown").value()).isEqualTo("Team Blue");
    }

    @Test
    void code_fencesRatherThanEscapes() {
        assertThat(Md.code("orders-foo-bar-service").value()).isEqualTo("`orders-foo-bar-service`");
        assertThat(Md.code("a_b_c").value()).isEqualTo("`a_b_c`");
    }

    @Test
    void code_whenTheValueHasBackticks_thenTheFenceIsLongerThanTheLongestRun() {
        assertThat(Md.code("a ` b").value()).isEqualTo("``a ` b``");
        assertThat(Md.code("a ``` b").value()).isEqualTo("````a ``` b````");
    }

    @Test
    void code_whenTheValueStartsOrEndsWithABacktick_thenItIsPadded() {
        assertThat(Md.code("`x").value()).isEqualTo("`` `x ``");
    }

    @Test
    void link_escapesTheLabelAndLeavesTheTargetAlone() {
        assertThat(Md.link("/systems/orders/", "ORDERS <the system>").value())
                .isEqualTo("[ORDERS \\<the system\\>](/systems/orders/)");
    }

    @Test
    void link_whenThereIsNoLabel_thenTheTargetIsTheLabel() {
        assertThat(Md.link("/systems/orders/", (String) null).value())
                .isEqualTo("[/systems/orders/](/systems/orders/)");
    }

    /**
     * The target standing in for the label is the one place a value from outside would otherwise reach the page
     * unescaped. A {@code ]} in it ends the link text early and leaves the rest as literal characters.
     */
    @Test
    void link_whenTheTargetIsTheLabel_thenTheLabelIsEscapedLikeAnyOtherText() {
        assertThat(Md.link("/a_b]c", (String) null).value()).isEqualTo("[/a\\_b\\]c](/a_b]c)");
    }

    /**
     * An indented block is a code block, and a value that came from somewhere else carries no indentation worth
     * keeping.
     */
    /**
     * Four columns of indentation open a code block, and CommonMark counts <b>columns</b>: a tab advances to
     * the next tab stop, so one space and a tab is as deep as four spaces. Counting characters instead left
     * three of these four turning an imported description into a code block.
     */
    @ParameterizedTest
    @ValueSource(strings = {"    def f()", "\tdef f()", " \tdef f()", "  \tdef f()", "   \tdef f()"})
    void text_whenItIsIndented_thenItIsNotACodeBlock(String value) {
        assertThat(Md.text(value).value()).isEqualTo("def f()");
    }

    /** The trailing space of a separator fragment is the point of it, and stripping the front leaves it. */
    @Test
    void text_whenItEndsInASpace_thenTheSpaceStays() {
        assertThat(Md.text(": ").value()).isEqualTo(": ");
    }

    /** A leading space inside emphasis would make the emphasis a bullet - see {@code Md.italic}. */
    @Test
    void italic_whenTheValueBeginsWithASpace_thenItIsNotABulletList() {
        assertThat(Md.italic(" never").value()).isEqualTo("*never*");
    }

    @Test
    void link_whenThereIsNoTarget_thenJustTheLabel() {
        assertThat(Md.link(null, "ORDERS").value()).isEqualTo("ORDERS");
    }

    /**
     * A target that would end the link early is a defect in whoever built the URL, and it fails loudly rather
     * than producing a page with a broken link on it - which {@code onBrokenLinks: 'throw'} would then blame on
     * the page.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/a path/", "/a(b)", "/a<b>", "/a\nb", "/a\tb", "/a\u0000b"})
    void link_whenTheTargetWouldBreakOut_thenItIsRefused(String target) {
        assertThatThrownBy(() -> Md.link(target, "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link target");
    }

    /**
     * <b>Where a value from the architecture repository reaches a link target.</b> {@code link} throws on a
     * target it will not put on a page, and one bad value out of one registry would end the generation of every
     * system of the environment; this is the twin that shows the value instead.
     * <p>
     * A relative target is in the list for the same reason: it does not throw, but Docusaurus would resolve it
     * against the page it stands on and fail the build over the broken link - the same environment lost, one
     * step later. Only an absolute {@code http(s)} or {@code mailto} target becomes a link here, so
     * {@code //evil.example} - which starts with a slash but leaves the site - is shown, not linked.
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://registry/a b.avdl", "https://registry/a(1).avdl", "registry/a.avdl",
            "javascript:alert(1)", "https://registry/a\tb.avdl",
            "/a.avdl", "./a.avdl", "../a.avdl", "#a", "//evil.example/a.avdl"})
    void linkOrCode_whenTheTargetCannotBeALink_thenTheNameStaysAndTheTargetIsShownAsCode(String target) {
        assertThat(Md.linkOrCode(target, "Value.avdl").value())
                .isEqualTo("Value.avdl `" + target + "`");
    }

    /** With nothing to call it, the target is all there is to show. */
    @Test
    void linkOrCode_whenThereIsNoLabelEither_thenTheTargetAloneIsShownAsCode() {
        assertThat(Md.linkOrCode("javascript:alert(1)", null).value()).isEqualTo("`javascript:alert(1)`");
    }

    @Test
    void linkOrCode_whenTheTargetCanBeALink_thenItIsOne() {
        assertThat(Md.linkOrCode("https://registry/Value.avdl", "Value.avdl").value())
                .isEqualTo("[Value.avdl](https://registry/Value.avdl)");
    }

    @Test
    void linkOrCode_whenThereIsNoTarget_thenNothing() {
        assertThat(Md.linkOrCode(null, "Value.avdl")).isEqualTo(Markdown.EMPTY);
    }

    @Test
    void sentence_putsTheFragmentsWhereThePlaceholdersAre() {
        Markdown sentence = Md.sentence("See {} for the components of {}.",
                Md.link("/systems/orders/x/", "the building block view"), Md.code("orders"));
        assertThat(sentence.value())
                .isEqualTo("See [the building block view](/systems/orders/x/) for the components of `orders`.");
    }

    @Test
    void sentence_escapesEverythingAroundThePlaceholders() {
        assertThat(Md.sentence("a <b> {}", Md.text("c")).value()).isEqualTo("a \\<b\\> c");
    }

    @Test
    void sentence_whenThePlaceholdersAndArgumentsDisagree_thenItFails() {
        assertThatThrownBy(() -> Md.sentence("{} and {}", Md.text("one")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Md.sentence("{}", Md.text("one"), Md.text("two")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joinWith_skipsTheEmptyFragments() {
        assertThat(Md.joinWith(", ", List.of(Md.text("a"), Markdown.EMPTY, Md.text("b"))).value())
                .isEqualTo("a, b");
    }

    @Test
    void bold_andItalic_leaveNothingBehindWhenThereIsNothing() {
        assertThat(Md.bold((String) null)).isEqualTo(Markdown.EMPTY);
        assertThat(Md.italic("")).isEqualTo(Markdown.EMPTY);
    }
}
