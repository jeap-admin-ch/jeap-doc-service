package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * What a collapsed table stands for: the partitions of one logical table, summarised.
 * <p>
 * Carried so that the page can say it. A row reading {@code doc_meta_*}, 125 partitions, {@code _1607} to
 * {@code _2609} hides nothing - it summarises, and the count and the range are what let a reader see that.
 *
 * @param count       how many tables were collapsed into this one
 * @param firstSuffix the lowest partition key, with its separator - {@code _1607}
 * @param lastSuffix  the highest
 * @param partialKeys how many foreign keys only some of the tables declare. The entry shows the keys all of
 *                    them declare - see {@link SharedKeys} - and the page says how many it left out
 */
public record Shards(int count, String firstSuffix, String lastSuffix, int partialKeys) {

    /** A family whose keys have not been compared yet, or whose tables all declare the same ones. */
    public Shards(int count, String firstSuffix, String lastSuffix) {
        this(count, firstSuffix, lastSuffix, 0);
    }
}
