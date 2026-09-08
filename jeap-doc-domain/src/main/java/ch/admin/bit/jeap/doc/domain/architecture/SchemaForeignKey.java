package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * One foreign key of a {@link SchemaTable}, which the diagram draws as an arrow.
 *
 * @param name                   the constraint name
 * @param columnNames            the columns of this table the key is made of
 * @param referencedTableName    the table it points at
 * @param referencedColumnNames  the columns of that table it points at
 */
public record SchemaForeignKey(String name, List<String> columnNames, String referencedTableName,
                               List<String> referencedColumnNames) {

    public SchemaForeignKey {
        columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
        referencedColumnNames = referencedColumnNames == null ? List.of()
                : List.copyOf(referencedColumnNames);
    }
}
