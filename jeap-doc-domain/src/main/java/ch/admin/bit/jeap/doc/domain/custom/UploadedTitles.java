package ch.admin.bit.jeap.doc.domain.custom;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What an uploaded page says its title is.
 * <p>
 * Read out of the front matter, because that is what Docusaurus shows in the navigation and what the
 * documentation asks the pages of a chapter to be sorted by. A page without one is sorted by its file name
 * instead, which is also what Docusaurus falls back to for the heading.
 * <p>
 * <b>Read by the same parser that writes the block</b> - see {@link UploadedFrontMatter}. Two readers of one
 * format is how a page comes to be sorted under one title and published under another.
 */
public final class UploadedTitles {

    /**
     * The most of a page that is read to find its title. A front-matter block is a handful of lines; a page
     * that puts one past this has none as far as the navigation is concerned.
     */
    public static final int HEAD_BYTES = 8 * 1024;

    /** Escaped rather than written: a raw one is invisible in an editor and in a diff. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final String TITLE_KEY = "title";

    private UploadedTitles() {
    }

    /**
     * The title in the front matter of a page, or null where it has none.
     * <p>
     * Only a scalar is a title: a mapping or a list under {@code title} is a page that says something this
     * navigation cannot show, and reads as no title rather than as an error - because a page with a strange
     * title still has to be published.
     *
     * @param head the first {@link #HEAD_BYTES} of the page, as UTF-8
     */
    public static String titleOf(byte[] head) {
        if (head == null || head.length == 0) {
            return null;
        }
        // A byte order mark is stripped once, from the text: it sits before the opening delimiter, so a line
        // that still carried it would not read as that delimiter.
        String text = new String(head, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        Object title = UploadedFrontMatter.parsed(blockOf(text)).get(TITLE_KEY);
        if (title == null || title instanceof Iterable || title instanceof java.util.Map) {
            return null;
        }
        String read = String.valueOf(title).strip();
        return read.isBlank() ? null : read;
    }

    /**
     * The front matter within what was read of the page.
     * <p>
     * Its own reading rather than the one {@link UploadedFrontMatter} does, and for one reason: this sees the
     * <b>head</b> of a page, so a block that is not closed within it may well be closed further down. There
     * is no telling, and a title guessed out of half a block is worse than none.
     */
    private static String blockOf(String text) {
        List<String> lines = List.of(text.split("\n", -1));
        int first = 0;
        while (first < lines.size() && lines.get(first).isBlank()) {
            first++;
        }
        if (first >= lines.size() || !lines.get(first).strip().equals("---")) {
            return "";
        }
        for (int i = first + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return String.join("\n", lines.subList(first + 1, i));
            }
        }
        return "";
    }
}
