package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the shards of a partitioned table become the one table they logically are, and when they do not.
 * <p>
 * The stem is the cheap signal and the column signature is the safe one: anything that differs stays separate,
 * because a reader who is shown one row must not lose the fact that two tables of that name are not alike.
 */
class ShardFamiliesTest {

    private static SchemaTable table(String name, String... columns) {
        List<SchemaColumn> columnList = new ArrayList<>();
        for (String column : columns) {
            columnList.add(new SchemaColumn(column, "uuid", false));
        }
        return new SchemaTable(name, columnList, List.of("id"), List.of());
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
        assertThat(collapsed.getFirst().shards().count()).isEqualTo(5);
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
