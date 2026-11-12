package ch.admin.bit.jeap.doc.domain.custom;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What an uploaded page says about its title, and where it asked to go among the other items of its chapter.
 * <p>
 * Read out of the front matter, because that is what Docusaurus shows in the navigation and what the
 * documentation asks the pages of a chapter to be sorted by. A page without a title is sorted by its file name
 * instead, which is also what Docusaurus falls back to for the heading. A page without a placement is ordered
 * as every page was before this hint existed.
 * <p>
 * <b>Read by the same parser that writes the block</b> - see {@link UploadedFrontMatter}. Two readers of one
 * format is how a page comes to be sorted under one title and published under another.
 * <p>
 * <b>A value this cannot make sense of reads as none given.</b> Refusing the upload over it is
 * {@code OrderValidation}'s job, which runs before a set is taken over and reads {@link #rawPlacementOf}
 * instead - a page already stored with one, read again on a later build, is read the same way a new one is.
 */
public final class UploadedTitles {

    /**
     * The most of a page that is read to find its title and its placement. A front-matter block is a handful
     * of lines; a page that puts one past this has neither as far as the navigation is concerned.
     */
    public static final int HEAD_BYTES = 8 * 1024;

    /** Escaped rather than written: a raw one is invisible in an editor and in a diff. */
    private static final char BYTE_ORDER_MARK = '﻿';
    private static final String TITLE_KEY = "title";
    private static final String POSITION_KEY = "sidebar_position";
    private static final String AFTER_KEY = "sidebar_after";
    private static final String BEFORE_KEY = "sidebar_before";

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
        Object title = frontMatterOf(head).get(TITLE_KEY);
        if (title == null || title instanceof Iterable || title instanceof Map) {
            return null;
        }
        String read = String.valueOf(title).strip();
        return read.isBlank() ? null : read;
    }

    /**
     * Where the page asked to go among the other items of its chapter.
     * <p>
     * At most one of {@code sidebar_after} and {@code sidebar_before} is read: a page carrying both named a
     * placement that cannot both hold, which the validation refuses - here it reads as neither, which is the
     * same as a page that named none.
     *
     * @param head the first {@link #HEAD_BYTES} of the page, as UTF-8
     */
    public static OrderHint hintOf(byte[] head) {
        Map<String, Object> frontMatter = frontMatterOf(head);
        Double position = UploadedFrontMatter.finiteNumberOf(frontMatter.get(POSITION_KEY));
        String after = scalarOf(frontMatter.get(AFTER_KEY));
        String before = scalarOf(frontMatter.get(BEFORE_KEY));
        if (after != null && before != null) {
            return new OrderHint(position, null, null);
        }
        if (after != null) {
            return new OrderHint(position, OrderHint.Direction.AFTER, after);
        }
        if (before != null) {
            return new OrderHint(position, OrderHint.Direction.BEFORE, before);
        }
        return position == null ? OrderHint.NONE : new OrderHint(position, null, null);
    }

    /**
     * Where the page named a placement, before anything decided what to make of it.
     * <p>
     * <b>For the validation, which has to tell an invalid value from none at all</b> - {@link #hintOf} turns
     * this into the {@link OrderHint} every other reader sees, reading a value it cannot make sense of as
     * none, which is right once a set is taken over and wrong at the upload that is still deciding whether to
     * take it over.
     *
     * @param head the first {@link #HEAD_BYTES} of the page, as UTF-8
     */
    public static UploadedPlacement rawPlacementOf(byte[] head) {
        Map<String, Object> frontMatter = frontMatterOf(head);
        return new UploadedPlacement(frontMatter.get(POSITION_KEY), frontMatter.get(AFTER_KEY),
                frontMatter.get(BEFORE_KEY));
    }

    /** A scalar as its text, or null for a blank one, a mapping or a list - none of which names an item. */
    static String scalarOf(Object value) {
        if (value == null || value instanceof Iterable || value instanceof Map) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isBlank() ? null : text;
    }

    /** The front matter within what was read of the page, parsed once for whatever is read out of it. */
    private static Map<String, Object> frontMatterOf(byte[] head) {
        if (head == null || head.length == 0) {
            return Map.of();
        }
        // A byte order mark is stripped once, from the text: it sits before the opening delimiter, so a line
        // that still carried it would not read as that delimiter.
        String text = new String(head, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        return UploadedFrontMatter.parsed(blockOf(text));
    }

    /**
     * The front matter within what was read of the page.
     * <p>
     * Its own reading rather than the one {@link UploadedFrontMatter} does, and for one reason: this sees the
     * <b>head</b> of a page, so a block that is not closed within it may well be closed further down. There
     * is no telling, and a title or a placement guessed out of half a block is worse than none.
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
