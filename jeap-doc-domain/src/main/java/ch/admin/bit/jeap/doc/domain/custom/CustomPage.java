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
 */
public record CustomPage(String chapter, String fileName, String title, int position, boolean asset) {

    /** The path of the file inside the set, which is what the archive holds it under. */
    public String path() {
        return chapter + "/" + fileName;
    }
}
