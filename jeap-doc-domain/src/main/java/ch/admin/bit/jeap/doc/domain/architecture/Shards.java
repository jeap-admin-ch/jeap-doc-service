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
 */
public record Shards(int count, String firstSuffix, String lastSuffix) {
}
