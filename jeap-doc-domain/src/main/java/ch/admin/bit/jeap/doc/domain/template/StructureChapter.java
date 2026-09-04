package ch.admin.bit.jeap.doc.domain.template;

import ch.admin.bit.jeap.doc.domain.Slugs;

import java.util.Comparator;

/**
 * One chapter of a structure template, numbered or not.
 * <p>
 * A chapter has three names, and they differ on purpose:
 * <ul>
 *   <li>the <b>folder</b>, {@code 5-building-block-view}, is what an upload carries and what is written on
 *   disk. Where the template numbers its chapters, the number is the prefix of the folder;</li>
 *   <li>the <b>URL segment</b>, {@code building-block-view}, is the folder without that prefix. Links survive
 *   a renumbering;</li>
 *   <li>the <b>label</b>, {@code 5. Building Block View}, puts the number back for the reader.</li>
 * </ul>
 * <p>
 * <b>The number is optional, and a template either numbers every chapter or none.</b> arc42 numbers its
 * chapters and the numbers are part of the method, so they belong in the folder, in the label and in the order.
 * A methodology that does not number its chapters has nothing to put there: its folders are the URL segments,
 * its labels are the titles, and the order of the navigation is the alphabet - see
 * {@link StructureTemplate#orderedChapters()}. Which of the two a chapter is, is said by the factory that made
 * it, and the two are never mixed within one template - {@link StructureTemplates} refuses that while the
 * service starts.
 *
 * @param number the chapter number, which orders it, or {@code null} where the template does not number its
 *               chapters
 * @param folder the folder name, carrying the number as a prefix where there is one
 * @param title  the chapter title, without the number
 */
public record StructureChapter(Integer number, String folder, String title) {

    /**
     * How the chapters of a template are ordered: by number where they are numbered, and by title - the
     * alphabet, ignoring case - where they are not.
     * <p>
     * Total rather than only defined for the kind a template actually uses, so that sorting a list can never
     * depend on the order it arrived in. The numbered ones come first, which only matters for a mixture that
     * cannot be configured.
     */
    public static final Comparator<StructureChapter> ORDER =
            Comparator.comparingInt((StructureChapter chapter) -> chapter.isNumbered() ? 0 : 1)
                    .thenComparingInt(chapter -> chapter.isNumbered() ? chapter.number() : 0)
                    .thenComparing(StructureChapter::title, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(StructureChapter::folder);

    public StructureChapter {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A chapter has a title; the folder '%s' carries none."
                    .formatted(folder));
        }
        if (!Slugs.isSlug(folder)) {
            // The folder is a path segment on disk, in an upload and - for an unnumbered chapter - in the URL.
            throw new IllegalArgumentException("The folder of the chapter '%s' is '%s', which is not a slug (%s)."
                    .formatted(title, folder, Slugs.DESCRIPTION));
        }
        if (number == null) {
            requireNoNumberPrefix(folder, title);
        } else {
            requireNumberPrefix(number, folder);
        }
    }

    /**
     * A chapter of a template that numbers its chapters. The folder carries the number, the label puts it back,
     * and the number is what orders the chapter.
     */
    public static StructureChapter numbered(int number, String folder, String title) {
        return new StructureChapter(number, folder, title);
    }

    /**
     * A chapter of a template that does not number its chapters. The folder is the URL segment, the label is
     * the title, and the chapters are ordered alphabetically by title.
     */
    public static StructureChapter unnumbered(String folder, String title) {
        return new StructureChapter(null, folder, title);
    }

    /** Whether this chapter carries a number - and therefore whether its template numbers its chapters. */
    public boolean isNumbered() {
        return number != null;
    }

    /**
     * The folder without its number prefix, which is what the site generator serves it at. For an unnumbered
     * chapter that is the folder itself.
     */
    public String urlSegment() {
        return isNumbered() ? folder.substring(String.valueOf(number).length() + 1) : folder;
    }

    /** What the navigation shows: the numbered chapter with its number, the unnumbered one its title. */
    public String label() {
        return isNumbered() ? number + ". " + title : title;
    }

    private static void requireNumberPrefix(int number, String folder) {
        if (number < 1) {
            throw new IllegalArgumentException("A chapter is numbered from 1, not " + number);
        }
        String prefix = number + "-";
        if (!folder.startsWith(prefix) || folder.length() == prefix.length()) {
            throw new IllegalArgumentException(
                    "The folder of chapter %d has to start with '%s' and name something after it; '%s' does not."
                            .formatted(number, prefix, folder));
        }
    }

    /**
     * An unnumbered chapter's folder may not begin with a digit.
     * <p>
     * Docusaurus strips a number prefix from a folder name by itself, and it is what makes the numbered chapters
     * work: {@code 5-building-block-view} is served at {@code building-block-view}. So a template that does not
     * number its chapters and calls a folder {@code 2024-decisions} would find it served somewhere the doc
     * service never wrote a link to - and no build would fail, because nothing here would know.
     */
    private static void requireNoNumberPrefix(String folder, String title) {
        if (Character.isDigit(folder.charAt(0))) {
            throw new IllegalArgumentException(
                    ("The chapter '%s' has no number, so its folder may not begin with a digit - and '%s' does. "
                     + "A leading number is stripped from the URL, so the chapter would be served at '%s' "
                     + "instead. Either number the chapter or rename the folder.")
                            .formatted(title, folder, folder.replaceFirst("^\\d+[-_.]?", "")));
        }
    }
}
