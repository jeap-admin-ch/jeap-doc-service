package ch.admin.bit.jeap.doc.archrepo;

import ch.admin.bit.jeap.doc.domain.architecture.ApiOperation;
import ch.admin.bit.jeap.doc.domain.architecture.DatabaseSchema;
import ch.admin.bit.jeap.doc.domain.architecture.RestApiOverview;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaColumn;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaForeignKey;
import ch.admin.bit.jeap.doc.domain.architecture.SchemaTable;
import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureArtifact;
import ch.admin.bit.jeap.doc.domain.port.ArchitectureArtifactContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the two artifacts the architecture repository publishes about a component.
 * <p>
 * In this module because both formats are the upstream's: the database schema is its own record model, and
 * the specification is the one it was pushed.
 * <p>
 * The schema is mapped into records of this service's own model, field for field under the same names.
 * Depending on {@code jeap-archrepo-dbschema-model} instead would put an arch repo artifact and its
 * validation annotations on this service's model for five records that will not move.
 * <p>
 * <b>The specification is read as a tree.</b> Only five things are taken out of it - {@code info.version},
 * the tags, the first server, the paths and each operation's summary - and all five are string-valued in
 * every version of the format. An OpenAPI parser resolves references and models the whole specification,
 * which is a large dependency for that. Anything unrecognised is ignored.
 */
@Slf4j
@RequiredArgsConstructor
class ArchRepoArtifactContent implements ArchitectureArtifactContent {

    /** The methods of an OpenAPI path item. Anything else under a path is not an operation. */
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final JsonMapper json;

    @Override
    public Optional<DatabaseSchema> databaseSchema(ArchitectureArtifact artifact) {
        return read(artifact).map(root -> new DatabaseSchema(
                text(root, "name"),
                text(root, "version"),
                tablesOf(root.path("tables"))));
    }

    @Override
    public Optional<RestApiOverview> restApi(ArchitectureArtifact artifact) {
        return read(artifact).map(root -> RestApiOverview.of(
                text(root.path("info"), "version"),
                serverUrlOf(root.path("servers")),
                operationsOf(root.path("paths")),
                tagDescriptionsOf(root.path("tags"))));
    }

    /**
     * The artifact's bytes as a JSON object, or empty with a log line naming the component.
     * <p>
     * Every failure lands here: an empty artifact, bytes that are not JSON, and JSON that is not an object.
     * None of them throws - see the port.
     */
    private Optional<JsonNode> read(ArchitectureArtifact artifact) {
        byte[] content = artifact.content();
        if (content == null || content.length == 0) {
            log.warn("The {} of {}/{} in the environment {} is empty, so it is not documented.",
                    artifact.kind(), artifact.system(), artifact.component(), artifact.environment());
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (RuntimeException e) {
            // WARN, not ERROR: it is something a component published wrongly, and nothing the operators of
            // the doc service can act on.
            log.warn("The {} of {}/{} in the environment {} is not readable as JSON, so it is not documented: "
                     + "{}", artifact.kind(), artifact.system(), artifact.component(), artifact.environment(),
                    e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            log.warn("The {} of {}/{} in the environment {} is JSON but not an object, so it is not "
                     + "documented.", artifact.kind(), artifact.system(), artifact.component(),
                    artifact.environment());
            return Optional.empty();
        }
        return Optional.of(root);
    }

    private static List<SchemaTable> tablesOf(JsonNode tables) {
        List<SchemaTable> read = new ArrayList<>();
        for (JsonNode table : arrayOf(tables)) {
            String name = text(table, "name");
            // A table without a name has no box and no row, and the rest of the schema is still worth a page.
            if (name == null || name.isBlank()) {
                continue;
            }
            read.add(new SchemaTable(name, columnsOf(table.path("columns")),
                    stringsOf(table.path("primaryKey").path("columnNames")),
                    foreignKeysOf(table.path("foreignKeys"))));
        }
        return read;
    }

    private static List<SchemaColumn> columnsOf(JsonNode columns) {
        List<SchemaColumn> read = new ArrayList<>();
        for (JsonNode column : arrayOf(columns)) {
            String name = text(column, "name");
            if (name != null && !name.isBlank()) {
                read.add(new SchemaColumn(name, text(column, "type"),
                        column.path("nullable").asBoolean(false)));
            }
        }
        return read;
    }

    private static List<SchemaForeignKey> foreignKeysOf(JsonNode keys) {
        List<SchemaForeignKey> read = new ArrayList<>();
        for (JsonNode key : arrayOf(keys)) {
            read.add(new SchemaForeignKey(text(key, "name"), stringsOf(key.path("columnNames")),
                    text(key, "referencedTableName"), stringsOf(key.path("referencedColumnNames"))));
        }
        return read;
    }

    /**
     * Where the API is served, from the <b>first</b> server of the specification. A specification often lists
     * the same API on one host per stage, and the page is about what the API is rather than where it runs.
     */
    private static String serverUrlOf(JsonNode servers) {
        for (JsonNode server : arrayOf(servers)) {
            String url = text(server, "url");
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    private static Map<String, String> tagDescriptionsOf(JsonNode tags) {
        Map<String, String> described = new LinkedHashMap<>();
        for (JsonNode tag : arrayOf(tags)) {
            String name = text(tag, "name");
            if (name != null && !name.isBlank()) {
                described.put(name, text(tag, "description"));
            }
        }
        return described;
    }

    private static List<ApiOperation> operationsOf(JsonNode paths) {
        List<ApiOperation> read = new ArrayList<>();
        if (!paths.isObject()) {
            return read;
        }
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            JsonNode item = path.getValue();
            if (!item.isObject()) {
                continue;
            }
            for (String method : METHODS) {
                JsonNode operation = item.path(method);
                if (operation.isObject()) {
                    read.add(new ApiOperation(method.toUpperCase(Locale.ROOT), path.getKey(),
                            text(operation, "summary"), operation.path("deprecated").asBoolean(false),
                            stringsOf(operation.path("tags"))));
                }
            }
        }
        return read;
    }

    private static List<String> stringsOf(JsonNode array) {
        List<String> read = new ArrayList<>();
        for (JsonNode value : arrayOf(array)) {
            if (value.isString() && !value.stringValue().isBlank()) {
                read.add(value.stringValue());
            }
        }
        return read;
    }

    private static Iterable<JsonNode> arrayOf(JsonNode node) {
        return node.isArray() ? node : List.of();
    }

    /** A string field, or null. A field of any other type reads as null rather than as its text. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }
}
