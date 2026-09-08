package ch.admin.bit.jeap.doc.domain.port;

/**
 * What a documentation site adds up to as it is published, across its parts.
 *
 * @param parts how many parts of it have a publication
 * @param pages how many pages they hold between them
 * @param bytes how large they are between them
 */
public record PublicationTotals(int parts, int pages, long bytes) {

    /** A site nothing has been published for. */
    public static PublicationTotals none() {
        return new PublicationTotals(0, 0, 0);
    }
}
