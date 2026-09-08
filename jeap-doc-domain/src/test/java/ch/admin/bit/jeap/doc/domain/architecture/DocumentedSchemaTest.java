package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived view a schema page is written from: the collapsed tables, the drawn subset and the listed
 * subset, worked out once instead of once per accessor.
 */
class DocumentedSchemaTest {

    private static SchemaTable table(String name, SchemaForeignKey... foreignKeys) {
        return new SchemaTable(name, List.of(new SchemaColumn("id", "uuid", false)), List.of("id"),
                List.of(foreignKeys));
    }

    private static DatabaseSchema schemaOf(List<SchemaTable> tables) {
        return new DatabaseSchema("orders_db", "1.2.3", tables);
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
        assertThat(view.notDrawn()).isEqualTo(160);
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

    /** A key into a table the diagram had no room for points at nothing, family or not. */
    @Test
    void drawnEntityNameOf_whenTheTableWasNotDrawn_thenItPointsAtNothing() {
        DocumentedSchema view = DocumentedSchema.of(schemaOf(shards("doc_meta", 6)), 0, 200);

        assertThat(view.drawn()).isEmpty();
        assertThat(view.drawnEntityNameOf("doc_meta_3")).isNull();
    }
}
