package ch.admin.bit.jeap.doc.domain.architecture;

import java.util.List;

/**
 * What an entry of a configured group stands for: its tables, by entity.
 * <p>
 * Carried so that the page can list the entities, which are what a reader looks for, rather than the tables.
 *
 * @param tables         how many tables the entry stands for
 * @param members        the entities, ordered by name
 * @param differentShape how many tables matched the group's pattern but have another shape, and are therefore
 *                       documented apart from it - on their own, or collapsed as partitions of one table
 * @param partialKeys    how many foreign keys only some of the tables declare. The entry shows the keys all of
 *                       them declare, and the page says how many it left out
 */
public record GroupedTables(int tables, List<Member> members, int differentShape, int partialKeys) {

    public GroupedTables {
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * One entity of the group.
     *
     * @param entity         the part of the table names the pattern calls {@code entity}
     * @param tables         how many tables belong to it
     * @param firstPartition the lowest {@code partition}, or null where the pattern has none
     * @param lastPartition  the highest
     */
    public record Member(String entity, int tables, String firstPartition, String lastPartition) {
    }
}
