package ch.admin.bit.jeap.doc.domain.port;

/**
 * Reads an HTML document as the text a reader would see.
 * <p>
 * What it is for is the search index: an uploaded microsite is published as it was built and is served file by
 * file, so the only way its content can be searched is to read the text out of it once, when it is uploaded.
 * Parsing HTML is infrastructure - a dependency, and one that has to survive whatever a generator produced -
 * so it is a port here and a library behind it.
 */
public interface HtmlText {

    /**
     * The title and the text of one document.
     *
     * @param html a document as it was uploaded, which may be malformed and may have been cut off at a byte
     *             bound - neither is a failure, and what could be read is what is answered
     */
    Extracted of(byte[] html);

    /**
     * What one document contributes to the index.
     *
     * @param title the document's own title, or empty where it names none - the caller decides what stands
     *              in, because only it knows what the document is called
     * @param text  what a reader would read, as one line, without the navigation and the scripts around it
     */
    record Extracted(String title, String text) {

        /** A document nothing could be read from: not an error, and not worth a record in the index. */
        public static final Extracted NOTHING = new Extracted("", "");

        public boolean isEmpty() {
            return title.isBlank() && text.isBlank();
        }
    }
}
