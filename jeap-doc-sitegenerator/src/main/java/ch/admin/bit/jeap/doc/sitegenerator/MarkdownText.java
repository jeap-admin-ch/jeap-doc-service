package ch.admin.bit.jeap.doc.sitegenerator;

/**
 * Puts a configured value into a generated page as the text it is.
 * <p>
 * The pages the generator writes are {@code .md}, and Docusaurus parses those as MDX - so a title of
 * {@code <script>alert(1)</script>} is an element rather than a heading, and one containing <code>{}</code> is
 * an expression. Two things follow from that, and the second is the one that bites first:
 * <ul>
 *   <li>markup written by whoever configured the instance would run in the browser of everyone reading the
 *   documentation;</li>
 *   <li><b>a title with a stray {@code <} or <code>{</code> in it fails the build</b> - minutes into a run, with
 *   an MDX parse error naming a line of a generated file rather than the property it came from.</li>
 * </ul>
 * The values are the instance's own configuration rather than anything uploaded, so this is not a way in from
 * outside. It is what keeps a plain-text title plain text, whether it was meant well or not.
 */
final class MarkdownText {

    private MarkdownText() {
    }

    /**
     * The given text as a character reference for everything that would otherwise stop being text: the two
     * characters that open markup ({@code <}, <code>{</code>) and the ones that close them, plus the ampersand
     * that introduces a reference - it goes first, or the references written here would be escaped again.
     * <p>
     * Emphasis and code markers are deliberately left alone. A title with an asterisk in it renders in italics,
     * which is cosmetic, while a title with a {@code <} in it is a page that does not build - and escaping every
     * character Markdown gives a meaning to would be a rule nobody could predict the output of.
     */
    static String escaped(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("{", "&#123;")
                .replace("}", "&#125;");
    }
}
