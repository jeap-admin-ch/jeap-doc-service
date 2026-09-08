package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which tables a database schema documents: the machinery left out, the partitions collapsed, the rest in the
 * alphabet.
 * <p>
 * What a page draws and lists out of them is bounded, and that is {@link DocumentedSchema}'s - a page is
 * written from the view and never from here, so the bounds are pinned in {@code DocumentedSchemaTest}.
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

    @Test
    void theTableListIsAlphabeticalWhateverOrderTheUpstreamListedThemIn() {
        DatabaseSchema schema = schemaOf(table("orders_party"), table("orders_order"), table("orders_line"));

        assertThat(schema.documentedTables()).extracting(SchemaTable::name)
                .containsExactly("orders_line", "orders_order", "orders_party");
    }

    /** A schema the upstream published without any table is a schema, not a null list. */
    @Test
    void aSchemaWithoutTablesIsEmptyRatherThanNull() {
        DatabaseSchema schema = new DatabaseSchema("orders_db", "1", null);

        assertThat(schema.tables()).isEmpty();
        assertThat(schema.documentedTables()).isEmpty();
        assertThat(schema.hiddenTables()).isEmpty();
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
}
