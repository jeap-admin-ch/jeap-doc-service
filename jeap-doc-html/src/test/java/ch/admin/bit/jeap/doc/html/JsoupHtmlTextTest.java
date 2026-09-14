package ch.admin.bit.jeap.doc.html;

import ch.admin.bit.jeap.doc.domain.port.HtmlText;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is read out of a document, and what is not.
 * <p>
 * The documents here are the shapes a microsite really carries: generated documentation with a navigation on
 * every page, a page whose title is only a heading, one that was cut off at a byte bound, and one that is not
 * HTML at all. None of them may fail.
 */
class JsoupHtmlTextTest {

    private final HtmlText text = new JsoupHtmlText(new DocHtmlProperties());

    @Test
    void theTitle_comesFromTheDocument() {
        HtmlText.Extracted extracted = read("""
                <!doctype html><html><head><title>Class CustomSet</title></head>
                <body><h1>CustomSet</h1><p>One documentation set.</p></body></html>
                """);

        assertThat(extracted.title()).isEqualTo("Class CustomSet");
        assertThat(extracted.text()).isEqualTo("CustomSet One documentation set.");
    }

    @Test
    void aDocumentWithoutATitle_isCalledAfterItsFirstHeading() {
        assertThat(read("<html><body><h2>Second</h2><h1>The first heading</h1></body></html>").title())
                .isEqualTo("The first heading");
    }

    /** Neither a title nor a heading: the caller names it, because only it knows what the file is called. */
    @Test
    void aDocumentWithNeither_isCalledNothing() {
        assertThat(read("<html><body><p>Only prose.</p></body></html>").title()).isEmpty();
    }

    /**
     * <b>The navigation is not text.</b> Generated documentation repeats it on every page, so an excerpt
     * built from it would read the same for every class of a Javadoc.
     */
    @Test
    void theFurniture_isLeftOut() {
        HtmlText.Extracted extracted = read("""
                <html><head><title>Class</title><style>h1 { color: red }</style></head>
                <body>
                <nav><a href="a.html">Package</a><a href="b.html">Class</a></nav>
                <div class="topNav">Overview Package Class Use Tree</div>
                <p>What the class is for.</p>
                <script>var tracked = 1;</script>
                <footer>Copyright</footer>
                </body></html>
                """);

        assertThat(extracted.text()).isEqualTo("What the class is for.");
    }

    @Test
    void characterReferences_areTheCharactersTheyName() {
        assertThat(read("<html><body><p>A &lt;T&gt; and an &amp;</p></body></html>").text())
                .isEqualTo("A <T> and an &");
    }

    /**
     * <b>A document cut off at a byte bound is read as far as it got.</b> The extraction reads a bounded
     * number of bytes, so the last tag of what it reads is routinely half a tag - and the text before it is
     * exactly what should be indexed.
     */
    @Test
    void aDocumentCutOffMidTag_isReadAsFarAsItGot() {
        HtmlText.Extracted extracted = read("""
                <html><head><title>Long</title></head><body><p>The first paragraph.</p><div class="sec
                """);

        assertThat(extracted.title()).isEqualTo("Long");
        assertThat(extracted.text()).isEqualTo("The first paragraph.");
    }

    /**
     * Malformed markup is recovered from, the way a browser recovers from it: the unclosed paragraphs become
     * two, and the bold text joins the one it is inside without a space - which is what a reader sees.
     */
    @Test
    void malformedMarkup_isParsedRatherThanRefused() {
        assertThat(read("<html><body><p>One<p>Two<b>Three</body>").text()).isEqualTo("One TwoThree");
    }

    @Test
    void aFileThatIsNotHtml_isItsOwnText() {
        assertThat(read("nothing but a line of text").text()).isEqualTo("nothing but a line of text");
    }

    @Test
    void anEmptyFile_isNothing() {
        assertThat(text.of(new byte[0])).isEqualTo(HtmlText.Extracted.NOTHING);
        assertThat(text.of(null).isEmpty()).isTrue();
    }

    /** The declared encoding is what the bytes are read as, not the platform's. */
    @Test
    void theDocumentsOwnEncoding_isWhatItIsReadIn() {
        byte[] latin1 = ("<html><head><meta charset=\"ISO-8859-1\"><title>Grüezi</title></head>"
                         + "<body><p>Schön</p></body></html>").getBytes(StandardCharsets.ISO_8859_1);

        HtmlText.Extracted extracted = text.of(latin1);

        assertThat(extracted.title()).isEqualTo("Grüezi");
        assertThat(extracted.text()).isEqualTo("Schön");
    }

    private HtmlText.Extracted read(String html) {
        return text.of(html.getBytes(StandardCharsets.UTF_8));
    }
}
