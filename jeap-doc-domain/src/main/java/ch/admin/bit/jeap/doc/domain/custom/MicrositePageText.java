package ch.admin.bit.jeap.doc.domain.custom;

/**
 * One page of an uploaded microsite, as the search index holds it.
 * <p>
 * A microsite is served file by file and no build writes its pages into a content tree, so this is the only
 * form in which its content reaches the index. It is produced once, when the set is uploaded, and read back
 * by every index run.
 *
 * @param path  the path within the set, which is what a hit points at
 * @param title what the page calls itself, or its path where it calls itself nothing
 * @param text  what a reader would read on it, as one line
 */
public record MicrositePageText(String path, String title, String text) {
}
