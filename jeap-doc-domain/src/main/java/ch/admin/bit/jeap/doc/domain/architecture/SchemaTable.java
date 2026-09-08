package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One table of a {@link DatabaseSchema}, or one family of partitions standing for the table they are of.
 *
 * @param name              the table name
 * @param columns           its columns, in the order the upstream listed them
 * @param primaryKeyColumns the columns the primary key is made of, empty where there is none. The name of
 *                          the constraint is not carried; nothing shows it
 * @param foreignKeys       the foreign keys it declares
 * @param shards            what this entry stands for where it is a collapsed family of partitions, null for
 *                          an ordinary table. One type flows through both renderings, which is what lets the
 *                          diagram and the list agree without either of them knowing about collapsing
 */
public record SchemaTable(String name, List<SchemaColumn> columns, List<String> primaryKeyColumns,
                          List<SchemaForeignKey> foreignKeys, Shards shards) {

    public SchemaTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKeyColumns = primaryKeyColumns == null ? List.of() : List.copyOf(primaryKeyColumns);
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }

    /** An ordinary table. Only {@link ShardFamilies} ever builds one that stands for a family. */
    public SchemaTable(String name, List<SchemaColumn> columns, List<String> primaryKeyColumns,
                       List<SchemaForeignKey> foreignKeys) {
        this(name, columns, primaryKeyColumns, foreignKeys, null);
    }

    /** The primary key columns, in the order the table lists its columns. */
    public List<SchemaColumn> keyColumns() {
        return columns.stream().filter(column -> isKeyColumn(column.name())).toList();
    }

    /** The other columns. The diagram draws them below a separator. */
    public List<SchemaColumn> otherColumns() {
        return columns.stream().filter(column -> !isKeyColumn(column.name())).toList();
    }

    public boolean isKeyColumn(String columnName) {
        return primaryKeyColumns.stream().anyMatch(key -> key.equalsIgnoreCase(columnName));
    }

    /** Whether a foreign key of this table is made of that column. The diagram marks those. */
    public boolean isForeignKeyColumn(String columnName) {
        return foreignKeys.stream()
                .flatMap(key -> key.columnNames().stream())
                .anyMatch(name -> name.equalsIgnoreCase(columnName));
    }
}
