package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the shards of a partitioned table become the one table they logically are, and when they do not.
 * <p>
 * The stem is the cheap signal and the signature over the columns and the primary key is the safe one:
 * anything that differs stays separate, because a reader who is shown one row must not lose the fact that two
 * tables of that name are not alike.
 */
class ShardFamiliesTest {

    private static List<SchemaColumn> columnsOf(String... columns) {
        List<SchemaColumn> columnList = new ArrayList<>();
        for (String column : columns) {
            columnList.add(new SchemaColumn(column, "uuid", false));
        }
        return columnList;
    }

    private static SchemaTable table(String name, String... columns) {
        return new SchemaTable(name, columnsOf(columns), List.of("id"), List.of());
    }

    /** The same table with another primary key, its columns being equal. */
    private static SchemaTable keyedBy(String name, List<String> primaryKey, String... columns) {
        return new SchemaTable(name, columnsOf(columns), primaryKey, List.of());
    }

    /** The same table with a foreign key of its own, its columns and its primary key being equal. */
    private static SchemaTable pointingAt(String name, String referenced, String... columns) {
        return new SchemaTable(name, columnsOf(columns), List.of("id"),
                List.of(new SchemaForeignKey("fk", List.of("ref_id"), referenced, List.of("id"))));
    }

    private static List<SchemaTable> shards(String stem, int from, int to, String... columns) {
        List<SchemaTable> tables = new ArrayList<>();
        for (int suffix = from; suffix <= to; suffix++) {
            tables.add(table(stem + "_" + suffix, columns));
        }
        return tables;
    }

    private static List<String> namesOf(List<SchemaTable> tables) {
        return tables.stream().map(SchemaTable::name).toList();
    }

    @Test
    void whenTablesOfOneStemShareTheirColumns_thenTheyBecomeOneEntryThatSaysWhatItStandsFor() {
        List<SchemaTable> collapsed = ShardFamilies.collapse(shards("doc_additional_info", 1607, 1712, "id"));

        assertThat(namesOf(collapsed)).containsExactly("doc_additional_info_*");
        assertThat(collapsed.getFirst().shards())
                .isEqualTo(new Shards(106, "_1607", "_1712"));
        assertThat(collapsed.getFirst().columns()).describedAs("the family's own columns, which are shared")
                .extracting(SchemaColumn::name).containsExactly("id");
    }

    /** A partition per day, which is the other suffix convention in the estate. */
    @Test
    void whenTheSuffixIsADate_thenItIsAPartitionKeyToo() {
        List<SchemaTable> daily = List.of(
                table("feature_container_partition_day_20260901", "id"),
                table("feature_container_partition_day_20260902", "id"),
                table("feature_container_partition_day_20260903", "id"),
                table("feature_container_partition_day_20260904", "id"),
                table("feature_container_partition_day_202610", "id"));

        List<SchemaTable> collapsed = ShardFamilies.collapse(daily);

        assertThat(namesOf(collapsed)).containsExactly("feature_container_partition_day_*");
        assertThat(collapsed.getFirst().shards())
                .describedAs("a monthly key among daily ones ranges as the number it is")
                .isEqualTo(new Shards(5, "_202610", "_20260904"));
    }

    /** {@code _ym110} and {@code _ch71}: an ordinal with a short tag in front of it. */
    @Test
    void whenTheSuffixIsATaggedOrdinal_thenItIsAPartitionKeyToo() {
        List<SchemaTable> index = List.of(table("doc_index_ym110", "id"), table("doc_index_ym111", "id"),
                table("doc_index_ym112", "id"), table("doc_index_ym113", "id"), table("doc_index_ym114", "id"));

        assertThat(namesOf(ShardFamilies.collapse(index))).containsExactly("doc_index_*");
    }

    /**
     * <b>Four do not collapse and five do.</b> The bound guards against a schema that does not exist yet: in
     * the measured estate nothing is anywhere near it.
     */
    @Test
    void whenThereAreFewerThanFiveOfAStem_thenNothingIsCollapsed() {
        assertThat(namesOf(ShardFamilies.collapse(shards("audit", 1, 4, "id"))))
                .containsExactly("audit_1", "audit_2", "audit_3", "audit_4");
        assertThat(namesOf(ShardFamilies.collapse(shards("audit", 1, 5, "id"))))
                .containsExactly("audit_*");
    }

    /** A lone table whose name happens to end in a number is a table, not a family. */
    @Test
    void whenATableStandsAlone_thenItKeepsItsName() {
        assertThat(namesOf(ShardFamilies.collapse(List.of(table("x_1", "id")))))
                .containsExactly("x_1");
    }

    /**
     * <b>The signature is what makes the stem safe to use.</b> An {@code order_v1} and an {@code order_v2}
     * whose columns differ are two tables, and a reader shown one row would never learn that.
     */
    @Test
    void whenTablesOfOneStemDoNotShareTheirColumns_thenNoneOfThemIsCollapsed() {
        List<SchemaTable> versions = new ArrayList<>(shards("order", 1, 5, "id"));
        versions.add(table("order_6", "id", "tenant"));

        assertThat(namesOf(ShardFamilies.collapse(versions))).containsExactly(
                "order_1", "order_2", "order_3", "order_4", "order_5", "order_6");
    }

    /** Two stems are two families, and neither takes the other's tables. */
    @Test
    void whenThereAreSeveralFamilies_thenEachBecomesItsOwnEntry() {
        List<SchemaTable> tables = new ArrayList<>(shards("doc_meta", 1, 5, "id"));
        tables.addAll(shards("doc_body", 1, 5, "id", "body"));
        tables.add(table("orders_order", "id"));

        assertThat(namesOf(ShardFamilies.collapse(tables)))
                .containsExactlyInAnyOrder("doc_meta_*", "doc_body_*", "orders_order");
    }

    /** The suffix range reads as numbers where it is numbers, so nine comes before ten. */
    @Test
    void whenTheSuffixesAreOfDifferentLength_thenTheRangeIsStillTheLowestToTheHighest() {
        List<SchemaTable> collapsed = ShardFamilies.collapse(shards("bucket", 8, 12, "id"));

        assertThat(collapsed.getFirst().shards()).isEqualTo(new Shards(5, "_8", "_12"));
    }

    /**
     * <b>The primary key is part of the signature.</b> Partitions of one table share it, so a stem whose
     * tables do not is not one table published as several - and a reader shown one row would never learn it.
     */
    @Test
    void whenTablesOfOneStemDoNotShareTheirPrimaryKey_thenNoneOfThemIsCollapsed() {
        List<SchemaTable> versions = new ArrayList<>(shards("audit", 1, 5, "id", "tenant"));
        versions.add(keyedBy("audit_6", List.of("id", "tenant"), "id", "tenant"));

        assertThat(namesOf(ShardFamilies.collapse(versions))).containsExactly(
                "audit_1", "audit_2", "audit_3", "audit_4", "audit_5", "audit_6");
    }

    /**
     * <b>The foreign keys are deliberately not compared.</b> A manually sharded child points at the
     * counterpart shard of its own parent, so comparing them would keep every such family apart. The
     * representative carries the arrows of one shard.
     */
    @Test
    void whenOnlyTheForeignKeysDiffer_thenItIsStillOneFamily() {
        List<SchemaTable> children = new ArrayList<>();
        for (int suffix = 1; suffix <= 5; suffix++) {
            children.add(pointingAt("doc_body_" + suffix, "doc_meta_" + suffix, "id"));
        }

        List<SchemaTable> collapsed = ShardFamilies.collapse(children);

        assertThat(namesOf(collapsed)).containsExactly("doc_body_*");
        assertThat(collapsed.getFirst().foreignKeys())
                .describedAs("the first shard's, because nothing unions them")
                .extracting(SchemaForeignKey::referencedTableName).containsExactly("doc_meta_1");
    }

    /** A zero-padded ordinal is the number it is, so {@code _007} ranges where seven belongs. */
    @Test
    void whenTheSuffixesAreZeroPadded_thenTheRangeReadsThemAsNumbers() {
        List<SchemaTable> buckets = List.of(table("bucket_007", "id"), table("bucket_8", "id"),
                table("bucket_9", "id"), table("bucket_10", "id"), table("bucket_11", "id"));

        assertThat(ShardFamilies.collapse(buckets).getFirst().shards())
                .isEqualTo(new Shards(5, "_007", "_11"));
    }

    /**
     * A family that mixes a plain ordinal with a tagged one still has an order: the tag first, then the
     * number. Comparing the strings by length where both are digits and lexicographically otherwise is not
     * one at all, and a sort can end such a comparison with an exception.
     */
    @Test
    void whenAFamilyMixesPlainAndTaggedOrdinals_thenTheRangeIsTheLowestToTheHighest() {
        List<SchemaTable> mixed = List.of(table("bucket_9", "id"), table("bucket_10", "id"),
                table("bucket_007", "id"), table("bucket_2", "id"), table("bucket_ym3", "id"));

        assertThat(ShardFamilies.collapse(mixed).getFirst().shards())
                .isEqualTo(new Shards(5, "_2", "_ym3"));
    }

    /**
     * Any run of digits is a partition key. Nothing validates a date: the published schema does not say what
     * a suffix means, so a thirteenth month partitions as well as a twelfth - and a key too large for an
     * {@code int} ranges as the number it is.
     */
    @Test
    void whenTheSuffixIsDigitsThatAreNoDate_thenItIsAPartitionKeyAllTheSame() {
        List<SchemaTable> odd = List.of(table("audit_202613", "id"), table("audit_202614", "id"),
                table("audit_18991231", "id"), table("audit_0", "id"), table("audit_99999999999", "id"));

        List<SchemaTable> collapsed = ShardFamilies.collapse(odd);

        assertThat(namesOf(collapsed)).containsExactly("audit_*");
        assertThat(collapsed.getFirst().shards()).isEqualTo(new Shards(5, "_0", "_99999999999"));
    }

    /**
     * What the diagram needs: a foreign key into a shard has to find the entity its family became, or every
     * arrow in a collapsed schema is dropped as pointing at nothing drawn.
     */
    @Test
    void familyNameOf_thenAShardResolvesToItsFamilyAndAnythingElseToNothing() {
        assertThat(ShardFamilies.familyNameOf("doc_meta_1607")).isEqualTo("doc_meta_*");
        assertThat(ShardFamilies.familyNameOf("doc_meta_20260904")).isEqualTo("doc_meta_*");
        assertThat(ShardFamilies.familyNameOf("orders_order")).isNull();
        assertThat(ShardFamilies.familyNameOf("orders")).isNull();
        assertThat(ShardFamilies.familyNameOf(null)).isNull();
    }
}
