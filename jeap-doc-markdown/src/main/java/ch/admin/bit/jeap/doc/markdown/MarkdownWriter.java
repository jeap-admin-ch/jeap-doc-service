package ch.admin.bit.jeap.doc.markdown;

import java.util.List;

/**
 * Writes one Markdown page, block by block.
 * <p>
 * A structure template says what a page contains. This says how it is written: the blank line between blocks,
 * the alignment row under a table header, the escaping of a pipe in a cell, the fence around a diagram.
 * <p>
 * <b>There is no {@code raw(String)} method on purpose.</b> It would let a caller hand over preformatted text
 * and end the guarantee that {@link Markdown} gives.
 * <p>
 * Not thread-safe. One instance writes one page.
 */
public final class MarkdownWriter {

    /** What a table cell shows when the value is missing. A blank cell reads as a defect. */
    public static final String NOT_KNOWN = "-";

    private final StringBuilder page = new StringBuilder(4096);

    /** Where the body starts, so {@link #hasContent()} can ignore the front matter. */
    private int bodyStartsAt;

    /**
     * The Docusaurus front matter, which has to be the very first thing on the page.
     */
    public MarkdownWriter frontMatter(FrontMatter frontMatter) {
        if (page.length() > 0) {
            throw new IllegalStateException(
                    "The front matter has to be the first thing on a page, and something is already written.");
        }
        page.append(frontMatter.render());
        bodyStartsAt = page.length();
        return this;
    }

    public MarkdownWriter heading(int level, String text) {
        return heading(level, Md.text(text));
    }

    /** A heading. Level 1 is the page title. The table of contents shows levels 2 and 3. */
    public MarkdownWriter heading(int level, Markdown text) {
        if (level < 1 || level > 4) {
            throw new IllegalArgumentException("A heading is between level 1 and level 4, not " + level + ".");
        }
        return block("#".repeat(level) + " " + text.value());
    }

    public MarkdownWriter paragraph(String text) {
        return paragraph(Md.text(text));
    }

    public MarkdownWriter paragraph(Markdown text) {
        return text.isEmpty() ? this : block(withoutOpeningABlock(text.value()));
    }

    /**
     * A backslash before what would turn the paragraph into something else.
     * <p>
     * {@link Md} escapes what means something <i>inside</i> a line, and deliberately not what means something
     * only as the first thing of one - a heading reading {@code 1. Introduction and Goals} is not an ordered
     * list, and a cell beginning with a dash is not a bullet. Here it is: a paragraph is exactly a fragment
     * standing at the start of a block, so an imported description beginning {@code - } would become a list and
     * one reading {@code ---} a thematic break - and where the page carries no front matter, a line the site
     * generator hands to a YAML parser.
     * <p>
     * The backslash goes before the punctuation, never before a digit: CommonMark escapes punctuation only, so
     * {@code \1.} would put a visible backslash on the page where {@code 1\.} puts nothing.
     */
    private static String withoutOpeningABlock(String paragraph) {
        if (paragraph.isEmpty()) {
            return paragraph;
        }
        char first = paragraph.charAt(0);
        if (first == '-' || first == '+') {
            return "\\" + paragraph;
        }
        int digits = 0;
        while (digits < paragraph.length() && Character.isDigit(paragraph.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits < paragraph.length()
            && (paragraph.charAt(digits) == '.' || paragraph.charAt(digits) == ')')) {
            return paragraph.substring(0, digits) + "\\" + paragraph.substring(digits);
        }
        return paragraph;
    }

    /** A bullet list. An empty list writes nothing; use {@link #paragraphOrNothing} to say so instead. */
    public MarkdownWriter bulletList(List<Markdown> items) {
        if (items.isEmpty()) {
            return this;
        }
        StringBuilder list = new StringBuilder();
        for (Markdown item : items) {
            if (item.isEmpty()) {
                continue;
            }
            list.append("- ").append(oneLine(item.value())).append('\n');
        }
        return list.isEmpty() ? this : block(list.substring(0, list.length() - 1));
    }

    /**
     * A table. Every row has to have as many cells as the header, because a short row renders as a table with
     * a gap rather than as an error.
     * <p>
     * Cells are escaped once more here, for the two things that only mean something inside a table: a pipe
     * splits the cell, and a line break ends the row.
     */
    public MarkdownWriter table(List<String> headers, List<List<Markdown>> rows) {
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("A table needs at least one column.");
        }
        StringBuilder table = new StringBuilder();
        // A table without column names is how a two-column list of properties is written; its header cells
        // stay empty rather than becoming the not-known dash, which is for a value that is missing.
        table.append(row(headers.stream().map(Md::text).toList(), ""));
        table.append("|").append(" --- |".repeat(headers.size())).append('\n');
        for (List<Markdown> cells : rows) {
            if (cells.size() != headers.size()) {
                throw new IllegalArgumentException(
                        "A table row has %d cells but the table has %d columns: %s"
                                .formatted(cells.size(), headers.size(), cells));
            }
            table.append(row(cells, NOT_KNOWN));
        }
        return block(table.substring(0, table.length() - 1));
    }

    /**
     * A fenced block whose body is not Markdown: a diagram, or a snippet of JSON. The body is passed through
     * untouched. It has to be valid in its own language, which is the business of whoever produced it.
     */
    public MarkdownWriter fence(String language, String body) {
        if (language == null || !language.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("A fence language is a plain lower-case word, not " + language);
        }
        String content = body == null ? "" : body.stripTrailing();
        // A body with a fence of its own would end the block early, so the outer fence is always longer.
        String fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
        return block(fence + language + "\n" + content + "\n" + fence);
    }

    /**
     * A Docusaurus admonition, {@code :::info[Title]}. It is a remark plugin and not a component, so it works
     * in CommonMark and needs nothing from the site template.
     */
    public MarkdownWriter admonition(String kind, String title, Markdown body) {
        if (kind == null || !kind.matches("[a-z]+")) {
            throw new IllegalArgumentException("An admonition kind is a plain lower-case word, not " + kind);
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("An admonition with nothing in it is a box the reader has to "
                                               + "wonder about; write no admonition instead.");
        }
        String head = title == null || title.isBlank()
                ? ":::" + kind
                : ":::" + kind + "[" + Md.text(title).value() + "]";
        return block(head + "\n\n" + body.value() + "\n\n:::");
    }

    /** Writes the paragraph, or the alternative when there is nothing to say. */
    public MarkdownWriter paragraphOrNothing(Markdown text, String whenEmpty) {
        return text.isEmpty() ? paragraph(whenEmpty) : paragraph(text);
    }

    /** The page, ending in exactly one newline. */
    public String text() {
        return page.toString();
    }

    /** Whether anything but the front matter has been written. */
    public boolean hasContent() {
        return page.length() > bodyStartsAt;
    }

    private MarkdownWriter block(String content) {
        if (page.length() > 0 && page.charAt(page.length() - 1) != '\n') {
            page.append('\n');
        }
        if (page.length() > 0) {
            page.append('\n');
        }
        page.append(content).append('\n');
        return this;
    }

    private static String row(List<Markdown> cells, String whenEmpty) {
        StringBuilder line = new StringBuilder("|");
        for (Markdown cell : cells) {
            line.append(' ').append(cellOf(cell, whenEmpty)).append(" |");
        }
        return line.append('\n').toString();
    }

    private static String cellOf(Markdown cell, String whenEmpty) {
        if (cell.isEmpty()) {
            return whenEmpty;
        }
        return oneLine(cell.value()).replace("|", "\\|");
    }

    private static String oneLine(String value) {
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private static int longestBacktickRun(String value) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < value.length(); i++) {
            run = value.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
