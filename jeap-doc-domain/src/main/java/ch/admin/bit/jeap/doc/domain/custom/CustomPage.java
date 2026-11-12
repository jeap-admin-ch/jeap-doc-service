package ch.admin.bit.jeap.doc.domain.custom;

/**
 * One file of a documentation set: a page, or an asset a page shows or links to.
 * <p>
 * The title and the position are read when the set is taken over, not when a site is built. A build needs
 * them to order the navigation, and reading them out of the archive on every build would be the same answer
 * computed again.
 *
 * @param chapter  the chapter folder the file lies in
 * @param fileName the path below the chapter folder: a page's name, or an asset's name with the folders it
 *                 may lie in
 * @param title    the title of the page, or null for an asset and for a page that carries none
 * @param position where the page goes among the pages of its chapter
 * @param asset    whether this is an asset rather than a page
 * @param hint     where the page asked to go among the other items of its chapter; {@link OrderHint#NONE} for
 *                 a page, and always, for an asset
 */
public record CustomPage(String chapter, String fileName, String title, int position, boolean asset,
                          OrderHint hint) {

    /** A page or asset with no declared placement among its siblings - every one before this hint existed. */
    public CustomPage(String chapter, String fileName, String title, int position, boolean asset) {
        this(chapter, fileName, title, position, asset, OrderHint.NONE);
    }

    /** The path of the file inside the set, which is what the archive holds it under. */
    public String path() {
        // A static site's files lie in no chapter: their path is the file name.
        return chapter.isEmpty() ? fileName : chapter + "/" + fileName;
    }
}
