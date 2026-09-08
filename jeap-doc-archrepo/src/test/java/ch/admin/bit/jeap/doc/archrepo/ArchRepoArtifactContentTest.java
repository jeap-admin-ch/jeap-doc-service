package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the two artifacts the architecture repository publishes, in the shapes it publishes them.
 * <p>
 * <b>Nothing here throws.</b> There is no {@code try} around the generation of a site, so a malformed
 * specification has to cost its own page and not every system of the environment.
 */
class ArchRepoArtifactContentTest {

    private final ArchRepoArtifactContent content = new ArchRepoArtifactContent(JsonMapper.builder().build());

    /**
     * The schema as {@code jeap-db-schema-publisher} pushes it and the architecture repository stores it,
     * under the field names of its own published record model.
     */
    private static final String SCHEMA = """
            { "name": "orders_db", "version": "1.2.3",
              "tables": [
                { "name": "orders_order",
                  "columns": [ { "name": "id", "type": "uuid", "nullable": false },
                               { "name": "party_id", "type": "uuid", "nullable": true },
                               { "name": "total", "type": "numeric(12,2)", "nullable": false } ],
                  "primaryKey": { "name": "pk_orders_order", "columnNames": ["id"] },
                  "foreignKeys": [ { "name": "fk_order_party", "columnNames": ["party_id"],
                                     "referencedTableName": "orders_party",
                                     "referencedColumnNames": ["id"] } ] },
                { "name": "orders_party",
                  "columns": [ { "name": "id", "type": "uuid", "nullable": false } ],
                  "primaryKey": { "name": "pk_orders_party", "columnNames": ["id"] },
                  "foreignKeys": [] },
                { "name": "flyway_schema_history",
                  "columns": [ { "name": "installed_rank", "type": "integer", "nullable": false } ] },
                { "name": "shedlock",
                  "columns": [ { "name": "name", "type": "varchar(64)", "nullable": false } ] }
              ] }""";

    /** A specification as a component publishes it: tags, a server, and operations under paths. */
    private static final String SPEC = """
            { "openapi": "3.0.1",
              "info": { "title": "Orders API", "version": "2.4.0" },
              "servers": [ { "url": "https://orders.example.ch/api" },
                           { "url": "https://orders-ref.example.ch/api" } ],
              "tags": [ { "name": "Orders", "description": "Everything about an order" },
                        { "name": "Reports", "description": "Nobody uses this tag" } ],
              "paths": {
                "/api/orders": {
                  "get": { "summary": "List the orders", "tags": ["Orders"] },
                  "post": { "summary": "Place an order", "tags": ["Orders", "Intake"] }
                },
                "/api/orders/{id}": {
                  "get": { "summary": "One order", "tags": ["Orders"], "deprecated": true },
                  "parameters": [ { "name": "id", "in": "path" } ]
                },
                "/api/health": { "get": { "summary": "Is it up" } }
              } }""";

    private static ArchitectureArtifact artifact(ArchitectureImportKind kind, String body) {
        return new ArchitectureArtifact("prod", kind, "orders", "orders-intake", "1", "\"sha256:one\"",
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                body == null ? 0 : body.length(), Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    private static ArchitectureArtifact schema(String body) {
        return artifact(ArchitectureImportKind.DATABASE_SCHEMA, body);
    }

    private static ArchitectureArtifact spec(String body) {
        return artifact(ArchitectureImportKind.OPENAPI_SPEC, body);
    }

    @Test
    void databaseSchema_readsTheNameTheVersionAndEveryTable() {
        DatabaseSchema read = content.databaseSchema(schema(SCHEMA)).orElseThrow();

        assertThat(read.name()).isEqualTo("orders_db");
        assertThat(read.version()).isEqualTo("1.2.3");
        assertThat(read.tables()).extracting(SchemaTable::name)
                .describedAs("every table as it arrived - what is left out is decided when it is drawn")
                .containsExactly("orders_order", "orders_party", "flyway_schema_history", "shedlock");
        assertThat(read.tables()).extracting(SchemaTable::shards)
                .describedAs("a table the adapter maps stands for itself; only the collapse builds a family")
                .containsOnlyNulls();
    }

    @Test
    void databaseSchema_readsTheColumnsWithTheirTypeAndNullability() {
        SchemaTable order = content.databaseSchema(schema(SCHEMA)).orElseThrow().documentedTables().getFirst();

        assertThat(order.columns()).extracting(SchemaColumn::name, SchemaColumn::type, SchemaColumn::nullable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id", "uuid", false),
                        org.assertj.core.groups.Tuple.tuple("party_id", "uuid", true),
                        org.assertj.core.groups.Tuple.tuple("total", "numeric(12,2)", false));
    }

    /**
     * The primary key is carried as the columns it is made of, not as its constraint name. No page shows
     * that name; the diagram marks the columns.
     */
    @Test
    void databaseSchema_readsThePrimaryKeyAsItsColumnsAndTheForeignKeysWhole() {
        SchemaTable order = content.databaseSchema(schema(SCHEMA)).orElseThrow().documentedTables().getFirst();

        assertThat(order.primaryKeyColumns()).containsExactly("id");
        assertThat(order.isKeyColumn("id")).isTrue();
        assertThat(order.isForeignKeyColumn("party_id")).isTrue();
        assertThat(order.foreignKeys()).singleElement().satisfies(key -> {
            assertThat(key.name()).isEqualTo("fk_order_party");
            assertThat(key.columnNames()).containsExactly("party_id");
            assertThat(key.referencedTableName()).isEqualTo("orders_party");
            assertThat(key.referencedColumnNames()).containsExactly("id");
        });
    }

    /** A table without a name has no box and no row, so it is left out rather than failing the page. */
    @Test
    void databaseSchema_whenATableHasNoName_thenItIsLeftOutAndTheRestIsRead() {
        DatabaseSchema read = content.databaseSchema(schema("""
                { "name": "orders_db", "version": "1",
                  "tables": [ { "columns": [] }, { "name": "orders_order", "columns": [] } ] }""")).orElseThrow();

        assertThat(read.tables()).extracting(SchemaTable::name).containsExactly("orders_order");
    }

    /** A table the publisher wrote without a primary key or foreign keys is a table, not a failure. */
    @Test
    void databaseSchema_whenATableHasNeitherKey_thenTheListsAreEmptyRatherThanNull() {
        SchemaTable table = content.databaseSchema(schema("""
                { "name": "orders_db", "version": "1",
                  "tables": [ { "name": "orders_log",
                                "columns": [ { "name": "line", "type": "text", "nullable": true } ] } ] }"""))
                .orElseThrow().tables().getFirst();

        assertThat(table.primaryKeyColumns()).isEmpty();
        assertThat(table.foreignKeys()).isEmpty();
        assertThat(table.otherColumns()).extracting(SchemaColumn::name).containsExactly("line");
    }

    @Test
    void restApi_readsTheVersionAndTheFirstServer() {
        RestApiOverview read = content.restApi(spec(SPEC)).orElseThrow();

        assertThat(read.version()).isEqualTo("2.4.0");
        assertThat(read.serverUrl()).describedAs("the first server, not every stage of the same API")
                .isEqualTo("https://orders.example.ch/api");
    }

    /**
     * An operation is grouped by its first tag. A tag the specification declares and nothing uses is not a
     * group: it would be an empty table promising part of an API that is not there.
     */
    @Test
    void restApi_groupsTheOperationsByTheirFirstTagAndShowsNoUnusedTag() {
        RestApiOverview read = content.restApi(spec(SPEC)).orElseThrow();

        assertThat(read.groups()).extracting(group -> group.name())
                .containsExactly("Orders", RestApiOverview.UNGROUPED);
        assertThat(read.groups().getFirst().description()).isEqualTo("Everything about an order");
        assertThat(read.groups().getFirst().operations())
                .extracting(ApiOperation::method, ApiOperation::path, ApiOperation::deprecated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GET", "/api/orders", false),
                        org.assertj.core.groups.Tuple.tuple("POST", "/api/orders", false),
                        org.assertj.core.groups.Tuple.tuple("GET", "/api/orders/{id}", true));
    }

    /** Only the methods of a path item are operations. A {@code parameters} key beside them is not one. */
    @Test
    void restApi_readsOnlyTheMethodsOfAPathItem() {
        RestApiOverview read = content.restApi(spec(SPEC)).orElseThrow();

        assertThat(read.operations()).extracting(ApiOperation::label)
                .containsExactlyInAnyOrder("GET /api/orders", "POST /api/orders", "GET /api/orders/{id}",
                        "GET /api/health");
    }

    @Test
    void restApi_readsTheSummaryOfAnOperation() {
        RestApiOverview read = content.restApi(spec(SPEC)).orElseThrow();

        assertThat(read.operations()).anySatisfy(operation -> {
            assertThat(operation.path()).isEqualTo("/api/health");
            assertThat(operation.summary()).isEqualTo("Is it up");
            assertThat(operation.group()).isEqualTo(RestApiOverview.UNGROUPED);
        });
    }

    /** A specification with no path at all is an overview with no group, not a failure. */
    @Test
    void restApi_whenTheSpecificationHasNoPath_thenTheOverviewIsEmpty() {
        RestApiOverview read = content.restApi(spec("{\"openapi\": \"3.0.1\", \"paths\": {}}")).orElseThrow();

        assertThat(read.isEmpty()).isTrue();
        assertThat(read.version()).isNull();
    }

    /**
     * Everything that can arrive instead of an artifact: nothing at all, bytes that are not JSON, JSON that
     * is not an object, and half a document. Each answers empty, and the page falls back to the model.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "   ", "not json at all", "[1, 2, 3]", "\"a string\"", "42",
            "{ \"name\": \"orders_db\", \"tables\": [ { \"name\": \"orders_"})
    void whatIsNotAnArtifact_answersEmptyRatherThanThrowing(String body) {
        assertThat(content.databaseSchema(schema(body))).isEmpty();
        assertThat(content.restApi(spec(body))).isEmpty();
    }

    @Test
    void whenTheArtifactCarriesNoBytesAtAll_thenItAnswersEmpty() {
        assertThat(content.databaseSchema(schema(null))).isEmpty();
        assertThat(content.restApi(spec(null))).isEmpty();
    }

    /**
     * A schema whose {@code tables} is not an array: it parses as JSON but is not the shape. The page then
     * says the schema is empty instead of the build ending.
     */
    @Test
    void whenAFieldHasTheWrongType_thenWhatCanBeReadIsReadAndTheRestIsEmpty() {
        DatabaseSchema read = content.databaseSchema(schema("""
                { "name": "orders_db", "version": 7, "tables": "none" }""")).orElseThrow();

        assertThat(read.name()).isEqualTo("orders_db");
        assertThat(read.version()).describedAs("a number is not the version string this shows").isNull();
        assertThat(read.tables()).isEmpty();
        assertThat(read.documentedTables()).isEmpty();
    }

    /** A foreign key of a table that is not documented is still read; nothing renders an arrow to nowhere. */
    @Test
    void aForeignKeyIntoAHiddenTableIsRead() {
        SchemaTable table = content.databaseSchema(schema("""
                { "name": "orders_db", "version": "1",
                  "tables": [ { "name": "orders_order", "columns": [],
                                "foreignKeys": [ { "name": "fk", "columnNames": ["lock_name"],
                                                   "referencedTableName": "shedlock",
                                                   "referencedColumnNames": ["name"] } ] } ] }"""))
                .orElseThrow().documentedTables().getFirst();

        assertThat(table.foreignKeys()).extracting(SchemaForeignKey::referencedTableName)
                .containsExactly("shedlock");
    }
}
