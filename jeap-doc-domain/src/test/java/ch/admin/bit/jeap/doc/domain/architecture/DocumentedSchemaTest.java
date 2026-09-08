package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived view a schema page is written from: the collapsed tables, the drawn subset and the listed
 * subset, worked out once instead of once per accessor.
 * <p>
 * Both bounds are pinned here rather than on {@link DatabaseSchema}, because this is the class a page is
 * written from.
 */
class DocumentedSchemaTest {

    private static SchemaTable table(String name, SchemaForeignKey... foreignKeys) {
        return new SchemaTable(name, List.of(new SchemaColumn("id", "uuid", false)), List.of("id"),
                List.of(foreignKeys));
    }

    private static DatabaseSchema schemaOf(List<SchemaTable> tables) {
        return new DatabaseSchema("orders_db", "1.2.3", tables);
    }

    private static SchemaForeignKey foreignKeyTo(String table) {
        return new SchemaForeignKey("fk_" + table, List.of("ref_id"), table, List.of("id"));
    }

    /** Tables named so that none of them looks like a partition key - these cases are about the bounds. */
    private static List<SchemaTable> plainTables(int count) {
        List<SchemaTable> tables = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tables.add(table("t%03d_data".formatted(index)));
        }
        return tables;
    }

    private static List<SchemaTable> shards(String stem, int count) {
        List<SchemaTable> tables = new ArrayList<>();
        for (int suffix = 1; suffix <= count; suffix++) {
            tables.add(table(stem + "_" + suffix));
        }
        return tables;
    }

    /**
     * <b>The collapse is paid once per page.</b> Every set of the view is derived from
     * {@code documentedTables()}, which groups and sorts on every call - and a page render asks for it through
     * a dozen paths.
     */
    @Test
    void of_thenEverySetIsHeldRatherThanWorkedOutAgain() {
        DatabaseSchema schema = schemaOf(shards("doc_meta", 6));
        DocumentedSchema view = DocumentedSchema.of(schema, 100, 200);

        assertThat(view.documented()).isSameAs(view.documented());
        assertThat(view.drawn()).isSameAs(view.drawn());
        assertThat(view.listed()).isSameAs(view.listed());
        assertThat(schema.documentedTables())
                .describedAs("the schema itself builds a new list every time, which is what the view avoids")
                .isNotSameAs(schema.documentedTables());
    }

    /** Both numbers are on the page: a reader has to be able to see that 6583 tables exist. */
    @Test
    void of_whenAlmostEveryTableIsAPartition_thenBothCountsAreReported() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int family = 0; family < 260; family++) {
            tables.addAll(shards("part_" + family + "_table", 25));
        }
        tables.add(table("flyway_schema_history"));

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 100, 200);

        assertThat(view.rawTableCount()).isEqualTo(6501);
        assertThat(view.documentedCount()).isEqualTo(260);
        assertThat(view.collapsedFamilies()).isEqualTo(260);
        assertThat(view.hiddenTables()).containsExactly("flyway_schema_history");
    }

    @Test
    void of_thenTheDiagramAndTheListAreBoundedApart() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int index = 0; index < 260; index++) {
            // Named so that nothing here looks like a partition key: this is about the bound, not the
            // collapse, and one stem of 260 identical tables would become a single entry.
            tables.add(table("t%03d_data".formatted(index)));
        }

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 100, 200);

        assertThat(view.drawn()).hasSize(100);
        assertThat(view.notDrawn())
                .describedAs("the diagram's own bound, counted against the list it draws from")
                .isEqualTo(100);
        assertThat(view.listed()).hasSize(200);
        assertThat(view.notListed()).isEqualTo(60);
        assertThat(view.documentedCount()).isEqualTo(260);
    }

    /**
     * <b>An arrow into a shard has to find the family it became.</b> Otherwise collapsing a schema drops every
     * arrow in it - the referenced name is a table nothing draws any more.
     */
    @Test
    void drawnEntityNameOf_whenTheKeyPointsAtAShard_thenItFindsTheCollapsedEntity() {
        List<SchemaTable> tables = new ArrayList<>(shards("doc_meta", 6));
        tables.add(table("doc_root",
                new SchemaForeignKey("fk", List.of("meta_id"), "DOC_META_3", List.of("id"))));

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 100, 200);

        assertThat(view.drawnEntityNameOf("DOC_META_3"))
                .describedAs("the entity's own spelling, because a PlantUML code is case-sensitive")
                .isEqualTo("doc_meta_*");
        assertThat(view.drawnEntityNameOf("doc_root")).isEqualTo("doc_root");
        assertThat(view.drawnEntityNameOf("somewhere_else")).isNull();
        assertThat(view.drawnEntityNameOf(null)).isNull();
    }

    /** A schema of nothing but machinery has nothing to document, and the page says so instead of drawing. */
    @Test
    void of_whenASchemaIsNothingButMachinery_thenItIsEmpty() {
        assertThat(DocumentedSchema.of(schemaOf(List.of(table("flyway_schema_history"))), 100, 200).isEmpty())
                .isTrue();
        assertThat(DocumentedSchema.of(schemaOf(List.of(table("orders_order"))), 100, 200).isEmpty())
                .isFalse();
    }

    /** Below the limit nothing is cut, and the diagram is the whole schema. */
    @Test
    void drawn_whenTheSchemaFitsInTheDiagram_thenEveryTableIsDrawn() {
        DocumentedSchema view = DocumentedSchema.of(
                schemaOf(List.of(table("orders_order"), table("orders_party"))), 100, 200);

        assertThat(view.drawn()).extracting(SchemaTable::name)
                .containsExactly("orders_order", "orders_party");
        assertThat(view.notDrawn()).isZero();
    }

    /**
     * What is kept when the schema is too large to draw: the tables something has a foreign key into, so
     * that the arrows of the tables that <i>are</i> drawn still point at a box.
     */
    @Test
    void drawn_whenTheSchemaIsTooLargeToDraw_thenTheReferencedTablesAreKeptFirst() {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(List.of(
                table("a_first_alphabetically"),
                table("b_referencing", foreignKeyTo("z_referenced")),
                table("z_referenced"))), 1, 200);

        assertThat(view.drawn()).extracting(SchemaTable::name)
                .describedAs("the referenced table wins over the one that only sorts first")
                .containsExactly("z_referenced");
        assertThat(view.notDrawn()).isEqualTo(2);
    }

    /** Whatever the diagram has room for, it is drawn alphabetically - so two runs write the same bytes. */
    @Test
    void drawn_isAlwaysInTheAlphabet() {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(List.of(
                table("m_middle"),
                table("z_last", foreignKeyTo("a_referenced")),
                table("a_referenced"))), 2, 200);

        assertThat(view.drawn()).extracting(SchemaTable::name)
                .containsExactly("a_referenced", "m_middle");
    }

    /** A limit of zero, or a negative one from a misread configuration, draws nothing rather than throwing. */
    @Test
    void drawn_whenTheLimitIsNone_thenNothingIsDrawnAndTheListIsLeftAlone() {
        DatabaseSchema schema = schemaOf(List.of(table("orders_order"), table("orders_party")));

        assertThat(DocumentedSchema.of(schema, 0, 200).drawn()).isEmpty();
        assertThat(DocumentedSchema.of(schema, -1, 200).drawn()).isEmpty();
        assertThat(DocumentedSchema.of(schema, 0, 200).notDrawn()).isEqualTo(2);
        assertThat(DocumentedSchema.of(schema, 0, 200).listed()).hasSize(2);
    }

    /** A hundred tables is the shipped default, and a schema of a hundred and one says one was left out. */
    @Test
    void drawn_theDefaultLimitDrawsAHundredTables() {
        List<SchemaTable> tables = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            // Not "orders_table_000": one stem of a hundred and one identical tables is a family, and this
            // case is about the bound rather than about the collapse.
            tables.add(table("orders_%03d_table".formatted(index)));
        }

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 100, 200);

        assertThat(view.drawn()).hasSize(100);
        assertThat(view.notDrawn()).isEqualTo(1);
        assertThat(view.documentedCount()).isEqualTo(101);
    }

    /**
     * <b>Collapsing happens before the bound, and that is what turns the bound into a formality.</b> A
     * hundred and one shards of one table are one entry, so nothing is left out of the diagram at all -
     * where capping first would have drawn a hundred shards and dropped the rest.
     */
    @Test
    void documented_whenTheTablesArePartitionsOfOne_thenTheyAreOneEntryAndNothingIsLeftOut() {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(shards("orders_order", 101)), 100, 200);

        assertThat(view.documented()).extracting(SchemaTable::name).containsExactly("orders_order_*");
        assertThat(view.notDrawn()).isZero();
        assertThat(view.rawTableCount()).describedAs("what the schema really holds is still readable")
                .isEqualTo(101);
    }

    /**
     * <b>The list is bounded at the entry, exactly.</b> Two hundred is a page a reader can use; the unbounded
     * list is what cost one component's page an hour and a half to build.
     */
    @Test
    void listed_isTheFirstEntriesByNameAndSaysHowManyItLeftOut() {
        assertThat(DocumentedSchema.of(schemaOf(plainTables(200)), 100, 200).listed())
                .describedAs("exactly at the bound, nothing left out").hasSize(200);
        assertThat(DocumentedSchema.of(schemaOf(plainTables(200)), 100, 200).notListed()).isZero();

        DocumentedSchema over = DocumentedSchema.of(schemaOf(plainTables(201)), 100, 200);
        assertThat(over.listed()).hasSize(200);
        assertThat(over.notListed()).isOne();
        assertThat(over.listed()).extracting(SchemaTable::name)
                .describedAs("by name, because a reader looks a table up here")
                .startsWith("t000_data").endsWith("t199_data");
    }

    /** A schema of nothing, and a bound of nothing, are both legal and both empty. */
    @Test
    void listed_whenThereIsNothingToList_thenItIsEmptyRatherThanRefused() {
        assertThat(DocumentedSchema.of(schemaOf(plainTables(0)), 100, 200).listed()).isEmpty();
        assertThat(DocumentedSchema.of(schemaOf(plainTables(5)), 100, 0).listed()).isEmpty();
        assertThat(DocumentedSchema.of(schemaOf(plainTables(5)), 100, 0).notListed()).isEqualTo(5);
        assertThat(DocumentedSchema.of(schemaOf(plainTables(5)), 100, -1).listed()).isEmpty();
    }

    /** A foreign key into a shard keeps the entity its family became on the diagram, not some other table. */
    @Test
    void drawn_whenAKeyPointsIntoAShard_thenTheCollapsedFamilyIsWhatIsKept() {
        List<SchemaTable> tables = new ArrayList<>(shards("orders_party", 5));
        tables.add(table("orders_order_root", foreignKeyTo("orders_party_3")));
        tables.add(table("zzz_unreferenced"));

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 2, 200);

        assertThat(view.drawn()).extracting(SchemaTable::name)
                .describedAs("the referenced family and the table pointing at it, before the unreferenced one")
                .containsExactly("orders_order_root", "orders_party_*");
    }

    /**
     * <b>The diagram is drawn out of the listed entries.</b> Picking by priority out of all of them would
     * keep a referenced table the list is bounded past - a box on the diagram that a reader cannot look up
     * anywhere on the page, while the note above it says the list carries what the diagram left out.
     */
    @Test
    void drawn_isAlwaysWithinTheList() {
        List<SchemaTable> tables = new ArrayList<>(plainTables(260));
        tables.set(0, table("t000_data", foreignKeyTo("t250_data")));

        DocumentedSchema view = DocumentedSchema.of(schemaOf(tables), 100, 200);

        assertThat(view.listed()).describedAs("every box has an entry a reader can look up")
                .containsAll(view.drawn());
        assertThat(view.drawn()).extracting(SchemaTable::name).doesNotContain("t250_data");
        assertThat(view.drawnEntityNameOf("t250_data"))
                .describedAs("and the arrow into it is dropped rather than pointing at a box nothing lists")
                .isNull();
    }

    /** A key into a table the diagram had no room for points at nothing, family or not. */
    @Test
    void drawnEntityNameOf_whenTheTableWasNotDrawn_thenItPointsAtNothing() {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(shards("doc_meta", 6)), 0, 200);

        assertThat(view.drawn()).isEmpty();
        assertThat(view.drawnEntityNameOf("doc_meta_3")).isNull();
    }
}
