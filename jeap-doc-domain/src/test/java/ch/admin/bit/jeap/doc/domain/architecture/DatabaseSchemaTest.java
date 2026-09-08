package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which tables a database schema documents, which of them a diagram draws, and in what order.
 * <p>
 * The diagram is bounded and the table list is not, so that a schema of three hundred tables gives a reader a
 * page they can use rather than one that fails to render.
 */
class DatabaseSchemaTest {

    private static SchemaColumn column(String name) {
        return new SchemaColumn(name, "uuid", false);
    }

    private static SchemaTable table(String name, SchemaForeignKey... foreignKeys) {
        return new SchemaTable(name, List.of(column("id")), List.of("id"), List.of(foreignKeys));
    }

    private static SchemaForeignKey foreignKeyTo(String table) {
        return new SchemaForeignKey("fk_" + table, List.of("ref_id"), table, List.of("id"));
    }

    private static DatabaseSchema schemaOf(SchemaTable... tables) {
        return new DatabaseSchema("orders_db", "1.2.3", List.of(tables));
    }

    /**
     * The two machinery tables of every jEAP schema are on neither the diagram nor the list. The page names
     * them, so that nothing is missing silently.
     */
    @Test
    void theMachineryOfASchemaIsNotDocumentedAndIsNamed() {
        DatabaseSchema schema = schemaOf(table("orders_order"), table("FLYWAY_SCHEMA_HISTORY"),
                table("shedlock"));

        assertThat(schema.documentedTables()).extracting(SchemaTable::name).containsExactly("orders_order");
        assertThat(schema.hiddenTables())
                .describedAs("matched without regard to case, and named as the schema spells them")
                .containsExactly("FLYWAY_SCHEMA_HISTORY", "shedlock");
    }

    /** A schema of nothing but machinery has nothing to document, and the page says so instead of drawing. */
    @Test
    void aSchemaOfNothingButMachineryIsEmpty() {
        assertThat(schemaOf(table("flyway_schema_history")).isEmpty()).isTrue();
        assertThat(schemaOf(table("orders_order")).isEmpty()).isFalse();
    }

    @Test
    void theTableListIsAlphabeticalWhateverOrderTheUpstreamListedThemIn() {
        DatabaseSchema schema = schemaOf(table("orders_party"), table("orders_order"), table("orders_line"));

        assertThat(schema.documentedTables()).extracting(SchemaTable::name)
                .containsExactly("orders_line", "orders_order", "orders_party");
    }

    /** Below the limit nothing is cut, and the diagram is the whole schema. */
    @Test
    void whenTheSchemaFitsInTheDiagram_thenEveryTableIsDrawn() {
        DatabaseSchema schema = schemaOf(table("orders_order"), table("orders_party"));

        assertThat(schema.drawnTables(100)).extracting(SchemaTable::name)
                .containsExactly("orders_order", "orders_party");
        assertThat(schema.notDrawn(100)).isZero();
    }

    /**
     * What is kept when the schema is too large to draw: the tables something has a foreign key into, so
     * that the arrows of the tables that <i>are</i> drawn still point at a box.
     */
    @Test
    void whenTheSchemaIsTooLargeToDraw_thenTheReferencedTablesAreKeptFirst() {
        DatabaseSchema schema = schemaOf(
                table("a_first_alphabetically"),
                table("b_referencing", foreignKeyTo("z_referenced")),
                table("z_referenced"));

        assertThat(schema.drawnTables(1)).extracting(SchemaTable::name)
                .describedAs("the referenced table wins over the one that only sorts first")
                .containsExactly("z_referenced");
        assertThat(schema.notDrawn(1)).isEqualTo(2);
    }

    /** Whatever the diagram has room for, it is drawn alphabetically - so two runs write the same bytes. */
    @Test
    void theDrawingOrderIsAlwaysTheAlphabet() {
        DatabaseSchema schema = schemaOf(
                table("m_middle"),
                table("z_last", foreignKeyTo("a_referenced")),
                table("a_referenced"));

        assertThat(schema.drawnTables(2)).extracting(SchemaTable::name)
                .containsExactly("a_referenced", "m_middle");
        assertThat(schema.drawnTables(2)).isEqualTo(schema.drawnTables(2));
    }

    /** A limit of zero, or a negative one from a misread configuration, draws nothing rather than throwing. */
    @Test
    void aLimitOfNoneDrawsNothingAndLeavesTheListAlone() {
        DatabaseSchema schema = schemaOf(table("orders_order"), table("orders_party"));

        assertThat(schema.drawnTables(0)).isEmpty();
        assertThat(schema.drawnTables(-1)).isEmpty();
        assertThat(schema.notDrawn(0)).isEqualTo(2);
        assertThat(schema.documentedTables()).hasSize(2);
    }

    /** A schema the upstream published without any table is a schema, not a null list. */
    @Test
    void aSchemaWithoutTablesIsEmptyRatherThanNull() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", null);

        assertThat(schema.tables()).isEmpty();
        assertThat(schema.documentedTables()).isEmpty();
        assertThat(schema.hiddenTables()).isEmpty();
        assertThat(schema.isEmpty()).isTrue();
    }

    /** The primary key columns come first on the diagram, and everything else below the separator. */
    @Test
    void aTableSeparatesItsKeyColumnsFromTheRest() {
        SchemaTable table = new SchemaTable("orders_order",
                List.of(column("total"), column("id"), column("party_id")),
                List.of("ID"), List.of(foreignKeyTo("orders_party")));

        assertThat(table.keyColumns()).extracting(SchemaColumn::name)
                .describedAs("the key names its column in another case, and it is still the key column")
                .containsExactly("id");
        assertThat(table.otherColumns()).extracting(SchemaColumn::name)
                .containsExactly("total", "party_id");
        assertThat(table.isForeignKeyColumn("REF_ID")).isTrue();
        assertThat(table.isForeignKeyColumn("total")).isFalse();
    }

    /** A hundred tables is the shipped default, and a schema of a hundred and one says one was left out. */
    @Test
    void theDefaultLimitDrawsAHundredTables() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            // Not "orders_table_000": one stem of a hundred and one identical tables is a family, and this
            // test is about the bound rather than about the collapse.
            tables.add(table("orders_%03d_table".formatted(i)));
        }
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", tables);

        assertThat(schema.drawnTables(100)).hasSize(100);
        assertThat(schema.notDrawn(100)).isEqualTo(1);
        assertThat(schema.documentedTables()).hasSize(101);
    }

    /**
     * <b>Collapsing happens before the bound, and that is what turns the bound into a formality.</b> A
     * hundred and one shards of one table are one entry, so nothing is left out of the diagram at all -
     * where capping first would have drawn a hundred shards and dropped the rest.
     */
    @Test
    void whenTheTablesArePartitionsOfOne_thenTheyAreOneEntryAndNothingIsLeftOut() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tables.add(table("orders_order_%03d".formatted(i)));
        }
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", tables);

        assertThat(schema.documentedTables()).extracting(SchemaTable::name)
                .containsExactly("orders_order_*");
        assertThat(schema.notDrawn(100)).isZero();
        assertThat(schema.rawTableCount()).describedAs("what the schema really holds is still readable")
                .isEqualTo(101);
    }

    /**
     * <b>The list is bounded at the entry, exactly.</b> Two hundred is a page a reader can use; the unbounded
     * list is what cost one component's page an hour and a half to build.
     */
    @Test
    void listedTables_isTheFirstEntriesByNameAndSaysHowManyItLeftOut() {
        assertThat(listOf(200).listedTables(200)).describedAs("exactly at the bound, nothing left out")
                .hasSize(200);
        assertThat(listOf(200).notListed(200)).isZero();
        assertThat(listOf(201).listedTables(200)).hasSize(200);
        assertThat(listOf(201).notListed(200)).isOne();
        assertThat(listOf(201).listedTables(200)).extracting(SchemaTable::name)
                .describedAs("by name, because a reader looks a table up here")
                .startsWith("t000_data").endsWith("t199_data");
    }

    /** A schema of nothing, and a bound of nothing, are both legal and both empty. */
    @Test
    void listedTables_whenThereIsNothingToList_thenItIsEmptyRatherThanRefused() {
        assertThat(listOf(0).listedTables(200)).isEmpty();
        assertThat(listOf(5).listedTables(0)).isEmpty();
        assertThat(listOf(5).notListed(0)).isEqualTo(5);
        assertThat(listOf(5).listedTables(-1)).isEmpty();
    }

    /** Tables named so that none of them looks like a partition key - these cases are about the bound. */
    private static DatabaseSchema listOf(int count) {
        List<SchemaTable> tables = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tables.add(table("t%03d_data".formatted(index)));
        }
        return new DatabaseSchema("orders_db", "1", tables);
    }

    /** A foreign key into a shard keeps the entity its family became on the diagram, not some other table. */
    @Test
    void whenAKeyPointsIntoAShard_thenTheCollapsedFamilyIsWhatIsKept() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tables.add(table("orders_party_%d".formatted(i)));
        }
        tables.add(table("orders_order_root", foreignKeyTo("orders_party_3")));
        tables.add(table("zzz_unreferenced"));
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", tables);

        assertThat(schema.drawnTables(2)).extracting(SchemaTable::name)
                .describedAs("the referenced family and the table pointing at it, before the unreferenced one")
                .containsExactly("orders_order_root", "orders_party_*");
    }
}
