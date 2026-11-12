package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which tables a configured group documents as one entry, and which it leaves to {@link ShardFamilies}.
 * <p>
 * The pattern selects and the shape decides: a table whose columns or primary key differ from the group's
 * stays an entry of its own, because a reader shown one entry must not lose it.
 */
class TableGroupsTest {

    private static final SchemaTableGroup BY_MONTH =
            SchemaTableGroup.of("id_<entity>_<yymm>", "id_(?<entity>.+)_(?<partition>\\d{4})");

    private static SchemaTable table(String name, String... columns) {
        List<SchemaColumn> columnList = new ArrayList<>();
        for (String column : columns) {
            columnList.add(new SchemaColumn(column, "varchar", false));
        }
        return new SchemaTable(name, columnList, List.of(columns[0]), List.of());
    }

    private static SchemaTable keyedBy(String name, List<String> primaryKey, String... columns) {
        List<SchemaColumn> columnList = new ArrayList<>();
        for (String column : columns) {
            columnList.add(new SchemaColumn(column, "varchar", false));
        }
        return new SchemaTable(name, columnList, primaryKey, List.of());
    }

    /** A table of the monthly shape with one foreign key from {@code ref_id} into {@code target}. */
    private static SchemaTable referencing(String name, String target) {
        return new SchemaTable(name, List.of(new SchemaColumn("id_real", "varchar", false),
                new SchemaColumn("ref_id", "varchar", false)), List.of("id_real"),
                List.of(new SchemaForeignKey("fk_" + name, List.of("ref_id"), target, List.of("id"))));
    }

    private static DatabaseSchema schemaOf(List<SchemaTable> tables) {
        return new DatabaseSchema("orders_db", "1", tables);
    }

    private static List<String> namesOf(List<SchemaTable> tables) {
        return tables.stream().map(SchemaTable::name).toList();
    }

    /** One entity per name and month, as a component that copies its identifiers per month keeps them. */
    private static List<SchemaTable> monthly(String entity, int from, int to) {
        List<SchemaTable> tables = new ArrayList<>();
        for (int month = from; month <= to; month++) {
            tables.add(table("id_" + entity + "_" + month, "id_real", "hash"));
        }
        return tables;
    }

    @Test
    void whenTablesOfSeveralEntitiesMatchAndShareTheirShape_thenTheyAreOneEntryListingTheEntities() {
        List<SchemaTable> tables = new ArrayList<>(monthly("orders", 2601, 2612));
        tables.addAll(monthly("parties", 2610, 2612));
        tables.add(table("orders_order", "id"));

        List<SchemaTable> documented = schemaOf(tables).documentedTables(List.of(BY_MONTH));

        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "orders_order");
        SchemaTable entry = documented.getFirst();
        assertThat(entry.group()).isEqualTo(new GroupedTables(15, List.of(
                new GroupedTables.Member("orders", 12, "2601", "2612"),
                new GroupedTables.Member("parties", 3, "2610", "2612")), 0, 0));
        assertThat(entry.columns()).extracting(SchemaColumn::name).containsExactly("id_real", "hash");
        assertThat(entry.shards()).describedAs("a group is not a family of partitions").isNull();
    }

    /**
     * <b>The shape is still a prerequisite.</b> A table with the same columns and another primary key is
     * another table, so it is documented as one - and the entry says it left it out.
     */
    @Test
    void whenAMatchingTableHasAnotherPrimaryKey_thenItIsAnEntryOfItsOwnAndTheGroupSaysSo() {
        List<SchemaTable> tables = new ArrayList<>(monthly("orders", 2601, 2603));
        tables.add(keyedBy("id_parties_2601", List.of("id_real", "hash"), "id_real", "hash"));

        List<SchemaTable> documented = schemaOf(tables).documentedTables(List.of(BY_MONTH));

        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "id_parties_2601");
        assertThat(documented.getFirst().group().tables()).isEqualTo(3);
        assertThat(documented.getFirst().group().differentShape()).isEqualTo(1);
    }

    /**
     * The tables of another shape are not dropped: what is left of them goes through the grouping of
     * partitions like any other table, so a family among them is still collapsed.
     */
    @Test
    void whenTheTablesOfAnotherShapeAreAFamily_thenTheyAreCollapsedLikeAnyOther() {
        List<SchemaTable> tables = new ArrayList<>(monthly("orders", 2601, 2606));
        for (int month = 2601; month <= 2602; month++) {
            tables.add(table("id_parties_" + month, "id_real", "hash", "tenant"));
        }

        List<SchemaTable> documented = schemaOf(tables).documentedTables(List.of(BY_MONTH));

        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "id_parties_*");
        assertThat(documented.getFirst().group().differentShape()).isEqualTo(2);
    }

    /** A tie between two shapes goes to the one whose first table sorts first, whatever the upstream order. */
    @Test
    void whenTwoShapesAreEquallyCommon_thenTheFirstByNameIsTheGroup() {
        List<SchemaTable> tables = new ArrayList<>(List.of(
                table("id_zebra_2601", "id_real", "tenant"), table("id_zebra_2602", "id_real", "tenant")));
        tables.addAll(monthly("alpha", 2601, 2602));

        List<SchemaTable> documented = schemaOf(tables).documentedTables(List.of(BY_MONTH));

        assertThat(documented.getFirst().group().members()).extracting(GroupedTables.Member::entity)
                .containsExactly("alpha");
        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "id_zebra_*");
    }

    /**
     * <b>The entities of a group can point at different tables</b>, so the entry shows only the keys all of its
     * tables declare. Showing the first table's keys would tell the reader that every entity points at the
     * orders, which the parties do not.
     */
    @Test
    void whenTheEntitiesOfAGroupPointAtDifferentTables_thenTheEntryShowsNoneOfThoseKeysAndCountsThem() {
        List<SchemaTable> tables = List.of(
                referencing("id_orders_2601", "orders_order"), referencing("id_orders_2602", "orders_order"),
                referencing("id_parties_2601", "orders_party"), referencing("id_parties_2602", "orders_party"),
                table("orders_order", "id"), table("orders_party", "id"));

        SchemaTable entry = schemaOf(tables).documentedTables(List.of(BY_MONTH)).getFirst();

        assertThat(entry.name()).isEqualTo("id_<entity>_<yymm>");
        assertThat(entry.foreignKeys()).isEmpty();
        assertThat(entry.group().partialKeys()).isEqualTo(2);
    }

    /**
     * A key every table declares stays, also where the tables point at different partitions of one parent:
     * they are the same entry on the page, so the key is the same key.
     */
    @Test
    void whenEveryTableOfAGroupPointsAtTheSameEntry_thenTheEntryKeepsTheKey() {
        List<SchemaTable> tables = List.of(
                referencing("id_orders_2601", "orders_order_2601"),
                referencing("id_parties_2601", "orders_order_2602"),
                table("orders_order_2601", "id"), table("orders_order_2602", "id"));

        SchemaTable entry = schemaOf(tables).documentedTables(List.of(BY_MONTH)).getFirst();

        assertThat(entry.foreignKeys()).extracting(SchemaForeignKey::referencedTableName)
                .containsExactly("orders_order_2601");
        assertThat(entry.group().partialKeys()).isZero();
    }

    private static final SchemaTableGroup ITEMS =
            SchemaTableGroup.of("item_<entity>_<yymm>", "item_(?<entity>.+)_(?<partition>\\d{4})");

    /** The key of a group entry and the entry it points at, as the page resolves it. */
    private static List<String> targetsOf(String group, List<SchemaTable> tables, List<SchemaTableGroup> groups) {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), groups, 100, 200);
        SchemaTable entry = view.documented().stream().filter(table -> table.name().equals(group)).findFirst()
                .orElseThrow();
        assertThat(entry.group().partialKeys()).describedAs("no key of %s is declared by only some", group)
                .isZero();
        return entry.foreignKeys().stream().map(key -> view.drawnEntityNameOf(key.referencedTableName())).toList();
    }

    /**
     * <b>A partitioned group pointing into a partitioned group</b>: every child points at the counterpart of
     * its own entity and month, which are different tables and one entry - so the key is every table's, and
     * its arrow reaches the other group.
     */
    @Test
    void whenAGroupPointsAtTheCounterpartsInAnotherGroup_thenTheKeyIsKeptAndReachesThatGroup() {
        List<SchemaTable> tables = new ArrayList<>();
        for (String partition : List.of("orders_2601", "orders_2602", "parties_2601")) {
            tables.add(referencing("item_" + partition, "id_" + partition));
            tables.add(table("id_" + partition, "id_real", "hash"));
        }

        assertThat(targetsOf("item_<entity>_<yymm>", tables, List.of(ITEMS, BY_MONTH)))
                .containsExactly("id_<entity>_<yymm>");
    }

    /** A group whose tables point into tables of the same group points at itself. */
    @Test
    void whenAGroupPointsIntoItself_thenTheKeyIsKeptAndReachesTheGroup() {
        List<SchemaTable> tables = List.of(referencing("item_orders_2601", "item_orders_2602"),
                referencing("item_orders_2602", "item_orders_2601"),
                referencing("item_parties_2601", "item_parties_2601"));

        assertThat(targetsOf("item_<entity>_<yymm>", tables, List.of(ITEMS)))
                .containsExactly("item_<entity>_<yymm>");
    }

    /** Every table of a group pointing at one ordinary table keeps that key. */
    @Test
    void whenEveryTableOfAGroupPointsAtOneTable_thenTheKeyIsKept() {
        List<SchemaTable> tables = List.of(referencing("item_orders_2601", "orders_order"),
                referencing("item_parties_2601", "orders_order"), table("orders_order", "id"));

        assertThat(targetsOf("item_<entity>_<yymm>", tables, List.of(ITEMS))).containsExactly("orders_order");
    }

    /**
     * Partitions the schema no longer lists are resolved by their names, so a group pointing at old months of
     * one family still shares the key.
     */
    @Test
    void whenAGroupPointsAtPartitionsTheSchemaDoesNotList_thenTheyAreResolvedByName() {
        List<SchemaTable> tables = List.of(referencing("item_orders_2601", "order_2511"),
                referencing("item_parties_2601", "order_2512"), table("order_2601", "id"),
                table("order_2602", "id"));

        assertThat(targetsOf("item_<entity>_<yymm>", tables, List.of(ITEMS))).containsExactly("order_*");
    }

    /**
     * A pattern may make {@code entity} optional. A table it matches without one belongs to no entity, so it
     * is not grouped - rather than failing the page when the entities are listed.
     */
    @Test
    void whenAMatchHasNoEntity_thenTheTableIsNotGrouped() {
        SchemaTableGroup optional = SchemaTableGroup.of("id_<entity>_<yymm>",
                "id_(?<entity>[a-z]+)?_(?<partition>\\d{4})");

        List<SchemaTable> documented = schemaOf(List.of(
                table("id__2601", "id_real"), table("id__2602", "id_real"),
                table("id_orders_2601", "id_real"), table("id_orders_2602", "id_real")))
                .documentedTables(List.of(optional));

        assertThat(documented.getFirst().group().members()).extracting(GroupedTables.Member::entity)
                .containsExactly("orders");
        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "id__*");
    }

    /** One table is not a group: it keeps its own name, and the page is not given an entry of one. */
    @Test
    void whenOnlyOneTableHasTheGroupsShape_thenNothingIsGrouped() {
        List<SchemaTable> documented = schemaOf(List.of(table("id_orders_2601", "id_real")))
                .documentedTables(List.of(BY_MONTH));

        assertThat(namesOf(documented)).containsExactly("id_orders_2601");
        assertThat(documented.getFirst().group()).isNull();
    }

    /** A table belongs to the first group that matches it, so two groups never document one table twice. */
    @Test
    void whenTwoGroupsMatchATable_thenTheFirstConfiguredOneTakesIt() {
        SchemaTableGroup everything = SchemaTableGroup.of("everything", "(?<entity>.+)");
        List<SchemaTable> tables = new ArrayList<>(monthly("orders", 2601, 2602));
        tables.add(table("orders_order", "id_real", "hash"));

        List<SchemaTable> documented = schemaOf(tables).documentedTables(List.of(BY_MONTH, everything));

        assertThat(namesOf(documented)).containsExactly("id_<entity>_<yymm>", "orders_order");
    }

    /** Without a {@code partition} group an entity has a count and no range. */
    @Test
    void whenThePatternHasNoPartition_thenTheEntitiesHaveNoRange() {
        SchemaTableGroup byEntity = SchemaTableGroup.of("lookup_<entity>", "lookup_(?<entity>.+)");

        List<SchemaTable> documented = schemaOf(List.of(table("lookup_country", "code"),
                table("lookup_currency", "code"))).documentedTables(List.of(byEntity));

        assertThat(documented.getFirst().group().members()).containsExactly(
                new GroupedTables.Member("country", 1, null, null),
                new GroupedTables.Member("currency", 1, null, null));
    }

    /** The machinery of a schema is never part of a group, whatever a pattern matches. */
    @Test
    void whenAPatternMatchesTheMachinery_thenTheMachineryIsStillHidden() {
        SchemaTableGroup everything = SchemaTableGroup.of("everything", "(?<entity>.+)");

        DatabaseSchema schema = schemaOf(List.of(table("orders_a", "id"), table("orders_b", "id"),
                table("shedlock", "id")));

        assertThat(schema.documentedTables(List.of(everything)).getFirst().group().tables()).isEqualTo(2);
        assertThat(schema.hiddenTables()).containsExactly("shedlock");
    }

    @Test
    void whenNoGroupIsConfigured_thenTheEntriesAreTheOnesOfThePartitionsAlone() {
        List<SchemaTable> tables = new ArrayList<>(monthly("orders", 2601, 2603));
        tables.add(table("orders_order", "id"));

        assertThat(schemaOf(tables).documentedTables(List.of()))
                .isEqualTo(schemaOf(tables).documentedTables());
        assertThat(namesOf(schemaOf(tables).documentedTables())).containsExactly("id_orders_*", "orders_order");
    }

    @Test
    void aGroupIsLookedUpByTheExactNameOfItsComponent() {
        SchemaTableGroups groups = SchemaTableGroups.of(java.util.Map.of("orders-intake", List.of(BY_MONTH)));

        assertThat(groups.forComponent("orders-intake")).containsExactly(BY_MONTH);
        assertThat(groups.forComponent("orders-shipping")).isEmpty();
        assertThat(groups.forComponent(null)).isEmpty();
        assertThat(SchemaTableGroups.NONE.forComponent("orders-intake")).isEmpty();
    }

    @Test
    void aGroupWithoutAnEntityIsRefused() {
        assertThatThrownBy(() -> SchemaTableGroup.of("orders", "orders_(\\d+)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no named group 'entity'");
    }
}
